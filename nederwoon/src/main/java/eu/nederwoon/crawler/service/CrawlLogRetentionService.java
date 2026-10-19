package eu.nederwoon.crawler.service;

import eu.nederwoon.crawler.model.CrawlLog;
import eu.nederwoon.crawler.model.CrawlLogRollup;
import eu.nederwoon.crawler.repository.CrawlLogRepository;
import eu.nederwoon.crawler.repository.CrawlLogRollupRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps {@code crawl_log} bounded. A row is written every crawl pass and nothing ever deleted them,
 * so the table grows without limit — roofz reached 475k rows, and because every stats aggregate scans
 * it, that one table was what kept its heap pinned at the JVM ceiling.
 *
 * <p>Rows past the retention window are folded into {@link CrawlLogRollup} and deleted. Lifetime
 * totals on /stats stay exact (rollup + live rows); only the per-pass detail ages out, and nothing
 * reads that beyond "recent crawls" and the 24h figures.
 *
 * <p>Deleting is done in bounded batches, each in its own transaction: the first run has a whole
 * backlog to clear, and doing that in one statement would spike the very heap this is meant to
 * relieve. Aggregating each batch immediately before deleting it means an interrupted run can be
 * resumed without double-counting.
 */
@Service
public class CrawlLogRetentionService {

    private static final Logger log = LoggerFactory.getLogger(CrawlLogRetentionService.class);

    /** Rows deleted per transaction. Small enough to stay cheap, large enough to drain a backlog. */
    private static final int BATCH_SIZE = 5_000;

    /** Ceiling on batches per run, so a huge backlog is drained over a few runs, not one long stall. */
    private static final int MAX_BATCHES_PER_RUN = 40;

    private final CrawlLogRepository crawlLogRepository;
    private final CrawlLogRollupRepository rollupRepository;
    private final Clock clock;
    private final int retentionDays;

    /**
     * Each batch commits on its own. A {@code @Transactional} method called from {@link
     * #pruneOldEntries()} in this same bean would be a self-invocation and never hit the proxy, so
     * the transaction is driven explicitly instead.
     */
    private final TransactionTemplate transactions;

    public CrawlLogRetentionService(
            CrawlLogRepository crawlLogRepository,
            CrawlLogRollupRepository rollupRepository,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${crawler.crawl-log-retention-days:14}") int retentionDays) {
        this.crawlLogRepository = crawlLogRepository;
        this.rollupRepository = rollupRepository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    /** Hourly is plenty: the table only grows a few hundred rows an hour once the backlog is gone. */
    @Scheduled(fixedDelayString = "3600000", initialDelayString = "120000")
    public void pruneOldEntries() {
        if (retentionDays <= 0) {
            return; // retention disabled
        }
        Instant cutoff = Instant.now(clock).minus(Duration.ofDays(retentionDays));
        long totalDeleted = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = pruneBatch(cutoff);
            if (deleted == 0) {
                break;
            }
            totalDeleted += deleted;
        }
        if (totalDeleted > 0) {
            log.info(
                    "Pruned {} crawl_log rows older than {} days (rolled into lifetime totals)",
                    totalDeleted,
                    retentionDays);
        }
    }

    /**
     * Folds one batch of expired rows into the rollup and deletes them, atomically. Returns how many
     * rows went; 0 means there is nothing left past the cutoff.
     */
    int pruneBatch(Instant cutoff) {
        Integer deleted = transactions.execute(status -> pruneBatchInTransaction(cutoff));
        return deleted == null ? 0 : deleted;
    }

    private int pruneBatchInTransaction(Instant cutoff) {
        List<CrawlLog> expired =
                crawlLogRepository.findByTimestampLessThanOrderByIdAsc(cutoff, Limit.of(BATCH_SIZE));
        if (expired.isEmpty()) {
            return 0;
        }

        long success = 0;
        long failed = 0;
        long newListings = 0;
        long statusChanges = 0;
        Instant newest = null;
        long maxId = Long.MIN_VALUE;
        for (CrawlLog row : expired) {
            if (row.isSuccess()) {
                success++;
            } else {
                failed++;
            }
            newListings += row.getNewListings();
            statusChanges += row.getStatusChanged();
            if (row.getTimestamp() != null && (newest == null || row.getTimestamp().isAfter(newest))) {
                newest = row.getTimestamp();
            }
            maxId = Math.max(maxId, row.getId());
        }

        CrawlLogRollup rollup =
                rollupRepository.findById(CrawlLogRollup.SINGLETON_ID).orElseGet(CrawlLogRollup::new);
        rollup.add(success, failed, newListings, statusChanges, newest);
        rollupRepository.save(rollup);

        // Range delete over exactly the rows just folded in — see deleteExpiredUpTo for why this is
        // not deleteAllInBatch(expired).
        return crawlLogRepository.deleteExpiredUpTo(cutoff, maxId);
    }
}

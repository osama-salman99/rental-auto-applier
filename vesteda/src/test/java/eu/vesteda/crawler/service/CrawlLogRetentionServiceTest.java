package eu.vesteda.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;

import eu.vesteda.crawler.model.CrawlLog;
import eu.vesteda.crawler.model.CrawlLogRollup;
import eu.vesteda.crawler.repository.CrawlLogRepository;
import eu.vesteda.crawler.repository.CrawlLogRollupRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The pruner deletes real rows, so this runs against a real database rather than mocks. The property
 * that matters most is the one a user would notice if it broke: the lifetime figures on /stats must
 * never jump backwards just because retention ran.
 */
@DataJpaTest
class CrawlLogRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");
    private static final int RETENTION_DAYS = 14;

    @Autowired private CrawlLogRepository crawlLogs;
    @Autowired private CrawlLogRollupRepository rollups;
    @Autowired private PlatformTransactionManager transactionManager;

    private CrawlLogRetentionService service;

    @BeforeEach
    void setUp() {
        service =
                new CrawlLogRetentionService(
                        crawlLogs,
                        rollups,
                        transactionManager,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        RETENTION_DAYS);
    }

    /** CrawlLog has no timestamp setter (it stamps itself); tests need to place rows in the past. */
    private CrawlLog crawlAt(Instant when, boolean success, int newListings, int statusChanged) {
        CrawlLog row = success ? new CrawlLog(10, 5, newListings, statusChanged) : CrawlLog.failure("boom");
        ReflectionTestUtils.setField(row, "timestamp", when);
        if (!success) {
            ReflectionTestUtils.setField(row, "newListings", newListings);
            ReflectionTestUtils.setField(row, "statusChanged", statusChanged);
        }
        return crawlLogs.save(row);
    }

    private CrawlLogRollup rollup() {
        return rollups.findById(CrawlLogRollup.SINGLETON_ID).orElseGet(CrawlLogRollup::new);
    }

    @Test
    void prunesExpiredRowsAndKeepsLifetimeTotalsExact() {
        Instant old = NOW.minus(Duration.ofDays(30));
        Instant recent = NOW.minus(Duration.ofDays(2));

        crawlAt(old, true, 3, 1);
        crawlAt(old, true, 2, 4);
        crawlAt(old, false, 0, 0);
        crawlAt(recent, true, 7, 5);
        crawlAt(recent, false, 0, 0);

        long lifetimeSuccessBefore = crawlLogs.countBySuccessTrue();
        long lifetimeFailedBefore = crawlLogs.countBySuccessFalse();

        service.pruneOldEntries();

        assertThat(crawlLogs.count()).isEqualTo(2);

        CrawlLogRollup r = rollup();
        assertThat(r.getArchivedSuccess() + crawlLogs.countBySuccessTrue()).isEqualTo(lifetimeSuccessBefore);
        assertThat(r.getArchivedFailed() + crawlLogs.countBySuccessFalse()).isEqualTo(lifetimeFailedBefore);
        assertThat(r.getArchivedNewListings()).isEqualTo(5);
        assertThat(r.getArchivedStatusChanges()).isEqualTo(5);
        assertThat(r.getPrunedThrough()).isEqualTo(old);
    }

    /** A second run must not re-count the rows it already archived — otherwise totals inflate. */
    @Test
    void runningTwiceDoesNotDoubleCount() {
        crawlAt(NOW.minus(Duration.ofDays(30)), true, 5, 2);
        crawlAt(NOW.minus(Duration.ofDays(1)), true, 1, 1);

        service.pruneOldEntries();
        long archivedSuccess = rollup().getArchivedSuccess();
        long archivedNew = rollup().getArchivedNewListings();

        service.pruneOldEntries();

        assertThat(rollup().getArchivedSuccess()).isEqualTo(archivedSuccess);
        assertThat(rollup().getArchivedNewListings()).isEqualTo(archivedNew);
        assertThat(crawlLogs.count()).isEqualTo(1);
    }

    @Test
    void keepsEverythingWhenRetentionIsDisabled() {
        CrawlLogRetentionService disabled =
                new CrawlLogRetentionService(
                        crawlLogs, rollups, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC), 0);
        crawlAt(NOW.minus(Duration.ofDays(365)), true, 1, 1);

        disabled.pruneOldEntries();

        assertThat(crawlLogs.count()).isEqualTo(1);
        assertThat(rollup().getArchivedSuccess()).isZero();
    }

    /** The bulk delete must not build one OR term per row — that overflows the HQL parser's stack. */
    @Test
    void drainsABacklogLargerThanOneBatch() {
        Instant old = NOW.minus(Duration.ofDays(30));
        int rows = 5_200; // > BATCH_SIZE (5_000), so the run must loop
        for (int i = 0; i < rows; i++) {
            crawlAt(old.plusSeconds(i), true, 1, 0);
        }
        crawlAt(NOW.minusSeconds(60), true, 1, 0);

        service.pruneOldEntries();

        assertThat(crawlLogs.count()).isEqualTo(1);
        assertThat(rollup().getArchivedSuccess()).isEqualTo(rows);
    }
}

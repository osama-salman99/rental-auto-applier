package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;

import eu.roofz.crawler.model.CrawlLog;
import eu.roofz.crawler.model.CrawlLogRollup;
import eu.roofz.crawler.repository.CrawlLogRepository;
import eu.roofz.crawler.repository.CrawlLogRollupRepository;
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
        CrawlLog row =
                success
                        ? new CrawlLog(10, 5, newListings, statusChanged)
                        : CrawlLog.failure("boom");
        ReflectionTestUtils.setField(row, "timestamp", when);
        if (!success) {
            ReflectionTestUtils.setField(row, "newListings", newListings);
            ReflectionTestUtils.setField(row, "statusChanged", statusChanged);
        }
        return crawlLogs.save(row);
    }

    private long liveSuccess() {
        return crawlLogs.countBySuccessTrue();
    }

    private CrawlLogRollup rollup() {
        return rollups.findById(CrawlLogRollup.SINGLETON_ID).orElseGet(CrawlLogRollup::new);
    }

    @Test
    void prunesExpiredRowsAndKeepsLifetimeTotalsExact() {
        Instant old = NOW.minus(Duration.ofDays(30)); // past the 14-day window
        Instant recent = NOW.minus(Duration.ofDays(2)); // inside it

        crawlAt(old, true, 3, 1);
        crawlAt(old, true, 2, 4);
        crawlAt(old, false, 0, 0);
        crawlAt(recent, true, 7, 5);
        crawlAt(recent, false, 0, 0);

        // Lifetime figures as /stats would report them before retention runs.
        long lifetimeSuccessBefore = crawlLogs.countBySuccessTrue(); // 3
        long lifetimeFailedBefore = crawlLogs.countBySuccessFalse(); // 2
        long lifetimeNewBefore = crawlLogs.totalNewListingsDiscovered(); // 12
        long lifetimeStatusBefore = crawlLogs.totalStatusChanges(); // 10

        service.pruneOldEntries();

        // The three expired rows are gone; the two recent ones remain.
        assertThat(crawlLogs.count()).isEqualTo(2);
        assertThat(liveSuccess()).isEqualTo(1);

        // And the lifetime figures are unchanged: archived + still-live == what it was before.
        CrawlLogRollup r = rollup();
        assertThat(r.getArchivedSuccess() + crawlLogs.countBySuccessTrue()).isEqualTo(lifetimeSuccessBefore);
        assertThat(r.getArchivedFailed() + crawlLogs.countBySuccessFalse()).isEqualTo(lifetimeFailedBefore);
        assertThat(r.getArchivedNewListings() + crawlLogs.totalNewListingsDiscovered())
                .isEqualTo(lifetimeNewBefore);
        assertThat(r.getArchivedStatusChanges() + crawlLogs.totalStatusChanges())
                .isEqualTo(lifetimeStatusBefore);

        assertThat(r.getPrunedThrough()).isEqualTo(old);
    }

    /** A second run must not re-count the rows it already archived — otherwise totals inflate. */
    @Test
    void runningTwiceDoesNotDoubleCount() {
        crawlAt(NOW.minus(Duration.ofDays(30)), true, 5, 2);
        crawlAt(NOW.minus(Duration.ofDays(1)), true, 1, 1);

        service.pruneOldEntries();
        CrawlLogRollup afterFirst = rollup();
        long archivedSuccess = afterFirst.getArchivedSuccess();
        long archivedNew = afterFirst.getArchivedNewListings();

        service.pruneOldEntries();

        CrawlLogRollup afterSecond = rollup();
        assertThat(afterSecond.getArchivedSuccess()).isEqualTo(archivedSuccess);
        assertThat(afterSecond.getArchivedNewListings()).isEqualTo(archivedNew);
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

    @Test
    void drainsABacklogLargerThanOneBatch() {
        Instant old = NOW.minus(Duration.ofDays(30));
        int rows = 5_200; // > BATCH_SIZE (5_000), so the run must loop
        for (int i = 0; i < rows; i++) {
            crawlAt(old.plusSeconds(i), true, 1, 0);
        }
        crawlAt(NOW.minusSeconds(60), true, 1, 0);

        service.pruneOldEntries();

        assertThat(crawlLogs.count()).isEqualTo(1); // only the recent row survives
        assertThat(rollup().getArchivedSuccess()).isEqualTo(rows);
        assertThat(rollup().getArchivedNewListings()).isEqualTo(rows);
    }
}

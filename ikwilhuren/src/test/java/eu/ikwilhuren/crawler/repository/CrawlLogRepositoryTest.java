package eu.ikwilhuren.crawler.repository;

import static org.assertj.core.api.Assertions.assertThat;

import eu.ikwilhuren.crawler.model.CrawlLog;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/**
 * The stats page reads the crawl log in one pass now, folding "all time" and "last 24 hours" into a
 * single query full of CASE branches. That is a lot more arithmetic to get subtly wrong than seven
 * plain queries were, so it is worth pinning: an old row must count towards the totals and not
 * towards the day, and a failed crawl must not be counted as a successful one.
 */
@DataJpaTest
class CrawlLogRepositoryTest {

    @Autowired private CrawlLogRepository repository;
    @Autowired private EntityManager em;

    @Test
    void gathersTotalsAndTheLastDayInOnePass() {
        // Two crawls just now: 3 new homes and 1 status change, then 2 new and 4 changes.
        repository.save(new CrawlLog(100, 10, 3, 1));
        repository.save(new CrawlLog(100, 10, 2, 4));
        // One that failed just now.
        repository.save(CrawlLog.failure("boom"));
        // And one that succeeded three days ago, carrying 5 new homes and 2 changes.
        CrawlLog old = repository.save(new CrawlLog(100, 10, 5, 2));
        backdate(old, Instant.now().minus(3, ChronoUnit.DAYS));

        CrawlStats stats = repository.stats(Instant.now().minus(24, ChronoUnit.HOURS));

        assertThat(stats.successful()).isEqualTo(3); // the two recent + the old one
        assertThat(stats.failed()).isEqualTo(1);
        assertThat(stats.crawlsLast24h()).isEqualTo(3); // the old one falls outside the window

        assertThat(stats.newListings()).isEqualTo(10); // 3 + 2 + 5
        assertThat(stats.statusChanges()).isEqualTo(7); // 1 + 4 + 2

        assertThat(stats.newListingsLast24h()).isEqualTo(5); // 3 + 2, not the old 5
        assertThat(stats.statusChangesLast24h()).isEqualTo(5); // 1 + 4, not the old 2
    }

    /** An empty log must read as zeroes, not as nulls — SUM over no rows is null. */
    @Test
    void anEmptyLogReadsAsZeroes() {
        CrawlStats stats = repository.stats(Instant.now().minus(24, ChronoUnit.HOURS));

        assertThat(stats.successful()).isZero();
        assertThat(stats.failed()).isZero();
        assertThat(stats.crawlsLast24h()).isZero();
        assertThat(stats.newListings()).isZero();
        assertThat(stats.statusChanges()).isZero();
        assertThat(stats.newListingsLast24h()).isZero();
        assertThat(stats.statusChangesLast24h()).isZero();
    }

    /** The entity deliberately has no timestamp setter; the crawler always stamps "now". */
    private void backdate(CrawlLog log, Instant to) {
        em.createNativeQuery("UPDATE crawl_log SET timestamp = ?1 WHERE id = ?2")
                .setParameter(1, to)
                .setParameter(2, log.getId())
                .executeUpdate();
        em.clear();
    }
}

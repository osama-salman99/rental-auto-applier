package eu.ikwilhuren.crawler.repository;

import eu.ikwilhuren.crawler.model.CrawlLog;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CrawlLogRepository extends JpaRepository<CrawlLog, Long> {

    /** Cheap only because crawl_log.timestamp is indexed — without it this sorts the whole table. */
    List<CrawlLog> findTop10ByOrderByTimestampDesc();

    /**
     * Totals and last-24h figures together, in one scan. Every branch the stats page needs is folded
     * into a CASE here rather than asked for in a query of its own.
     */
    @Query(
            """
            SELECT new eu.ikwilhuren.crawler.repository.CrawlStats(
                COUNT(CASE WHEN c.success = TRUE THEN 1 END),
                COUNT(CASE WHEN c.success = FALSE THEN 1 END),
                COUNT(CASE WHEN c.timestamp > :since THEN 1 END),
                COALESCE(SUM(c.newListings), 0L),
                COALESCE(SUM(c.statusChanged), 0L),
                COALESCE(SUM(CASE WHEN c.timestamp > :since THEN c.newListings ELSE 0 END), 0L),
                COALESCE(SUM(CASE WHEN c.timestamp > :since THEN c.statusChanged ELSE 0 END), 0L))
            FROM CrawlLog c
            """)
    CrawlStats stats(@Param("since") Instant since);

    /**
     * The oldest rows past the retention cutoff, capped so the pruner can work in bounded batches
     * rather than materialising the whole backlog in one transaction. Ordered by id (not timestamp)
     * so the batch is exactly the id-range {@link #deleteExpiredUpTo} then removes.
     */
    List<CrawlLog> findByTimestampLessThanOrderByIdAsc(Instant cutoff, Limit limit);

    /**
     * Deletes one batch as a single range delete.
     *
     * <p>Not {@code deleteAllInBatch(entities)}: that builds one {@code OR} term per entity, so a
     * 5,000-row batch becomes a 5,000-term boolean expression that overflows the HQL parser's stack.
     * Because the batch is the {@code n} lowest ids past the cutoff, {@code timestamp < cutoff AND id
     * <= maxId} matches precisely those rows — the ones already folded into the rollup, no more.
     *
     * <p>{@code flushAutomatically} matters: the caller has just updated the rollup, and a bulk delete
     * touches a different table, so Hibernate would not auto-flush it — {@code clearAutomatically}
     * would then discard that pending update and the archived totals would silently vanish.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM CrawlLog c WHERE c.timestamp < :cutoff AND c.id <= :maxId")
    int deleteExpiredUpTo(@Param("cutoff") Instant cutoff, @Param("maxId") long maxId);
}

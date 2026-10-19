package eu.nederwoon.crawler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Running totals for the {@link CrawlLog} rows that retention has already deleted. The stats page
 * shows lifetime figures, but keeping every row forever is what makes the table unbounded. So the
 * pruner folds each batch it deletes into this single row (id = 1) and the lifetime figure becomes
 * {@code archived + what is still in the table} — the number keeps climbing and never resets, while
 * the table stays small.
 */
@Entity
@Table(name = "crawl_log_rollup")
public class CrawlLogRollup {

    public static final long SINGLETON_ID = 1L;

    @Id private Long id = SINGLETON_ID;

    private long archivedSuccess;
    private long archivedFailed;
    private long archivedNewListings;
    private long archivedStatusChanges;

    /** Newest timestamp folded in so far — i.e. the table holds nothing older than this. */
    private Instant prunedThrough;

    private Instant updatedAt;

    public CrawlLogRollup() {}

    /** Folds one deleted batch into the totals. */
    public void add(long success, long failed, long newListings, long statusChanges, Instant newest) {
        this.archivedSuccess += success;
        this.archivedFailed += failed;
        this.archivedNewListings += newListings;
        this.archivedStatusChanges += statusChanges;
        if (newest != null && (prunedThrough == null || newest.isAfter(prunedThrough))) {
            this.prunedThrough = newest;
        }
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getArchivedSuccess() {
        return archivedSuccess;
    }

    public long getArchivedFailed() {
        return archivedFailed;
    }

    public long getArchivedNewListings() {
        return archivedNewListings;
    }

    public long getArchivedStatusChanges() {
        return archivedStatusChanges;
    }

    public Instant getPrunedThrough() {
        return prunedThrough;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

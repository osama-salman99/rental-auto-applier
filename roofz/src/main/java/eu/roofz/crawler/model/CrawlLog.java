package eu.roofz.crawler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One row per crawl pass — written every crawl interval (~10s), so this table is by far the largest
 * in the DB. The stats aggregates all filter on {@code timestamp} or {@code success}; without these
 * indexes every one of them is a full scan of a table that grows ~8.6k rows a day.
 *
 * <p>{@link eu.roofz.crawler.service.CrawlLogRetentionService} prunes rows past the retention window
 * into {@link CrawlLogRollup}, so the table stays bounded while lifetime totals stay exact.
 */
@Entity
@Table(
        name = "crawl_log",
        indexes = {
            @Index(name = "idx_crawl_log_timestamp", columnList = "timestamp"),
            @Index(name = "idx_crawl_log_success", columnList = "success")
        })
public class CrawlLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant timestamp;
    private int totalListings;
    private int matchingListings;
    private int newListings;
    private int statusChanged;
    private boolean success;
    private String errorMessage;

    public CrawlLog() {}

    public CrawlLog(int totalListings, int matchingListings, int newListings, int statusChanged) {
        this.timestamp = Instant.now();
        this.totalListings = totalListings;
        this.matchingListings = matchingListings;
        this.newListings = newListings;
        this.statusChanged = statusChanged;
        this.success = true;
    }

    public static CrawlLog failure(String errorMessage) {
        CrawlLog log = new CrawlLog();
        log.timestamp = Instant.now();
        log.success = false;
        log.errorMessage = errorMessage;
        return log;
    }

    public Long getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public int getTotalListings() { return totalListings; }
    public int getMatchingListings() { return matchingListings; }
    public int getNewListings() { return newListings; }
    public int getStatusChanged() { return statusChanged; }
    public boolean isSuccess() { return success; }
    public String getErrorMessage() { return errorMessage; }
}

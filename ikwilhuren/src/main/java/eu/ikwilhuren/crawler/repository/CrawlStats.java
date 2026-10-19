package eu.ikwilhuren.crawler.repository;

/**
 * Every crawl-log number the stats page shows, gathered in a single pass over the table.
 *
 * <p>These used to be seven queries. That was fine when the log held a few hundred rows; it holds
 * over 150,000 now, one per crawl since May, and none of them could be answered without reading all
 * of it — so the page paid for seven full scans and took the better part of a second.
 */
public record CrawlStats(
        long successful,
        long failed,
        long crawlsLast24h,
        long newListings,
        long statusChanges,
        long newListingsLast24h,
        long statusChangesLast24h) {}

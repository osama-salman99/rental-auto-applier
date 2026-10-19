package eu.ikwilhuren.crawler.scheduler;

import eu.osmosis.crawler.core.http.CurlFetchException;

import eu.ikwilhuren.crawler.model.CrawlLog;
import eu.ikwilhuren.crawler.repository.CrawlLogRepository;
import eu.ikwilhuren.crawler.service.CrawlerFetchException;
import eu.ikwilhuren.crawler.service.AutoApplyService;
import eu.ikwilhuren.crawler.service.CrawlerService;
import eu.ikwilhuren.crawler.service.FailureNotifier;
import eu.ikwilhuren.crawler.service.IkwilhurenScraper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Off in tests: a Spring context test would otherwise start crawling ikwilhuren.nu for real.
@ConditionalOnProperty(name = "crawler.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class CrawlerScheduler {

    private static final Logger log = LoggerFactory.getLogger(CrawlerScheduler.class);
    private static final int QUICK_PAGES = 2;

    // MVGM explicitly bans scrapers and blocks offending IPs (and can close the account), so we keep
    // our footprint modest. A newly listed home has a 48-hour (working-day) Plus-only window and the
    // draw runs up to 2 working days out, so catching it within a few minutes costs nothing strategic
    // — whereas polling every 30s from one IP round the clock is a textbook scrape signature.
    private static final long QUICK_POLL_INTERVAL_MS = 240_000; // 4 minutes

    private final CrawlerService crawlerService;
    private final AutoApplyService autoApplyService;
    private final CrawlLogRepository crawlLogRepository;
    private final FailureNotifier failureNotifier;

    public CrawlerScheduler(
            CrawlerService crawlerService,
            AutoApplyService autoApplyService,
            CrawlLogRepository crawlLogRepository,
            FailureNotifier failureNotifier) {
        this.crawlerService = crawlerService;
        this.autoApplyService = autoApplyService;
        this.crawlLogRepository = crawlLogRepository;
        this.failureNotifier = failureNotifier;
    }

    // fixedDelay, not fixedRate: measure the gap from when a pass FINISHES, so a slow crawl (or the
    // auto-apply pass it kicks off) can never queue the next one back-to-back and hammer MVGM.
    @Scheduled(fixedDelay = QUICK_POLL_INTERVAL_MS)
    public void quickPoll() {
        runCrawl(QUICK_PAGES);
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void deepPoll() {
        runCrawl(IkwilhurenScraper.DEFAULT_MAX_PAGES);
    }

    private void runCrawl(int maxPages) {
        try {
            CrawlerService.CrawlResult result = crawlerService.crawl(maxPages);
            failureNotifier.recordSuccess();

            // Reconcile the moment the offering actually changes — a home appearing or being taken
            // is exactly what makes a different five the best five. Only on a change, though: an
            // unchanged crawl teaches us nothing, so it just waits for the next timer tick rather
            // than kicking off a needless auto-apply pass.
            if (result.newCount() > 0 || result.statusChanged() > 0) {
                log.info(
                        "Offering changed ({} new, {} status changes) — recomputing the best five",
                        result.newCount(),
                        result.statusChanged());
                try {
                    autoApplyService.tick();
                } catch (Exception e) {
                    log.error("Auto-apply pass after crawl failed", e);
                }
            }
        } catch (Exception e) {
            String reason = friendlyReason(e);
            log.error("Crawl failed ({} pages): {}", maxPages, reason, e);
            try {
                crawlLogRepository.save(CrawlLog.failure(reason));
            } catch (Exception saveError) {
                log.error("Also failed to save failure log", saveError);
            }
            try {
                failureNotifier.recordFailure(reason);
            } catch (Exception notifyError) {
                log.error("Failed to dispatch failure notification", notifyError);
            }
        }
    }

    public static String friendlyReason(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof CrawlerFetchException || t instanceof CurlFetchException) {
                return "Could not reach ikwilhuren.nu";
            }
            if (t instanceof DataAccessException) {
                return "Database error";
            }
        }
        return "Unexpected error";
    }

}

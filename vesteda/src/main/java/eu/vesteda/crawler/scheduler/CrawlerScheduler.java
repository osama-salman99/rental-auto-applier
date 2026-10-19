package eu.vesteda.crawler.scheduler;

import eu.vesteda.crawler.model.CrawlLog;
import eu.vesteda.crawler.repository.CrawlLogRepository;
import eu.vesteda.crawler.service.CrawlerFetchException;
import eu.vesteda.crawler.service.CrawlerService;
import eu.vesteda.crawler.service.FailureNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Gated behind a property so a Spring context test can never crawl the live site for real. */
@Component
@ConditionalOnProperty(name = "vesteda.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class CrawlerScheduler {

    private static final Logger log = LoggerFactory.getLogger(CrawlerScheduler.class);

    private final CrawlerService crawlerService;
    private final CrawlLogRepository crawlLogRepository;
    private final FailureNotifier failureNotifier;

    public CrawlerScheduler(
            CrawlerService crawlerService,
            CrawlLogRepository crawlLogRepository,
            FailureNotifier failureNotifier) {
        this.crawlerService = crawlerService;
        this.crawlLogRepository = crawlLogRepository;
        this.failureNotifier = failureNotifier;
    }

    // fixedDelay, not fixedRate: measure the gap from when a pass finishes, so a slow crawl (or the
    // auto-apply pass it triggers) can never queue the next one back-to-back.
    @Scheduled(fixedDelay = 60_000)
    public void poll() {
        runCrawl();
    }

    private void runCrawl() {
        try {
            crawlerService.crawl();
            failureNotifier.recordSuccess();
        } catch (Exception e) {
            String reason = friendlyReason(e);
            log.error("Crawl failed: {}", reason, e);
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
            if (t instanceof CrawlerFetchException) {
                return "Could not reach vesteda.com";
            }
            if (t instanceof DataAccessException) {
                return "Database error";
            }
        }
        return "Unexpected error";
    }
}

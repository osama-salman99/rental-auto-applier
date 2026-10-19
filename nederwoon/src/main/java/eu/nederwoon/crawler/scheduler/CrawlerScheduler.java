package eu.nederwoon.crawler.scheduler;

import eu.nederwoon.crawler.model.CrawlLog;
import eu.nederwoon.crawler.repository.CrawlLogRepository;
import eu.nederwoon.crawler.service.CrawlerFetchException;
import eu.nederwoon.crawler.service.CrawlerService;
import eu.nederwoon.crawler.service.FailureNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
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
                return "Could not reach nederwoon.nl";
            }
            if (t instanceof DataAccessException) {
                return "Database error";
            }
        }
        return "Unexpected error";
    }
}

package eu.ikwilhuren.crawler.scheduler;

import eu.ikwilhuren.crawler.service.AutoApplyService;
import eu.ikwilhuren.crawler.service.PortalSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the viewing-request allocator.
 *
 * <p>The two-minute timer is only the backstop. What actually matters is that the crawler reconciles
 * the moment the offering changes — a home appearing is exactly the event worth reacting to, because
 * MVGM caps viewing requests per home and a home fills up. So {@code CrawlerScheduler} calls the
 * allocator the instant a crawl finds something new, and this timer merely catches whatever that
 * misses.
 */
// Off in tests: a Spring context test would otherwise start crawling ikwilhuren.nu for real.
@ConditionalOnProperty(name = "crawler.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class AutoApplyScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoApplyScheduler.class);

    private final AutoApplyService autoApplyService;
    private final PortalSession session;

    public AutoApplyScheduler(AutoApplyService autoApplyService, PortalSession session) {
        this.autoApplyService = autoApplyService;
        this.session = session;
    }

    @Scheduled(fixedDelay = 120_000, initialDelay = 45_000)
    public void allocate() {
        try {
            autoApplyService.tick();
        } catch (Exception e) {
            log.error("Auto-apply pass failed", e);
        }
    }

    /** Keeps the portal session warm so an apply doesn't have to start by logging in. */
    @Scheduled(fixedDelay = 600_000, initialDelay = 300_000)
    public void keepSessionAlive() {
        try {
            session.keepAlive();
        } catch (Exception e) {
            log.debug("Session keepalive failed: {}", e.getMessage());
        }
    }
}

package eu.rebo.crawler.scheduler;

import eu.rebo.crawler.service.AutoApplyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs an auto-apply pass shortly after each crawl. Gated by a property (default on) so a Spring
 * context test cannot register interest against the live portal for real.
 */
@Component
@ConditionalOnProperty(name = "crawler.autoapply.scheduler.enabled", matchIfMissing = true)
public class AutoApplyScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoApplyScheduler.class);

    private final AutoApplyService autoApplyService;

    public AutoApplyScheduler(AutoApplyService autoApplyService) {
        this.autoApplyService = autoApplyService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void poll() {
        try {
            autoApplyService.runOnce();
        } catch (Exception e) {
            // An auto-apply failure must never take down the crawl loop.
            log.error("Auto-apply pass failed", e);
        }
    }
}

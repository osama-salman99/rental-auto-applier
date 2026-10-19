package eu.vesteda.crawler.scheduler;

import eu.vesteda.crawler.service.UnitDetailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives detail-page enrichment on its own slow timer, decoupled from the once-a-minute search crawl.
 * Gated behind the same property as the crawler so a Spring context test never reaches the live site.
 */
@Component
@ConditionalOnProperty(name = "vesteda.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class UnitDetailScheduler {

    private static final Logger log = LoggerFactory.getLogger(UnitDetailScheduler.class);

    private final UnitDetailService unitDetailService;

    public UnitDetailScheduler(UnitDetailService unitDetailService) {
        this.unitDetailService = unitDetailService;
    }

    // Give the first search crawl time to land some listings, then top up every ten minutes.
    @Scheduled(fixedDelay = 600_000, initialDelay = 90_000)
    public void enrich() {
        try {
            unitDetailService.enrichPending();
        } catch (Exception e) {
            log.warn("Detail enrichment pass failed: {}", e.getMessage());
        }
    }
}

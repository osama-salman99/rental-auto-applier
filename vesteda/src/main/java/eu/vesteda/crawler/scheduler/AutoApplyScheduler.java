package eu.vesteda.crawler.scheduler;

import eu.vesteda.crawler.model.CrawlerConfig;
import eu.vesteda.crawler.repository.CrawlerConfigRepository;
import eu.vesteda.crawler.service.AutoApplyService;
import eu.vesteda.crawler.service.PortalListingService;
import eu.vesteda.crawler.service.PortalSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the apply loop.
 *
 * <p>The poll is deliberately tight. Saturation on this portal cannot be seen until after you have
 * already spent a slot on a home, so the only durable edge is arriving before the home fills up —
 * which makes the gap between a home appearing and us seeing it the single most valuable number in
 * the system. The whole catalogue comes down on one page and gzips to ~110 KB, so a 45-second poll
 * is affordable, and there is no pagination or sort order that could hide a new home from it.
 *
 * <p>Gated behind a property so a Spring context test can never crawl — or apply to — the live site.
 */
@Component
@ConditionalOnProperty(name = "vesteda.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class AutoApplyScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoApplyScheduler.class);

    private final PortalSession session;
    private final PortalListingService portalListings;
    private final AutoApplyService autoApply;
    private final CrawlerConfigRepository configRepository;

    public AutoApplyScheduler(
            PortalSession session,
            PortalListingService portalListings,
            AutoApplyService autoApply,
            CrawlerConfigRepository configRepository) {
        this.session = session;
        this.portalListings = portalListings;
        this.autoApply = autoApply;
        this.configRepository = configRepository;
    }

    /**
     * Watch the front of the queue, and re-plan the moment anything moves. The timer below is only a
     * backstop; this is what makes the system fast enough to matter.
     */
    @Scheduled(fixedDelay = 45_000, initialDelay = 20_000)
    public void pollPortal() {
        if (!armed()) {
            return;
        }
        try {
            if (portalListings.refresh()) {
                autoApply.tick();
            }
        } catch (Exception e) {
            log.error("Portal poll failed: {}", e.getMessage());
        }
    }

    /** Backstop, in case nothing on the portal changed but our own clock did (a home ages out). */
    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void reconcile() {
        if (!armed()) {
            return;
        }
        try {
            autoApply.tick();
        } catch (Exception e) {
            log.error("Auto-apply pass failed: {}", e.getMessage());
        }
    }

    /** The portal session lasts two hours; keep it warm so no apply pays for a re-login. */
    @Scheduled(fixedDelay = 1_800_000, initialDelay = 1_800_000)
    public void keepAlive() {
        if (!armed()) {
            return;
        }
        session.keepAlive();
    }

    private boolean armed() {
        CrawlerConfig config = configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
        return config.isAutoApplyEnabled() && session.isConfigured();
    }
}

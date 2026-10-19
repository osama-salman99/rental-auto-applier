package eu.roofz.crawler.scheduler;

import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import eu.roofz.crawler.service.InviteWatchdog;
import eu.roofz.crawler.service.PortalPipelineService;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Runs the ReletPortal application pipeline on the same dynamic interval as the crawler: each tick
 * advances every tracked application and runs the stage watchdogs. Invitations arrive via the email
 * ingest endpoint (Cloudflare forwards them), not by polling. Interval is read from
 * {@link CrawlerConfig#getPollIntervalSeconds()} (default 10s), so a config change takes effect
 * without a restart. Each stage is guarded so one failing stage never blocks the others.
 */
@Component
public class PortalScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(PortalScheduler.class);

    private static final int DEFAULT_INTERVAL_SECONDS = 10;
    private static final int MIN_INTERVAL_SECONDS = 1;

    private final PortalPipelineService pipelineService;
    private final InviteWatchdog inviteWatchdog;
    private final CrawlerConfigRepository configRepository;

    public PortalScheduler(
            PortalPipelineService pipelineService,
            InviteWatchdog inviteWatchdog,
            CrawlerConfigRepository configRepository) {
        this.pipelineService = pipelineService;
        this.inviteWatchdog = inviteWatchdog;
        this.configRepository = configRepository;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
                this::tick,
                triggerContext -> {
                    long seconds = readIntervalSeconds();
                    Instant base = triggerContext.lastCompletion();
                    if (base == null) {
                        base = Instant.now();
                    }
                    return base.plus(Duration.ofSeconds(seconds));
                });
    }

    private long readIntervalSeconds() {
        try {
            return configRepository
                    .findById(1L)
                    .map(CrawlerConfig::getPollIntervalSeconds)
                    .filter(s -> s != null && s >= MIN_INTERVAL_SECONDS)
                    .orElse(DEFAULT_INTERVAL_SECONDS);
        } catch (Exception e) {
            return DEFAULT_INTERVAL_SECONDS;
        }
    }

    private void tick() {
        runGuarded("pipeline", pipelineService::pollAndAdvance);
        runGuarded("watchdog", inviteWatchdog::check);
    }

    private void runGuarded(String stage, Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.error("Portal scheduler stage '{}' failed", stage, e);
        }
    }
}

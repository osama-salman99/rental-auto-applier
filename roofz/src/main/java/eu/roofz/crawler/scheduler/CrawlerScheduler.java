package eu.roofz.crawler.scheduler;

import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import eu.roofz.crawler.service.CrawlFailureNotifier;
import eu.roofz.crawler.service.CrawlerService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

@Component
public class CrawlerScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CrawlerScheduler.class);

    private static final int DEFAULT_INTERVAL_SECONDS = 10;
    private static final int MIN_INTERVAL_SECONDS = 1;

    private final CrawlerService crawlerService;
    private final CrawlerConfigRepository configRepository;
    private final CrawlFailureNotifier crawlFailureNotifier;
    private final Clock clock;

    public CrawlerScheduler(
            CrawlerService crawlerService,
            CrawlerConfigRepository configRepository,
            CrawlFailureNotifier crawlFailureNotifier,
            Clock clock) {
        this.crawlerService = crawlerService;
        this.configRepository = configRepository;
        this.crawlFailureNotifier = crawlFailureNotifier;
        this.clock = clock;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
                this::poll,
                triggerContext -> {
                    long seconds = readIntervalSeconds();

                    // Measure the interval from when the pass STARTED, not when it finished.
                    // lastCompletion() makes the real period (pass duration + interval), so anything
                    // that slows a pass silently stretches the cadence — a 10s setting was observed
                    // polling every 30s. Being first to a listing is this crawler's whole point, so
                    // 10s has to mean 10s.
                    Instant base = triggerContext.lastActualExecution();
                    if (base == null) {
                        base = clock.instant();
                    }
                    Instant next = base.plus(Duration.ofSeconds(seconds));

                    // A pass that overran its own interval must run again now, not at a time in the
                    // past (which would make the trigger fire in a tight loop trying to catch up).
                    Instant now = clock.instant();
                    return next.isBefore(now) ? now : next;
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
            log.warn("Failed to read poll interval from config, defaulting to {}s", DEFAULT_INTERVAL_SECONDS, e);
            return DEFAULT_INTERVAL_SECONDS;
        }
    }

    private void poll() {
        try {
            crawlerService.crawl();
            crawlFailureNotifier.onSuccess();
        } catch (Exception e) {
            log.error("Crawl failed", e);
            crawlFailureNotifier.onFailure(e);
        }
    }
}

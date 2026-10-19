package eu.vesteda.crawler.service;

import eu.vesteda.crawler.model.CrawlerConfig;
import eu.vesteda.crawler.repository.CrawlerConfigRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Push notifications on crawl failures, suppressed until 5 consecutive failures occur. After the
 * threshold is reached, exponential backoff applies: 1 minute before the next, then 2, 4, 8, 16,
 * 32, and 60-minute intervals. Sends a one-shot "recovered" notification on the next success (only
 * if the threshold was previously reached).
 */
@Component
public class FailureNotifier {

    private static final Logger log = LoggerFactory.getLogger(FailureNotifier.class);
    private static final long MAX_BACKOFF_MINUTES = 60;
    private static final int MIN_FAILURES_TO_NOTIFY = 5;

    private final NotificationService notificationService;
    private final CrawlerConfigRepository configRepository;
    private final Clock clock;

    private int consecutiveFailures = 0;
    private int notificationsSent = 0;
    private Instant lastNotifiedAt;

    public FailureNotifier(
            NotificationService notificationService,
            CrawlerConfigRepository configRepository,
            Clock clock) {
        this.notificationService = notificationService;
        this.configRepository = configRepository;
        this.clock = clock;
    }

    public synchronized void recordFailure(String errorMessage) {
        consecutiveFailures++;
        Instant now = clock.instant();

        if (!shouldNotifyNow(now)) {
            log.debug("Suppressing failure notification (#{} this streak; in backoff window)",
                    consecutiveFailures);
            return;
        }

        CrawlerConfig config = loadConfig();
        if (config == null || !config.isNotificationsEnabled()) return;

        String title = consecutiveFailures == 1
                ? "[Vesteda] Crawl failing"
                : "[Vesteda] Crawl failing (×" + consecutiveFailures + ")";
        String body = errorMessage != null ? errorMessage : "(no error message)";
        notificationService.sendCrawlerStatusNotification(title, body, "warning", config);
        notificationsSent++;
        lastNotifiedAt = now;
    }

    public synchronized void recordSuccess() {
        if (consecutiveFailures == 0) return;

        int recoveredFrom = consecutiveFailures;
        boolean hadNotified = notificationsSent > 0;
        consecutiveFailures = 0;
        notificationsSent = 0;
        lastNotifiedAt = null;

        if (!hadNotified) return;

        CrawlerConfig config = loadConfig();
        if (config == null || !config.isNotificationsEnabled()) return;

        notificationService.sendCrawlerStatusNotification(
                "[Vesteda] Crawl recovered",
                "Back to normal after " + recoveredFrom + " consecutive failure"
                        + (recoveredFrom == 1 ? "" : "s"),
                "white_check_mark",
                config);
    }

    private boolean shouldNotifyNow(Instant now) {
        if (consecutiveFailures < MIN_FAILURES_TO_NOTIFY) return false;
        if (lastNotifiedAt == null) return true;
        long waitMinutes = backoffMinutes(notificationsSent);
        return Duration.between(lastNotifiedAt, now).toMinutes() >= waitMinutes;
    }

    /**
     * Doubles each time, capped at {@value #MAX_BACKOFF_MINUTES} minutes.
     */
    private static long backoffMinutes(int notificationsAlreadySent) {
        if (notificationsAlreadySent < 1) return 0;
        int exponent = notificationsAlreadySent - 1;
        if (exponent >= 6) return MAX_BACKOFF_MINUTES;
        return Math.min(1L << exponent, MAX_BACKOFF_MINUTES);
    }

    private CrawlerConfig loadConfig() {
        try {
            return configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
        } catch (Exception e) {
            log.warn("Could not load config for failure-notifier: {}", e.getMessage());
            return null;
        }
    }
}

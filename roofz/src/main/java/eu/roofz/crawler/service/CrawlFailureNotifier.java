package eu.roofz.crawler.service;

import eu.roofz.crawler.model.CrawlLog;
import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.repository.CrawlLogRepository;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Tracks consecutive crawl failures and suppresses notifications until 5 consecutive failures
 * occur, then applies exponential backoff between subsequent notifications.
 *
 * <p>Notifications start at failure 5, then with 1-minute, 2, 4, 8, 16, 32, and 60-minute
 * intervals. Recovery (the first successful crawl after a notified streak) sends one notification
 * and resets state. Every failure is still persisted as a {@link CrawlLog} row regardless of the
 * rate-limit so the historical record is complete.
 */
@Service
public class CrawlFailureNotifier {

    private static final Logger log = LoggerFactory.getLogger(CrawlFailureNotifier.class);
    private static final long MAX_BACKOFF_MINUTES = 60;
    private static final int MIN_FAILURES_TO_NOTIFY = 5;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 200;

    private final NotificationService notificationService;
    private final CrawlerConfigRepository configRepository;
    private final CrawlLogRepository crawlLogRepository;
    private final Clock clock;

    private int consecutiveFailures = 0;
    private int notificationsSent = 0;
    private Instant lastNotifiedAt;

    public CrawlFailureNotifier(
            NotificationService notificationService,
            CrawlerConfigRepository configRepository,
            CrawlLogRepository crawlLogRepository,
            Clock clock) {
        this.notificationService = notificationService;
        this.configRepository = configRepository;
        this.crawlLogRepository = crawlLogRepository;
        this.clock = clock;
    }

    public synchronized void onFailure(Throwable failure) {
        consecutiveFailures++;
        Instant now = clock.instant();
        String message = truncate(messageOf(failure));
        crawlLogRepository.save(CrawlLog.failure(message));

        if (!shouldNotifyNow(now)) {
            log.debug("Suppressing failure notification (#{} this streak; in backoff window)",
                    consecutiveFailures);
            return;
        }

        CrawlerConfig config = configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
        if (config.isNotificationsEnabled()) {
            notificationService.sendCrawlFailureNotification(consecutiveFailures, message, config);
        }
        notificationsSent++;
        lastNotifiedAt = now;
    }

    public synchronized void onSuccess() {
        if (consecutiveFailures == 0) return;
        int recoveredFrom = consecutiveFailures;
        boolean hadNotified = notificationsSent > 0;
        consecutiveFailures = 0;
        notificationsSent = 0;
        lastNotifiedAt = null;
        if (!hadNotified) return;
        CrawlerConfig config = configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
        if (config.isNotificationsEnabled()) {
            notificationService.sendCrawlRecoveredNotification(recoveredFrom, config);
        }
    }

    private boolean shouldNotifyNow(Instant now) {
        if (consecutiveFailures < MIN_FAILURES_TO_NOTIFY) return false;
        if (lastNotifiedAt == null) return true;
        long waitMinutes = backoffMinutes(notificationsSent);
        return Duration.between(lastNotifiedAt, now).toMinutes() >= waitMinutes;
    }

    /**
     * Backoff between the next notification and the one most recently sent, given how many
     * notifications have already gone out in this streak. Doubles each time, capped at
     * {@value #MAX_BACKOFF_MINUTES} minutes.
     */
    private static long backoffMinutes(int notificationsAlreadySent) {
        if (notificationsAlreadySent < 1) return 0;
        int exponent = notificationsAlreadySent - 1;
        if (exponent >= 6) return MAX_BACKOFF_MINUTES;
        return Math.min(1L << exponent, MAX_BACKOFF_MINUTES);
    }

    private static String messageOf(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > MAX_ERROR_MESSAGE_LENGTH
                ? s.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "…"
                : s;
    }
}

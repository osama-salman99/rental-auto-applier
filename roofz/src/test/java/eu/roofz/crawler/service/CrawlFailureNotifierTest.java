package eu.roofz.crawler.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import eu.roofz.crawler.model.CrawlLog;
import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.repository.CrawlLogRepository;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CrawlFailureNotifierTest {

    private static final Instant T0 = Instant.parse("2026-05-26T10:00:00Z");

    private final NotificationService notif = mock(NotificationService.class);
    private final CrawlerConfigRepository configs = mock(CrawlerConfigRepository.class);
    private final CrawlLogRepository crawlLogs = mock(CrawlLogRepository.class);
    private final MutableClock clock = new MutableClock(T0);
    private CrawlFailureNotifier notifier;

    @BeforeEach
    void setup() {
        notifier = new CrawlFailureNotifier(notif, configs, crawlLogs, clock);
        when(configs.findById(1L)).thenReturn(Optional.of(enabledConfig()));
    }

    @Test
    void suppressesNotificationsBeforeThreshold() {
        for (int i = 0; i < 4; i++) {
            notifier.onFailure(new RuntimeException("boom"));
        }
        verify(notif, never()).sendCrawlFailureNotification(anyInt(), anyString(), any());
    }

    @Test
    void notifiesOnFifthConsecutiveFailure() {
        for (int i = 0; i < 5; i++) {
            notifier.onFailure(new RuntimeException("boom"));
        }
        verify(notif, times(1)).sendCrawlFailureNotification(eq(5), eq("boom"), any());
    }

    @Test
    void persistsEveryFailureToCrawlLogEvenWhenRateLimited() {
        notifier.onFailure(new RuntimeException("boom1"));
        notifier.onFailure(new RuntimeException("boom2")); // rate-limited

        verify(crawlLogs, times(2)).save(any(CrawlLog.class));
    }

    @Test
    void waitsOneMinuteBeforeSecondNotification() {
        for (int i = 0; i < 5; i++) {
            notifier.onFailure(new RuntimeException("boom"));
        }
        // first notification sent at failure 5

        clock.advance(Duration.ofSeconds(59));
        notifier.onFailure(new RuntimeException("boom"));
        verify(notif, times(1)).sendCrawlFailureNotification(anyInt(), anyString(), any());

        clock.advance(Duration.ofSeconds(2)); // total elapsed: 1m1s since 5th failure
        notifier.onFailure(new RuntimeException("boom"));
        verify(notif, times(2)).sendCrawlFailureNotification(anyInt(), anyString(), any());
    }

    @Test
    void doublesBackoffAfterEachSentNotification() {
        for (int i = 0; i < 5; i++) {
            notifier.onFailure(new RuntimeException("e")); // 5th triggers first notification
        }

        clock.advance(Duration.ofMinutes(1));
        notifier.onFailure(new RuntimeException("e")); // 2nd: after 1 minute

        // After 2nd notification, backoff doubles to 2 minutes.
        clock.advance(Duration.ofMinutes(1));
        notifier.onFailure(new RuntimeException("e")); // too soon — still in 2-minute window
        verify(notif, times(2)).sendCrawlFailureNotification(anyInt(), anyString(), any());

        clock.advance(Duration.ofMinutes(1));
        notifier.onFailure(new RuntimeException("e")); // 3rd: 2 minutes elapsed since 2nd
        verify(notif, times(3)).sendCrawlFailureNotification(anyInt(), anyString(), any());
    }

    @Test
    void recoveryAfterThresholdSendsRecoveryNotificationAndResets() {
        for (int i = 0; i < 5; i++) {
            notifier.onFailure(new RuntimeException("e"));
        }

        notifier.onSuccess();
        verify(notif).sendCrawlRecoveredNotification(eq(5), any());

        // Next failure starts a fresh streak; no notification until 5 consecutive again.
        clock.advance(Duration.ofSeconds(1));
        notifier.onFailure(new RuntimeException("e3"));
        verify(notif, times(1)).sendCrawlFailureNotification(anyInt(), anyString(), any());
    }

    @Test
    void recoveryWithoutThresholdSendsNoRecoveryNotification() {
        notifier.onFailure(new RuntimeException("e1"));
        notifier.onFailure(new RuntimeException("e2")); // only 2 failures, threshold not reached

        notifier.onSuccess();
        verify(notif, never()).sendCrawlRecoveredNotification(anyInt(), any());
    }

    @Test
    void successWithNoPriorFailuresIsNoOp() {
        notifier.onSuccess();

        verifyNoInteractions(notif);
    }

    @Test
    void respectsNotificationsDisabled() {
        CrawlerConfig disabled = new CrawlerConfig();
        disabled.setNotificationsEnabled(false);
        when(configs.findById(1L)).thenReturn(Optional.of(disabled));

        for (int i = 0; i < 5; i++) {
            notifier.onFailure(new RuntimeException("boom"));
        }
        verify(notif, never()).sendCrawlFailureNotification(anyInt(), anyString(), any());

        // The CrawlLog rows should still be written.
        verify(crawlLogs, times(5)).save(any(CrawlLog.class));
    }

    private static CrawlerConfig enabledConfig() {
        CrawlerConfig c = new CrawlerConfig();
        c.setNotificationsEnabled(true);
        c.setNtfyTopic("test-topic");
        return c;
    }

    private static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            this.now = this.now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}

package eu.vesteda.crawler.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import eu.vesteda.crawler.model.CrawlerConfig;
import eu.vesteda.crawler.repository.CrawlerConfigRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FailureNotifierTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private NotificationService notificationService;
    private CrawlerConfigRepository configRepository;
    private CrawlerConfig config;
    private MutableClock clock;
    private FailureNotifier notifier;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        configRepository = mock(CrawlerConfigRepository.class);
        config = new CrawlerConfig();
        config.setNotificationsEnabled(true);
        config.setNtfyTopic("test-topic");
        when(configRepository.findById(1L)).thenReturn(Optional.of(config));
        clock = new MutableClock(T0);
        notifier = new FailureNotifier(notificationService, configRepository, clock);
    }

    @Test
    void suppressesNotificationsBeforeThreshold() {
        for (int i = 0; i < 4; i++) {
            notifier.recordFailure("boom");
        }
        verify(notificationService, never())
                .sendCrawlerStatusNotification(anyString(), anyString(), anyString(), any());
    }

    @Test
    void notifiesOnFifthConsecutiveFailure() {
        for (int i = 0; i < 5; i++) {
            notifier.recordFailure("boom");
        }
        verify(notificationService, times(1))
                .sendCrawlerStatusNotification(anyString(), anyString(), eq("warning"), eq(config));
    }

    @Test
    void waitsOneMinuteBeforeSecondNotification() {
        for (int i = 0; i < 5; i++) {
            notifier.recordFailure("boom");
        }
        // first notification sent at failure 5

        clock.advance(Duration.ofSeconds(59));
        notifier.recordFailure("boom");
        verify(notificationService, times(1))
                .sendCrawlerStatusNotification(anyString(), anyString(), anyString(), eq(config));

        clock.advance(Duration.ofSeconds(2)); // total elapsed: 1m1s since 5th failure
        notifier.recordFailure("boom");
        verify(notificationService, times(2))
                .sendCrawlerStatusNotification(anyString(), anyString(), anyString(), eq(config));
    }

    @Test
    void doublesBackoffAfterEachSentNotification() {
        for (int i = 0; i < 5; i++) {
            notifier.recordFailure("e"); // 5th triggers first notification
        }

        clock.advance(Duration.ofMinutes(1));
        notifier.recordFailure("e"); // 2nd: after 1 minute

        clock.advance(Duration.ofMinutes(1));
        notifier.recordFailure("e"); // too soon — still in 2-minute window
        verify(notificationService, times(2))
                .sendCrawlerStatusNotification(anyString(), anyString(), anyString(), eq(config));

        clock.advance(Duration.ofMinutes(1));
        notifier.recordFailure("e"); // 3rd: 2 minutes elapsed since 2nd
        verify(notificationService, times(3))
                .sendCrawlerStatusNotification(anyString(), anyString(), anyString(), eq(config));
    }

    @Test
    void recoveryAfterThresholdSendsRecoveredAndResets() {
        for (int i = 0; i < 5; i++) {
            notifier.recordFailure("e");
        }

        notifier.recordSuccess();
        verify(notificationService, times(1))
                .sendCrawlerStatusNotification(
                        eq("[Vesteda] Crawl recovered"), anyString(),
                        eq("white_check_mark"), eq(config));

        // Next failure starts a fresh streak; no notification until 5 consecutive again.
        clock.advance(Duration.ofSeconds(1));
        notifier.recordFailure("e3");
        verify(notificationService, times(1))
                .sendCrawlerStatusNotification(anyString(), anyString(), eq("warning"), eq(config));
    }

    @Test
    void recoveryWithoutThresholdSendsNoRecoveryNotification() {
        notifier.recordFailure("e1");
        notifier.recordFailure("e2"); // only 2 failures, threshold not reached

        notifier.recordSuccess();
        verify(notificationService, never())
                .sendCrawlerStatusNotification(anyString(), anyString(), eq("white_check_mark"), any());
    }

    @Test
    void successWithoutPriorFailureIsNoOp() {
        notifier.recordSuccess();

        verifyNoInteractions(notificationService);
    }

    @Test
    void doubleSuccessOnlyEmitsOneRecoveredNotification() {
        for (int i = 0; i < 5; i++) {
            notifier.recordFailure("boom");
        }

        notifier.recordSuccess();
        notifier.recordSuccess();

        verify(notificationService, times(1))
                .sendCrawlerStatusNotification(
                        eq("[Vesteda] Crawl recovered"), anyString(),
                        eq("white_check_mark"), eq(config));
    }

    @Test
    void respectsNotificationsDisabled() {
        config.setNotificationsEnabled(false);

        for (int i = 0; i < 5; i++) {
            notifier.recordFailure("boom");
        }
        notifier.recordSuccess();

        verify(notificationService, never())
                .sendCrawlerStatusNotification(anyString(), anyString(), anyString(), eq(config));
    }

    private static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            this.now = this.now.plus(d);
        }

        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}

package eu.roofz.crawler.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import eu.roofz.crawler.service.CrawlFailureNotifier;
import eu.roofz.crawler.service.CrawlerService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.SimpleTriggerContext;

/**
 * The poll interval must mean what it says. The trigger used to schedule the next pass from
 * {@code lastCompletion()}, which makes the real period {@code (pass duration + interval)} — so a
 * crawl that took 20s turned a 10s setting into a 30s cadence, silently. Being first to a listing is
 * this crawler's entire value, so the interval is measured from the START of a pass.
 */
class CrawlerSchedulerCadenceTest {

    private static final Instant START = Instant.parse("2026-07-13T12:00:00Z");

    /** {@code now} lets a test place "the present" relative to a pass, so the clamp is deterministic. */
    private Trigger triggerWithInterval(int seconds, Instant now) {
        CrawlerConfig config = CrawlerConfig.defaults();
        config.setPollIntervalSeconds(seconds);

        CrawlerConfigRepository configRepository = mock(CrawlerConfigRepository.class);
        when(configRepository.findById(1L)).thenReturn(Optional.of(config));

        CrawlerScheduler scheduler =
                new CrawlerScheduler(
                        mock(CrawlerService.class),
                        configRepository,
                        mock(CrawlFailureNotifier.class),
                        Clock.fixed(now, ZoneOffset.UTC));

        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        scheduler.configureTasks(registrar);
        return registrar.getTriggerTaskList().get(0).getTrigger();
    }

    /** A pass that takes 3s must still be followed by one 10s after it STARTED — not 13s. */
    @Test
    void aSlowPassDoesNotStretchTheInterval() {
        // now = the moment the 3s pass finished
        Trigger trigger = triggerWithInterval(10, START.plus(Duration.ofSeconds(3)));

        SimpleTriggerContext context = new SimpleTriggerContext();
        context.update(START, START, START.plus(Duration.ofSeconds(3))); // pass took 3s

        assertThat(trigger.nextExecution(context)).isEqualTo(START.plus(Duration.ofSeconds(10)));
    }

    /** The old bug, stated directly: completion-based scheduling would have said 13s here. */
    @Test
    void nextRunIsMeasuredFromTheStartNotTheCompletion() {
        Instant completion = START.plus(Duration.ofSeconds(3));
        Trigger trigger = triggerWithInterval(10, completion);

        SimpleTriggerContext context = new SimpleTriggerContext();
        context.update(START, START, completion);

        assertThat(trigger.nextExecution(context)).isNotEqualTo(completion.plus(Duration.ofSeconds(10)));
    }

    /** A pass that overran its own interval runs again immediately — never at a time in the past. */
    @Test
    void aPassThatOverranItsIntervalRunsAgainImmediately() {
        Instant now = START.plus(Duration.ofSeconds(25)); // the pass overran its 10s interval
        Trigger trigger = triggerWithInterval(10, now);

        SimpleTriggerContext context = new SimpleTriggerContext();
        context.update(START, START, now);

        // START + 10s is long past, so the trigger must clamp to "now" rather than hand the
        // scheduler a past instant and spin.
        assertThat(trigger.nextExecution(context)).isEqualTo(now);
    }

    /** First ever run: no history, so it schedules from now rather than blowing up. */
    @Test
    void firstRunSchedulesFromNow() {
        Trigger trigger = triggerWithInterval(10, START);

        assertThat(trigger.nextExecution(new SimpleTriggerContext()))
                .isEqualTo(START.plus(Duration.ofSeconds(10)));
    }
}

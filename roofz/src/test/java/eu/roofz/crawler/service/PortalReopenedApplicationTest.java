package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.PortalApplication;
import eu.roofz.crawler.model.PortalApplicationState;
import eu.roofz.crawler.repository.ApplicantProfileRepository;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import eu.roofz.crawler.repository.PortalApplicationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * FINALIZED is not terminal, and treating it as such lost a real application.
 *
 * <p>On 2026-07-14 ReletPortal moved an application back to {@code awaiting_full_application}
 * after it had once reported {@code full_application_completed}. Our row said FINALIZED, so
 * {@code syncStatuses} never looked at it again — and the full-application invite email that would
 * have woken us was discarded as a duplicate of that same tracked application. The portal was waiting
 * on a form nobody was filling, and the user was never told.
 *
 * <p>The portal's status is the source of truth. Our state reconciles toward it, never the reverse.
 */
class PortalReopenedApplicationTest {

    private static final String APP_ID = "4387fb5c-cfe5-4f1a-b49d-a4a696abee8a";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReletPortalClient portal = mock(ReletPortalClient.class);
    private final PortalApplicationRepository repo = mock(PortalApplicationRepository.class);
    private final CrawlerConfigRepository configRepo = mock(CrawlerConfigRepository.class);

    private final PortalPipelineService service =
            new PortalPipelineService(
                    portal,
                    repo,
                    mock(ApplicantProfileRepository.class),
                    configRepo,
                    mock(NotificationService.class),
                    mock(TwilioCallService.class),
                    mock(ApplicantDocumentService.class),
                    mapper);

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private PortalApplication finalizedApplication() {
        PortalApplication p = new PortalApplication();
        p.setApplicationId(APP_ID);
        p.setState(PortalApplicationState.FINALIZED);
        p.setFinalizedAt(Instant.parse("2026-07-11T22:11:21Z"));
        p.setFullFilledAt(Instant.parse("2026-07-11T22:10:00Z"));
        p.setFullReadyNotified(true); // the user was told once, back when we filled it
        return p;
    }

    private void portalReports(String status) {
        CrawlerConfig config = CrawlerConfig.defaults();
        config.setPortalPipelineEnabled(true);
        config.setPortalDryRun(true); // don't attempt any writes in this test
        when(configRepo.findById(1L)).thenReturn(Optional.of(config));
        when(portal.isConfigured()).thenReturn(true);
        when(portal.listApplications())
                .thenReturn(
                        json(
                                "{\"data\":[{\"id\":\""
                                        + APP_ID
                                        + "\",\"attributes\":{\"application\":{\"id\":\""
                                        + APP_ID
                                        + "\",\"status\":\""
                                        + status
                                        + "\"},\"residence\":{\"address\":{}}}}]}"));
    }

    /** The actual incident: portal says awaiting_full_application, our row says FINALIZED. */
    @Test
    void anApplicationThePortalReopensIsPickedUpAgain() {
        PortalApplication p = finalizedApplication();
        // Only syncStatuses (the call whose state list includes FINALIZED) sees the row. The stage
        // loops that run afterwards get nothing, so this test isolates the reconciliation itself —
        // the fill-and-notify that follows is covered by PortalPipelineFlowTest.
        when(repo.findByStateIn(anyList()))
                .thenAnswer(
                        inv -> {
                            List<PortalApplicationState> states = inv.getArgument(0);
                            return states.contains(PortalApplicationState.FINALIZED)
                                    ? List.of(p)
                                    : List.of();
                        });
        when(repo.findByApplicationId(APP_ID)).thenReturn(Optional.of(p));
        portalReports("awaiting_full_application");

        service.pollAndAdvance();

        assertThat(p.getState()).isEqualTo(PortalApplicationState.AWAITING_FULL);
        assertThat(p.getFinalizedAt()).isNull();
        // The user has to act again and has no reason to suspect it — so they must be told again.
        assertThat(p.isFullReadyNotified()).isFalse();
    }

    /** syncStatuses must actually look at FINALIZED rows, or none of the above can ever happen. */
    @Test
    void finalizedApplicationsAreStillPolled() {
        PortalApplication p = finalizedApplication();
        // Only syncStatuses (the call whose state list includes FINALIZED) sees the row. The stage
        // loops that run afterwards get nothing, so this test isolates the reconciliation itself —
        // the fill-and-notify that follows is covered by PortalPipelineFlowTest.
        when(repo.findByStateIn(anyList()))
                .thenAnswer(
                        inv -> {
                            List<PortalApplicationState> states = inv.getArgument(0);
                            return states.contains(PortalApplicationState.FINALIZED)
                                    ? List.of(p)
                                    : List.of();
                        });
        when(repo.findByApplicationId(APP_ID)).thenReturn(Optional.of(p));
        portalReports("awaiting_full_application");

        service.pollAndAdvance();

        // If FINALIZED were excluded from the tracked set, the row would never have been read and the
        // state would still be FINALIZED.
        assertThat(p.getState()).isNotEqualTo(PortalApplicationState.FINALIZED);
    }

    /** A genuinely completed application stays finalized — we must not churn it back open. */
    @Test
    void aCompletedApplicationStaysFinalized() {
        PortalApplication p = finalizedApplication();
        // Only syncStatuses (the call whose state list includes FINALIZED) sees the row. The stage
        // loops that run afterwards get nothing, so this test isolates the reconciliation itself —
        // the fill-and-notify that follows is covered by PortalPipelineFlowTest.
        when(repo.findByStateIn(anyList()))
                .thenAnswer(
                        inv -> {
                            List<PortalApplicationState> states = inv.getArgument(0);
                            return states.contains(PortalApplicationState.FINALIZED)
                                    ? List.of(p)
                                    : List.of();
                        });
        when(repo.findByApplicationId(APP_ID)).thenReturn(Optional.of(p));
        portalReports("full_application_completed");

        service.pollAndAdvance();

        assertThat(p.getState()).isEqualTo(PortalApplicationState.FINALIZED);
        assertThat(p.getFinalizedAt()).isNotNull();
        assertThat(p.isFullReadyNotified()).isTrue(); // not re-notified
    }
}

package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.roofz.crawler.model.ApplicantDocument;
import eu.roofz.crawler.model.ApplicantProfile;
import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.PortalApplication;
import eu.roofz.crawler.model.PortalApplicationState;
import eu.roofz.crawler.repository.ApplicantProfileRepository;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import eu.roofz.crawler.repository.PortalApplicationRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * End-to-end mock of the whole ReletPortal pipeline: a single {@code pollAndAdvance()} tick drives a
 * freshly-discovered invitation all the way to FINALIZED, and we assert the real payloads produced at
 * each hop (prospect → pre-application → full-application fill → document upload → finalize). No real
 * HTTP: {@link ReletPortalClient} is mocked and the repository is a stateful stub that returns the
 * application whenever its current state matches the query, so the tick cascades through every stage.
 */
class PortalPipelineFlowTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final ReletPortalClient portal = mock(ReletPortalClient.class);
    private final PortalApplicationRepository repo = mock(PortalApplicationRepository.class);
    private final ApplicantProfileRepository profileRepo = mock(ApplicantProfileRepository.class);
    private final CrawlerConfigRepository configRepo = mock(CrawlerConfigRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final TwilioCallService twilio = mock(TwilioCallService.class);
    private final ApplicantDocumentService documents = mock(ApplicantDocumentService.class);

    private final PortalPipelineService service =
            new PortalPipelineService(
                    portal, repo, profileRepo, configRepo, notifications, twilio, documents, mapper);

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String profileJson() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/applicant-profile.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void oneTickDrivesInvitationAllTheWayToFinalized() throws Exception {
        // --- config: live (no dry-run), auto-upload + auto-finalize on ---
        CrawlerConfig config = new CrawlerConfig();
        config.setPortalDryRun(false);
        config.setPortalAutoUploadDocuments(true);
        config.setPortalAutoFinalize(true);
        when(configRepo.findById(1L)).thenReturn(Optional.of(config));
        when(portal.isConfigured()).thenReturn(true);
        when(profileRepo.findById(1L)).thenReturn(Optional.of(new ApplicantProfile(profileJson())));

        // --- the one application, seeded as freshly DISCOVERED ---
        PortalApplication app = PortalApplication.fromInvitation("inv-1", "tok-1", "email-1");

        // Stateful repo: return the app whenever its current state is in the queried set.
        when(repo.findByStateIn(anyList()))
                .thenAnswer(
                        inv -> {
                            List<PortalApplicationState> states = inv.getArgument(0);
                            return states.contains(app.getState())
                                    ? new ArrayList<>(List.of(app))
                                    : new ArrayList<>();
                        });

        // --- portal responses ---
        when(portal.getInvitation("inv-1"))
                .thenReturn(
                        json(
                                "{\"data\":{\"attributes\":{\"isFullApplicationInvitation\":false,"
                                    + "\"residence\":{\"address\":{\"streetName\":\"Nieuwe Voorbeeldgracht\","
                                    + "\"houseNumber\":\"18\",\"houseNumberExtension\":\"D\",\"cityName\":\"Amsterdam\"}}}}}"));
        when(portal.submitProspectApplication(any())).thenReturn(json("{\"data\":{\"id\":\"app-1\"}}"));
        // The scaffold the prospect step creates: placeholder DOB + local-format phone (both must be repaired).
        when(portal.getApplication("app-1"))
                .thenReturn(
                        json(
                                "{\"data\":{\"attributes\":{\"personId\":\"person-1\",\"attachments\":[],"
                                    + "\"questionnaire\":{\"person\":{\"personalDetails\":{\"firstName\":\"Jan\","
                                    + "\"phoneNumber\":\"0612345678\",\"dateOfBirth\":\"2026-07-09 12:49:45\"}}}}}}"));
        when(portal.submitPreApplication(any())).thenReturn(json("{\"data\":{\"id\":\"app-1\"}}"));
        // syncStatuses sees the portal report the pre-app is done and the full app is open.
        when(portal.listApplications())
                .thenReturn(
                        json(
                                "{\"data\":[{\"id\":\"app-1\",\"attributes\":{\"application\":{\"id\":\"app-1\","
                                    + "\"status\":\"awaiting_full_application\"},\"residence\":{\"address\":{}}}}]}"));
        when(portal.updateFullApplication(eq("app-1"), any())).thenReturn(json("{\"data\":{\"id\":\"app-1\"}}"));
        when(portal.finalizeApplication(eq("app-1"), any())).thenReturn(json("{\"data\":{\"id\":\"app-1\"}}"));

        // Two stored documents selected for upload (one undated ID, one dated payslip).
        when(documents.selectForApplication(anyInt()))
                .thenReturn(
                        List.of(
                                new ApplicantDocument(
                                        "identityDocument", "id-front.jpg", "image/jpeg", new byte[] {1}, null),
                                new ApplicantDocument(
                                        "salarySlip", "pay.pdf", "application/pdf", new byte[] {2},
                                        LocalDate.of(2026, 6, 1))));

        // --- ONE tick drives the whole cascade ---
        service.pollAndAdvance();

        assertThat(app.getState()).isEqualTo(PortalApplicationState.FINALIZED);
        assertThat(app.getApplicationId()).isEqualTo("app-1");

        // Prospect payload: single applicant, employed, salary 0 at this stage.
        ArgumentCaptor<Object> prospect = ArgumentCaptor.forClass(Object.class);
        verify(portal).submitProspectApplication(prospect.capture());
        JsonNode pp = (JsonNode) prospect.getValue();
        assertThat(pp.path("invitationId").asText()).isEqualTo("inv-1");
        assertThat(pp.path("partnerSituation").asBoolean()).isFalse();
        assertThat(pp.path("workSituation").path("workSituation").asText()).isEqualTo("employed");
        assertThat(pp.path("workSituation").path("workMonthlySalary").asInt()).isZero();

        // Pre-application payload: DOB nulled, phone E.164, housing/family/marital from the profile.
        ArgumentCaptor<Object> preApp = ArgumentCaptor.forClass(Object.class);
        verify(portal).submitPreApplication(preApp.capture());
        JsonNode ap = ((JsonNode) preApp.getValue()).path("application");
        assertThat(ap.path("person").path("personalDetails").path("dateOfBirth").isNull()).isTrue();
        assertThat(ap.path("person").path("personalDetails").path("phoneNumber").asText())
                .isEqualTo("+31612345678");
        assertThat(ap.path("currentHousingSituation").asText()).isEqualTo("alone");

        // Full app filled, both documents uploaded with the application's personId, then finalized.
        verify(portal).updateFullApplication(eq("app-1"), any());
        verify(portal, times(2))
                .uploadApplicationFile(eq("app-1"), eq("person-1"), any(), any(), any(), any());
        verify(portal).finalizeApplication(eq("app-1"), any());
        verify(notifications).sendApplicationFinalized(any(), any());
    }

    @Test
    void stopsAtFullFilledWhenAutoUploadDisabled() {
        CrawlerConfig config = new CrawlerConfig();
        config.setPortalDryRun(false);
        config.setPortalAutoUploadDocuments(false); // gate closed
        when(configRepo.findById(1L)).thenReturn(Optional.of(config));
        when(portal.isConfigured()).thenReturn(true);

        PortalApplication app = PortalApplication.fromInvitation("inv-2", null, null);
        app.setApplicationId("app-2");
        app.setState(PortalApplicationState.FULL_FILLED);
        when(repo.findByStateIn(anyList()))
                .thenAnswer(
                        inv -> {
                            List<PortalApplicationState> states = inv.getArgument(0);
                            return states.contains(app.getState())
                                    ? new ArrayList<>(List.of(app))
                                    : new ArrayList<>();
                        });
        when(portal.listApplications()).thenReturn(json("{\"data\":[]}"));

        service.pollAndAdvance();

        // Gate closed → no upload/finalize, stays FULL_FILLED, user is notified to finish manually.
        assertThat(app.getState()).isEqualTo(PortalApplicationState.FULL_FILLED);
        verify(portal, times(0)).uploadApplicationFile(any(), any(), any(), any(), any(), any());
        verify(portal, times(0)).finalizeApplication(any(), any());
        verify(notifications).sendFullApplicationReady(any(), eq("app-2"), any());
    }

    /** A pre-application-completed row is filled early (before the portal opens it), but not submitted. */
    @Test
    void eagerlyPrefillsFullApplicationBeforeItOpens() throws Exception {
        PortalApplication app = prefillCandidate("app-3");
        service.pollAndAdvance();

        // Full questionnaire saved once; state unchanged; no documents/finalize before it opens.
        assertThat(app.isFullPrefilled()).isTrue();
        assertThat(app.getState()).isEqualTo(PortalApplicationState.PRE_APP_SUBMITTED);
        verify(portal, times(1)).updateFullApplication(eq("app-3"), any());
        verify(portal, times(0)).uploadApplicationFile(any(), any(), any(), any(), any(), any());
        verify(portal, times(0)).finalizeApplication(any(), any());

        // A second tick does not save again (fullPrefilled guards it).
        service.pollAndAdvance();
        verify(portal, times(1)).updateFullApplication(eq("app-3"), any());
    }

    /** An early save the portal refuses is benign: no state change, no retry count, no escalation. */
    @Test
    void earlyPrefillFailureIsBenign() throws Exception {
        PortalApplication app = prefillCandidate("app-4");
        when(portal.updateFullApplication(eq("app-4"), any()))
                .thenThrow(new RuntimeException("400 not open yet"));

        service.pollAndAdvance();

        assertThat(app.isFullPrefilled()).isFalse();
        assertThat(app.getState()).isEqualTo(PortalApplicationState.PRE_APP_SUBMITTED);
        assertThat(app.getRetryCount()).isZero();
        verify(portal, times(0)).finalizeApplication(any(), any());
        verify(notifications, times(0)).sendPipelineError(any(), any(), any());
    }

    /** With eager pre-fill disabled, a pre-application-completed row is left untouched. */
    @Test
    void doesNotPrefillWhenDisabled() throws Exception {
        PortalApplication app = prefillCandidate("app-5");
        eagerConfig.setPortalEagerPrefill(false);

        service.pollAndAdvance();

        assertThat(app.isFullPrefilled()).isFalse();
        verify(portal, times(0)).updateFullApplication(any(), any());
    }

    private CrawlerConfig eagerConfig;

    /**
     * Seeds a PRE_APP_SUBMITTED application whose portal status is {@code pre_application_completed}
     * (pre-app done, full app not yet opened) and wires the mocks the eager pre-fill needs.
     */
    private PortalApplication prefillCandidate(String appId) throws Exception {
        eagerConfig = new CrawlerConfig();
        eagerConfig.setPortalDryRun(false); // eager pre-fill is on by default
        when(configRepo.findById(1L)).thenReturn(Optional.of(eagerConfig));
        when(portal.isConfigured()).thenReturn(true);
        when(profileRepo.findById(1L)).thenReturn(Optional.of(new ApplicantProfile(profileJson())));

        PortalApplication app = PortalApplication.fromInvitation("inv-" + appId, null, null);
        app.setApplicationId(appId);
        app.setState(PortalApplicationState.PRE_APP_SUBMITTED);
        when(repo.findByStateIn(anyList()))
                .thenAnswer(
                        inv -> {
                            List<PortalApplicationState> states = inv.getArgument(0);
                            return states.contains(app.getState())
                                    ? new ArrayList<>(List.of(app))
                                    : new ArrayList<>();
                        });
        // syncStatuses: portal reports the pre-app is done but the full app is NOT open yet.
        when(portal.listApplications())
                .thenReturn(
                        json(
                                "{\"data\":[{\"id\":\"" + appId + "\",\"attributes\":{\"application\":{\"id\":\""
                                    + appId + "\",\"status\":\"pre_application_completed\"},"
                                    + "\"residence\":{\"address\":{}}}}]}"));
        when(portal.getApplication(appId))
                .thenReturn(
                        json(
                                "{\"data\":{\"attributes\":{\"personId\":\"person-1\",\"attachments\":[],"
                                    + "\"questionnaire\":{\"person\":{\"personalDetails\":{\"firstName\":\"Jan\"}}}}}}"));
        when(portal.updateFullApplication(eq(appId), any())).thenReturn(json("{\"data\":{\"id\":\"" + appId + "\"}}"));
        return app;
    }
}

package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.roofz.crawler.model.ApplicantProfile;
import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.PortalApplication;
import eu.roofz.crawler.model.PortalApplicationState;
import eu.roofz.crawler.repository.ApplicantProfileRepository;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import eu.roofz.crawler.repository.PortalApplicationRepository;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The prospect-application POST is not idempotent server-side: if a prior attempt created the
 * application but died before we could read/persist the returned id, a blind retry would create a
 * DUPLICATE real application. On a retry we first try to recover the id by exact residence address
 * from the account's application list, and only create a new one if none matches.
 */
class PortalProspectIdempotencyTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ReletPortalClient portal = mock(ReletPortalClient.class);
    private final PortalApplicationRepository repo = mock(PortalApplicationRepository.class);
    private final ApplicantProfileRepository profileRepo = mock(ApplicantProfileRepository.class);
    private final CrawlerConfigRepository configRepo = mock(CrawlerConfigRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final TwilioCallService twilio = mock(TwilioCallService.class);

    private final PortalPipelineService service =
            new PortalPipelineService(
                    portal, repo, profileRepo, configRepo, notifications, twilio, null, mapper);

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String profileJson() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/applicant-profile.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void commonMocks(PortalApplication app) throws Exception {
        CrawlerConfig config = new CrawlerConfig();
        config.setPortalDryRun(false);
        when(configRepo.findById(1L)).thenReturn(Optional.of(config));
        when(portal.isConfigured()).thenReturn(true);
        when(profileRepo.findById(1L)).thenReturn(Optional.of(new ApplicantProfile(profileJson())));
        when(repo.findByStateIn(anyList()))
                .thenAnswer(
                        inv -> {
                            List<PortalApplicationState> states = inv.getArgument(0);
                            return states.contains(app.getState())
                                    ? new ArrayList<>(List.of(app))
                                    : new ArrayList<>();
                        });
        when(portal.getInvitation("inv-1"))
                .thenReturn(
                        json(
                                "{\"data\":{\"attributes\":{\"isFullApplicationInvitation\":false,"
                                    + "\"residence\":{\"address\":{\"streetName\":\"Kerkstraat\","
                                    + "\"houseNumber\":\"12\"}}}}}"));
    }

    @Test
    void retryRecoversExistingApplicationInsteadOfCreatingADuplicate() throws Exception {
        PortalApplication app = PortalApplication.fromInvitation("inv-1", "tok", "email-1");
        app.setRetryCount(1); // a prior attempt failed
        commonMocks(app);

        // The account already has an application at the same address (created by the failed attempt).
        when(portal.listApplications())
                .thenReturn(
                        json(
                                "{\"data\":[{\"id\":\"list-row-1\",\"attributes\":{"
                                    + "\"application\":{\"id\":\"app-existing\"},"
                                    + "\"residence\":{\"address\":{\"streetName\":\"Kerkstraat\","
                                    + "\"houseNumber\":\"12\"}}}}]}"));
        // Step 2 (pre-application) mocks so the tick can proceed after adoption.
        when(portal.getApplication("app-existing"))
                .thenReturn(
                        json(
                                "{\"data\":{\"attributes\":{\"questionnaire\":{\"person\":"
                                    + "{\"personalDetails\":{\"phoneNumber\":\"0612345678\"}}}}}}"));
        when(portal.submitPreApplication(any())).thenReturn(json("{\"data\":{\"id\":\"app-existing\"}}"));

        service.pollAndAdvance();

        verify(portal, never()).submitProspectApplication(any()); // NO duplicate create
        assertThat(app.getApplicationId()).isEqualTo("app-existing");
        assertThat(app.getState()).isEqualTo(PortalApplicationState.PRE_APP_SUBMITTED);
    }

    @Test
    void retryCreatesFreshApplicationWhenNoneExists() throws Exception {
        PortalApplication app = PortalApplication.fromInvitation("inv-1", "tok", "email-1");
        app.setRetryCount(1);
        commonMocks(app);

        // No existing application on the account → the prospect POST must run.
        when(portal.listApplications()).thenReturn(json("{\"data\":[]}"));
        when(portal.submitProspectApplication(any())).thenReturn(json("{\"data\":{\"id\":\"app-new\"}}"));
        when(portal.getApplication("app-new"))
                .thenReturn(
                        json(
                                "{\"data\":{\"attributes\":{\"questionnaire\":{\"person\":"
                                    + "{\"personalDetails\":{\"phoneNumber\":\"0612345678\"}}}}}}"));
        when(portal.submitPreApplication(any())).thenReturn(json("{\"data\":{\"id\":\"app-new\"}}"));

        service.pollAndAdvance();

        verify(portal, times(1)).submitProspectApplication(any());
        assertThat(app.getApplicationId()).isEqualTo("app-new");
    }
}

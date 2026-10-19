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
import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.PortalApplication;
import eu.roofz.crawler.model.PortalApplicationState;
import eu.roofz.crawler.repository.ApplicantProfileRepository;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import eu.roofz.crawler.repository.PortalApplicationRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Document upload must be idempotent across a partial-failure retry: if the second of two documents
 * fails to upload, the retry uploads only the failed one — never re-uploading the first (which would
 * put a duplicate attachment on a real application). The portal rewrites fileName to a CDN URL, so the
 * dedup can't rely on the portal's attachment list; it uses our own {@code uploadedDocKeys} ledger.
 */
class PortalDocumentLedgerTest {

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

    @Test
    void retryAfterPartialUploadDoesNotReUploadTheDocumentThatAlreadyLanded() {
        CrawlerConfig config = new CrawlerConfig();
        config.setPortalDryRun(false);
        config.setPortalAutoUploadDocuments(true);
        config.setPortalAutoFinalize(false); // stop after upload
        when(configRepo.findById(1L)).thenReturn(Optional.of(config));
        when(portal.isConfigured()).thenReturn(true);

        PortalApplication app = PortalApplication.fromInvitation("inv-1", null, null);
        app.setApplicationId("app-1");
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
        // Portal reports NO attachments (its list would be unreliable anyway) — dedup must come from our ledger.
        when(portal.getApplication("app-1"))
                .thenReturn(json("{\"data\":{\"attributes\":{\"personId\":\"person-1\",\"attachments\":[]}}}"));
        when(documents.selectForApplication(anyInt()))
                .thenReturn(
                        List.of(
                                new ApplicantDocument("identityDocument", "id.jpg", "image/jpeg", new byte[] {1}, null),
                                new ApplicantDocument("salarySlip", "pay.pdf", "application/pdf", new byte[] {2}, null)));

        // The identity doc uploads fine; the salary slip fails on the first attempt, then succeeds.
        when(portal.uploadApplicationFile(any(), any(), eq("identityDocument"), any(), any(), any()))
                .thenReturn(json("{}"));
        when(portal.uploadApplicationFile(any(), any(), eq("salarySlip"), any(), any(), any()))
                .thenThrow(new RuntimeException("upload failed"))
                .thenReturn(json("{}"));

        service.pollAndAdvance(); // tick 1: id OK, salary FAILS → stays FULL_FILLED, ledger has the id doc
        assertThat(app.getState()).isEqualTo(PortalApplicationState.FULL_FILLED);

        service.pollAndAdvance(); // tick 2: id skipped (ledger), salary retried → succeeds

        // The identity document was uploaded exactly ONCE across both ticks — no duplicate attachment.
        verify(portal, times(1)).uploadApplicationFile(any(), any(), eq("identityDocument"), any(), any(), any());
        // The salary slip was attempted twice (fail then success).
        verify(portal, times(2)).uploadApplicationFile(any(), any(), eq("salarySlip"), any(), any(), any());
        assertThat(app.getState()).isEqualTo(PortalApplicationState.DOCS_UPLOADED);
    }
}

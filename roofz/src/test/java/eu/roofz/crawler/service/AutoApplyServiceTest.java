package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.Listing;
import eu.roofz.crawler.repository.ListingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class AutoApplyServiceTest {

    /** A fully-valid applicant config, so the only thing that can stop a POST is a subscribability guard. */
    private static CrawlerConfig validConfig() {
        CrawlerConfig c = new CrawlerConfig();
        c.setApplicantFirstName("Jan");
        c.setApplicantLastName("Jansen");
        c.setApplicantEmail("a@b.com");
        c.setApplicantPhone("+31612345678");
        return c;
    }

    /** A listing that routes applications off-site must not be POSTed to — our candidate endpoint won't apply. */
    @Test
    void skipsAutoApplyWhenListingHasAnExternalSubscriptionUrl() {
        RestClient restClient = mock(RestClient.class);
        ListingRepository listings = mock(ListingRepository.class);
        when(listings.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));
        AutoApplyService service = new AutoApplyService(restClient, listings);

        Listing l = new Listing();
        l.setId(42L);
        l.setExternalSubscriptionUrl("https://partner.example.com/apply");
        l.setAllowPropertySubscription(true);

        AutoApplyService.ApplyResult result = service.applyFor(l, validConfig());

        assertThat(result.status()).isEqualTo(AutoApplyService.STATUS_SKIPPED);
        assertThat(l.getAppliedAt()).isNull();
        verifyNoInteractions(restClient); // never attempted the POST
    }

    /** A listing that forbids property subscription (e.g. placeholders) must not be POSTed to either. */
    @Test
    void skipsAutoApplyWhenSubscriptionIsNotAllowed() {
        RestClient restClient = mock(RestClient.class);
        ListingRepository listings = mock(ListingRepository.class);
        when(listings.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));
        AutoApplyService service = new AutoApplyService(restClient, listings);

        Listing l = new Listing();
        l.setId(43L);
        l.setAllowPropertySubscription(false);

        AutoApplyService.ApplyResult result = service.applyFor(l, validConfig());

        assertThat(result.status()).isEqualTo(AutoApplyService.STATUS_SKIPPED);
        assertThat(l.getAppliedAt()).isNull();
        verifyNoInteractions(restClient);
    }

    @Test
    void looksSuccessful_acceptsRealCreatedBody() {
        // The live candidate endpoint answers a genuine apply with this exact body.
        assertThat(AutoApplyService.looksSuccessful("Created successfully")).isTrue();
    }

    @Test
    void looksSuccessful_rejectsErrorShapedBodyOn2xx() {
        String body =
                "{\"error\":true,\"statusCode\":422,\"data\":{\"message\":\"Property does not exist\"}}";
        assertThat(AutoApplyService.looksSuccessful(body)).isFalse();
    }

    @Test
    void looksSuccessful_rejectsNullOrBlank() {
        assertThat(AutoApplyService.looksSuccessful(null)).isFalse();
        assertThat(AutoApplyService.looksSuccessful("")).isFalse();
    }

    @Test
    void looksSuccessful_rejectsExplicitSuccessFalse() {
        // The bare "contains(success)" fallback used to accept these — a 2xx body that literally says
        // the apply did NOT succeed must be treated as FAILED, not APPLIED.
        assertThat(AutoApplyService.looksSuccessful("{\"success\":false}")).isFalse();
        assertThat(AutoApplyService.looksSuccessful("{\"success\": false, \"message\":\"blocked\"}")).isFalse();
        assertThat(AutoApplyService.looksSuccessful("{\"ok\":false}")).isFalse();
        // A genuine positive still passes.
        assertThat(AutoApplyService.looksSuccessful("{\"success\":true}")).isTrue();
    }

    @Test
    void looksSuccessful_rejectsProseMerelyContainingTheWordSuccess() {
        // Regression guard: the bare contains("success") fallback also matched "unsuccessful" and
        // error prose that happens to include the word, so a 2xx error body could be recorded as a
        // phantom APPLIED that hides a genuinely missed apartment. Only an explicit marker counts now.
        assertThat(AutoApplyService.looksSuccessful("Your application was unsuccessful.")).isFalse();
        assertThat(AutoApplyService.looksSuccessful("{\"status\":\"error\",\"detail\":\"no success\"}")).isFalse();
        // The explicit positive markers still pass.
        assertThat(AutoApplyService.looksSuccessful("Created successfully")).isTrue();
        assertThat(AutoApplyService.looksSuccessful("{\"success\":true}")).isTrue();
    }

    @Test
    void normalizePhone_convertsE164ToDutchLocal() {
        // Browser posts the local 06… form; config stores +31…. They must end up identical.
        assertThat(AutoApplyService.normalizePhone("+31612345678")).isEqualTo("0612345678");
        assertThat(AutoApplyService.normalizePhone("0031612345678")).isEqualTo("0612345678");
        assertThat(AutoApplyService.normalizePhone("06 12 34 56 78")).isEqualTo("0612345678");
    }
}

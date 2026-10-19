package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import eu.roofz.crawler.model.PortalApplication;
import eu.roofz.crawler.repository.PortalApplicationRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The confirmation-email → application matching that satisfies the SLA watchdogs (W2/W3). */
class PortalConfirmationMatchingTest {

    private final PortalApplicationRepository repo = mock(PortalApplicationRepository.class);
    private final PortalPipelineService service =
            new PortalPipelineService(null, repo, null, null, null, null, null, null);

    private static PortalApplication appAt(String address) {
        PortalApplication p = new PortalApplication();
        p.setResidenceAddress(address);
        p.setApplicationId("app-1");
        return p;
    }

    @Test
    void normalizeAddress_stripsPunctuationAndCase() {
        assertThat(PortalPipelineService.normalizeAddress("Nieuwe Voorbeeldgracht 12-C, Amsterdam"))
                .isEqualTo("nieuwevoorbeeldgracht12camsterdam");
    }

    @Test
    void preApplicationConfirmationMatchesByAddressDespiteFormatting() {
        PortalApplication p = appAt("Nieuwe Voorbeeldgracht 12C, Amsterdam");
        when(repo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(p));

        String outcome =
                service.recordConfirmationEmail(
                        false, "Confirmation of your pre-application for Nieuwe Voorbeeldgracht 12-C, Amsterdam");

        assertThat(outcome).contains("pre-application confirmed");
        assertThat(p.getPreAppConfirmedAt()).isNotNull();
        assertThat(p.getFullAppConfirmedAt()).isNull();
    }

    @Test
    void fullApplicationConfirmationSetsTheFullTimestampOnly() {
        PortalApplication p = appAt("Nieuwe Voorbeeldgracht 12C, Amsterdam");
        when(repo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(p));

        String outcome =
                service.recordConfirmationEmail(
                        true, "Confirmation of your application for Nieuwe Voorbeeldgracht 12-C, Amsterdam");

        assertThat(outcome).contains("full-application confirmed");
        assertThat(p.getFullAppConfirmedAt()).isNotNull();
        assertThat(p.getPreAppConfirmedAt()).isNull();
    }

    @Test
    void confirmationForUnknownAddressMatchesNothing() {
        PortalApplication p = appAt("Nieuwe Voorbeeldgracht 12C, Amsterdam");
        when(repo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(p));

        String outcome =
                service.recordConfirmationEmail(
                        false, "Confirmation of your pre-application for Some Other Street 1, Utrecht");

        assertThat(outcome).contains("matched no tracked application");
        assertThat(p.getPreAppConfirmedAt()).isNull();
    }
}

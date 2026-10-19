package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.PortalApplication;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import eu.roofz.crawler.repository.PortalApplicationRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The offer/allocation email ("… has been allocated to you") → loud, time-critical alert (ntfy +
 * Twilio call), matched to the application by address and fired once.
 */
class PortalOfferEmailTest {

    private final PortalApplicationRepository repo = mock(PortalApplicationRepository.class);
    private final CrawlerConfigRepository configRepo = mock(CrawlerConfigRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final TwilioCallService twilio = mock(TwilioCallService.class);
    private final PortalPipelineService service =
            new PortalPipelineService(null, repo, null, configRepo, notifications, twilio, null, null);

    private PortalApplication appAt(String address) {
        PortalApplication p = new PortalApplication();
        p.setResidenceAddress(address);
        p.setApplicationId("app-1");
        return p;
    }

    @Test
    void offerEmailAlertsOncePerApplication() {
        when(configRepo.findById(1L)).thenReturn(Optional.of(new CrawlerConfig()));
        PortalApplication p = appAt("Nieuwe Voorbeeldgracht 16D, Amsterdam");
        when(repo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(p));

        String outcome =
                service.recordOfferEmail(
                        "Congratulations! Nieuwe Voorbeeldgracht 16-D, Amsterdam has been allocated to you",
                        "https://roofz.onosre.com/offer/off-1");

        assertThat(outcome).contains("offer recorded");
        assertThat(p.getOfferedAt()).isNotNull();
        assertThat(p.getOfferLink()).isEqualTo("https://roofz.onosre.com/offer/off-1");
        assertThat(p.isOfferNotified()).isTrue();
        verify(notifications).sendOfferReceived(eq("Nieuwe Voorbeeldgracht 16D, Amsterdam"), any(), any());
        verify(twilio).triggerNewListingCall();

        // A re-delivered offer for the same application does not alert again.
        service.recordOfferEmail(
                "Congratulations! Nieuwe Voorbeeldgracht 16-D, Amsterdam has been allocated to you", null);
        verify(notifications, times(1)).sendOfferReceived(any(), any(), any());
        verify(twilio, times(1)).triggerNewListingCall();
    }

    @Test
    void offerMatchesTheMoreSpecificNestedAddressNotThePrefix() {
        // A city-less stored address "Kerkstraat 1" normalizes to "kerkstraat1", which is a prefix of
        // "kerkstraat12" — so a plain first-substring match could attach the time-critical offer to the
        // WRONG home. Longest-match must pick 12 for a 12 offer.
        when(configRepo.findById(1L)).thenReturn(Optional.of(new CrawlerConfig()));
        PortalApplication one = appAt("Kerkstraat 1");
        one.setApplicationId("app-1");
        PortalApplication twelve = appAt("Kerkstraat 12");
        twelve.setApplicationId("app-12");
        // Put the shorter (prefix) one first — as newest-first might — to prove length wins, not order.
        when(repo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(one, twelve));

        service.recordOfferEmail("Congratulations! Kerkstraat 12 has been allocated to you", null);

        assertThat(twelve.getOfferedAt()).isNotNull();
        assertThat(one.getOfferedAt()).isNull();
    }

    @Test
    void offerMatchesShorterAddressWhenThatIsTheOne() {
        when(configRepo.findById(1L)).thenReturn(Optional.of(new CrawlerConfig()));
        PortalApplication one = appAt("Kerkstraat 1");
        one.setApplicationId("app-1");
        PortalApplication twelve = appAt("Kerkstraat 12");
        twelve.setApplicationId("app-12");
        when(repo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(one, twelve));

        service.recordOfferEmail("Congratulations! Kerkstraat 1 has been allocated to you", null);

        // "…kerkstraat1hasbeen…" does not contain "kerkstraat12", so only the 1 matches.
        assertThat(one.getOfferedAt()).isNotNull();
        assertThat(twelve.getOfferedAt()).isNull();
    }

    @Test
    void offerForUnknownAddressStillAlerts() {
        when(configRepo.findById(1L)).thenReturn(Optional.of(new CrawlerConfig()));
        when(repo.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        String outcome =
                service.recordOfferEmail(
                        "Congratulations! Some Other Street 1, Utrecht has been allocated to you", null);

        assertThat(outcome).contains("alerted anyway");
        verify(notifications).sendOfferReceived(any(), any(), any());
        verify(twilio).triggerNewListingCall();
    }
}

package eu.ikwilhuren.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import eu.ikwilhuren.crawler.model.Listing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How busy a home is, and — more importantly — when we are not entitled to an opinion.
 *
 * <p>These tests exist because of a feedback loop that ran live. MVGM only shows the "many viewing
 * requests" warning to someone who could actually apply, so once all five requests were spent it
 * vanished from every page. The crawler read that silence as "not full", every home's score leapt,
 * held requests were traded away for homes that were just as full, those came straight back as "no
 * more viewings possible" — and round it went, withdrawing and re-applying on the user's account.
 */
class CompetitionServiceTest {

    private static final String WARNED =
            "<a href=\"/bezichtigingsaanvraag/abc/\">Request viewing</a>"
                    + "<p>This house has many viewing requests. Increase your chance by selecting a"
                    + " different property.</p>";

    /** A home with room left: the request link is offered, and no warning alongside it. */
    private static final String QUIET =
            "<a href=\"/bezichtigingsaanvraag/abc/\">Request viewing</a>";

    /** What every page looks like once all five requests are spent: no link, and no warning. */
    private static final String AT_CAP = "<h1>Some home</h1><p>Nice kitchen.</p>";

    private PortalSession session;
    private CompetitionService service;
    private Listing listing;

    @BeforeEach
    void setUp() {
        session = mock(PortalSession.class);
        service = new CompetitionService(session);
        listing = new Listing();
        listing.setId("amsterdam-1234ab-1-teststraat-466d07e2b38d35ba896c538bb34d5500");
        listing.setSlug(listing.getId());
        listing.setTitle("Test home");
    }

    private void portalServes(String html) {
        when(session.getAuthenticated(any(), any())).thenReturn(html);
    }

    @Test
    void readsTheWarningThatAHomeIsAlreadyFull() {
        portalServes(WARNED);

        service.refresh(listing);

        assertThat(listing.getCrowded()).isTrue();
    }

    @Test
    void aHomeStillTakingViewingsIsRecordedAsSuch() {
        portalServes(QUIET);

        service.refresh(listing);

        assertThat(listing.getCrowded()).isFalse();
    }

    @Test
    void silenceFromAStrippedPageIsNotAVerdict() {
        // The page MVGM serves when all five requests are spent: no request link, so no warning
        // either. It tells us nothing, and must not be mistaken for good news.
        portalServes(AT_CAP);

        service.refresh(listing);

        assertThat(listing.getCrowded()).isNull();
        assertThat(listing.getCompetitionCheckedAt()).isNull();
    }

    @Test
    void aHomeKnownToBeFullIsNeverQuietlyUnmarked() {
        // The loop itself: a full home whose page has gone quiet must stay full. Homes do not un-fill.
        listing.setCrowded(Boolean.TRUE);
        portalServes(AT_CAP);

        service.refresh(listing);

        assertThat(listing.getCrowded()).isTrue();
    }

    @Test
    void notEvenAPageOfferingTheLinkCanUnmarkAFullHome() {
        listing.setCrowded(Boolean.TRUE);
        portalServes(QUIET);

        service.refresh(listing);

        assertThat(listing.getCrowded()).isTrue();
    }

    @Test
    void aFailedReadChangesNothing() {
        listing.setCrowded(Boolean.FALSE);
        when(session.getAuthenticated(any(), any())).thenThrow(new PortalException("down"));

        service.refresh(listing);

        assertThat(listing.getCrowded()).isFalse();
    }
}

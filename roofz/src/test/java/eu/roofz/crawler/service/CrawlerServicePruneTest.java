package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.Listing;
import eu.roofz.crawler.model.api.ApiListing;
import eu.roofz.crawler.repository.CrawlLogRepository;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import eu.roofz.crawler.repository.ListingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A listing that disappears from Roofz is gone, and must not linger on the dashboard pretending to be
 * a live match — even one we applied to. If it ever comes back, it is genuinely open again and
 * applying again is the right move.
 *
 * <p>Two guards remain, and this is what they protect:
 *
 * <ul>
 *   <li>An <b>empty</b> API response prunes nothing (a hiccup must not wipe the table).
 *   <li>A <b>single</b> miss prunes nothing. A short or partial response would otherwise delete a
 *       home that still exists — and because the next pass re-adds it as "new", that means a
 *       DUPLICATE auto-apply on a property we had already applied for.
 * </ul>
 */
class CrawlerServicePruneTest {

    private final RoofzApiClient apiClient = mock(RoofzApiClient.class);
    private final AutoApplyService autoApply = mock(AutoApplyService.class);
    private final ListingRepository listings = mock(ListingRepository.class);
    private final CrawlerConfigRepository configs = mock(CrawlerConfigRepository.class);
    private final InquiryHistoryService inquiries = mock(InquiryHistoryService.class);

    private final CrawlerService service =
            new CrawlerService(
                    apiClient,
                    new FilterService(),
                    mock(NotificationService.class),
                    autoApply,
                    listings,
                    configs,
                    mock(CrawlLogRepository.class),
                    inquiries);

    private CrawlerService.CrawlResult crawlWith(List<ApiListing> apiListings, List<Listing> stored) {
        CrawlerConfig config = CrawlerConfig.defaults();
        config.setNotificationsEnabled(false);
        config.setAutoApplyEnabled(false);
        when(configs.findById(1L)).thenReturn(Optional.of(config));
        when(apiClient.fetchAllListings(any())).thenReturn(apiListings);
        when(listings.findAll()).thenReturn(new ArrayList<>(stored));
        for (Listing l : stored) {
            when(listings.existsById(l.getId())).thenReturn(true);
            when(listings.findById(l.getId())).thenReturn(Optional.of(l));
        }
        return service.crawl();
    }

    private static Listing stored(long id, Instant appliedAt) {
        Listing l = new Listing();
        l.setId(id);
        l.setSlug("home-" + id);
        l.setTitle("Home " + id);
        l.setCity("Amsterdam");
        l.setStage("occupied");
        l.setAppliedAt(appliedAt);
        l.setInviteWatchdogAlerted(true); // watchdog has already had its say
        return l;
    }

    /** A listing the API still returns. Only its id matters here — the prune keys on presence. */
    private static ApiListing api(long id) {
        return new ApiListing(
                id,
                "home-" + id,
                "Home " + id,
                "available",
                null, // characteristic
                null, // handover
                null, // address
                null, // media
                null, // statistics
                null, // project
                null, // createdAt
                null, // publishedAt
                null, // publishedAtDate
                null, // availableAt
                null, // status
                null); // externalSubscriptionUrl
    }

    /** The reported bug: an applied listing that vanished from Roofz stayed on the dashboard. */
    @Test
    void anAppliedListingThatVanishedFromTheApiIsPruned() {
        Listing applied = stored(1L, Instant.now().minusSeconds(60 * 60 * 24 * 30)); // applied a month ago
        List<ApiListing> apiSaysOnlyThis = List.of(api(99L));

        // Absent for the first two passes: still not believed.
        crawlWith(apiSaysOnlyThis, List.of(applied));
        crawlWith(apiSaysOnlyThis, List.of(applied));
        verify(listings, never()).deleteAll(anyList());

        // Third consecutive miss: now it goes, applied or not.
        crawlWith(apiSaysOnlyThis, List.of(applied));

        ArgumentCaptor<List<Listing>> deleted = ArgumentCaptor.forClass(List.class);
        verify(listings, times(1)).deleteAll(deleted.capture());
        assertThat(deleted.getValue()).extracting(Listing::getId).containsExactly(1L);

        // and its inquiry trail goes with it, rather than orphaning rows forever.
        verify(inquiries, times(1)).deleteForListings(List.of(1L));
    }

    /** A partial API response must not delete a home that still exists — that means a duplicate apply. */
    @Test
    void aSingleMissDoesNotPrune() {
        Listing live = stored(1L, null);

        crawlWith(List.of(api(99L)), List.of(live)); // one short response

        verify(listings, never()).deleteAll(anyList());
        verify(inquiries, never()).deleteForListings(anyList());
    }

    /** A listing that reappears before the threshold has its miss count forgiven. */
    @Test
    void aListingThatComesBackResetsItsMissCount() {
        Listing flaky = stored(1L, null);

        crawlWith(List.of(api(99L)), List.of(flaky)); // miss 1
        crawlWith(List.of(api(99L)), List.of(flaky)); // miss 2
        crawlWith(List.of(api(1L), api(99L)), List.of(flaky)); // back again — count resets
        crawlWith(List.of(api(99L)), List.of(flaky)); // miss 1 again, not 3

        verify(listings, never()).deleteAll(anyList());
    }

    /** An empty response is an API hiccup, never a reason to wipe the table. */
    @Test
    void anEmptyApiResponsePrunesNothing() {
        Listing live = stored(1L, null);

        for (int i = 0; i < 5; i++) {
            crawlWith(List.of(), List.of(live));
        }

        verify(listings, never()).deleteAll(anyList());
    }

    /**
     * A home applied to moments ago is kept until the invite watchdog can fire. Deleting it first
     * would silently remove the "applied but no invite arrived" alarm.
     */
    @Test
    void aJustAppliedListingIsKeptUntilTheInviteWatchdogHasFired() {
        Listing justApplied = stored(1L, Instant.now().minusSeconds(30));
        justApplied.setInviteWatchdogAlerted(false); // watchdog still pending

        for (int i = 0; i < 5; i++) {
            crawlWith(List.of(api(99L)), List.of(justApplied));
        }

        verify(listings, never()).deleteAll(anyList());
    }
}

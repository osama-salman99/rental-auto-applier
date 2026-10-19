package eu.holland2stay.crawler.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.holland2stay.crawler.model.CrawlerConfig;
import eu.holland2stay.crawler.model.Listing;
import eu.holland2stay.crawler.repository.CrawlLogRepository;
import eu.holland2stay.crawler.repository.CrawlerConfigRepository;
import eu.holland2stay.crawler.repository.ListingRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the "empty scrape wipes the catalogue" bug: {@code fetchAllListings()}
 * returns an empty list for every failure mode (no cf_clearance, a Cloudflare block, an unexpected
 * response, a parse error), and an unguarded removal loop would then mark every stored
 * {@code available} listing {@code removed} -- a false removal, followed by a full-catalogue
 * "back available" notification storm on the next successful crawl. {@code doCrawl()} must only
 * reconcile removals when it actually saw listings.
 */
class CrawlerServiceRemovalGuardTest {

    @Test
    void emptyScrapeDoesNotMarkStoredListingsRemoved() {
        Holland2stayClient apiClient = mock(Holland2stayClient.class);
        FilterService filterService = mock(FilterService.class);
        NotificationService notificationService = mock(NotificationService.class);
        ListingRepository listingRepository = mock(ListingRepository.class);
        CrawlerConfigRepository configRepository = mock(CrawlerConfigRepository.class);
        CrawlLogRepository crawlLogRepository = mock(CrawlLogRepository.class);

        CrawlerConfig config = mock(CrawlerConfig.class);
        when(config.getTargetCity()).thenReturn(null);
        when(config.isNotificationsEnabled()).thenReturn(false);
        when(configRepository.findById(1L)).thenReturn(Optional.of(config));

        // A previously-seen, still-available listing sits in the DB.
        Listing stored = new Listing();
        stored.setId("h2s-1");
        stored.setStage("available");

        when(listingRepository.count()).thenReturn(1L);          // not the first run
        when(listingRepository.findAll()).thenReturn(List.of(stored));
        // The scrape comes back empty -- the failure mode that used to wipe the catalogue.
        when(apiClient.fetchAllListings()).thenReturn(List.of());
        when(filterService.applyFilters(any(), any())).thenReturn(List.of());

        CrawlerService svc = new CrawlerService(
                apiClient, filterService, notificationService,
                listingRepository, configRepository, crawlLogRepository);

        svc.crawl();

        assertEquals("available", stored.getStage(),
                "an empty scrape must not flip a stored listing to 'removed'");
        // And it must not have been persisted as removed either.
        verify(listingRepository, never()).save(stored);
    }
}

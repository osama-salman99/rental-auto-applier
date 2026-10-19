package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.Listing;
import eu.roofz.crawler.model.api.ApiListing;
import eu.roofz.crawler.repository.CrawlLogRepository;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import eu.roofz.crawler.repository.InquiryHistoryRepository;
import eu.roofz.crawler.repository.ListingRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CrawlerServiceStageTransitionTest {

    @Test
    void setsOptionAtWhenStageFlipsToOptionForFirstTime() {
        RoofzApiClient api = mock(RoofzApiClient.class);
        FilterService filter = new FilterService();
        NotificationService notif = mock(NotificationService.class);
        AutoApplyService apply = mock(AutoApplyService.class);
        ListingRepository listings = mock(ListingRepository.class);
        CrawlerConfigRepository configs = mock(CrawlerConfigRepository.class);
        CrawlLogRepository crawlLog = mock(CrawlLogRepository.class);
        InquiryHistoryService inquiry = mock(InquiryHistoryService.class);

        CrawlerService svc = new CrawlerService(api, filter, notif, apply, listings, configs, crawlLog, inquiry);

        CrawlerConfig config = new CrawlerConfig();
        config.setTargetCity("Amsterdam");
        config.setMinSquareMeters(null);
        config.setOnlyAvailable(false);
        when(configs.findById(1L)).thenReturn(Optional.of(config));

        Listing existing = new Listing();
        existing.setId(1L);
        existing.setStage("available");
        existing.setCity("Amsterdam");
        when(listings.findById(1L)).thenReturn(Optional.of(existing));
        when(listings.existsById(1L)).thenReturn(true);
        when(listings.findAll()).thenReturn(List.of(existing));

        ApiListing incoming = new ApiListing(
                1L, "slug", "Title", "option",
                null, null,
                new eu.roofz.crawler.model.api.ApiAddress(null, null, null, null, "Amsterdam"),
                null, null, null, null, null, null, null, null, null);
        when(api.fetchAllListings(any())).thenReturn(List.of(incoming));

        // Allow save to return the saved entity unchanged.
        lenient().when(listings.save(any())).thenAnswer(inv -> inv.getArgument(0));

        svc.crawl();

        ArgumentCaptor<Listing> saved = ArgumentCaptor.forClass(Listing.class);
        org.mockito.Mockito.verify(listings, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        Listing afterTransition =
                saved.getAllValues().stream()
                        .filter(l -> "option".equals(l.getStage()))
                        .findFirst()
                        .orElseThrow();
        assertThat(afterTransition.getOptionAt()).isNotNull();
    }
}

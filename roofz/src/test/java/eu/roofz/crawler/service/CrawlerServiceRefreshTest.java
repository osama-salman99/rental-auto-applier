package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;

import eu.roofz.crawler.model.Listing;
import eu.roofz.crawler.model.api.ApiAddress;
import eu.roofz.crawler.model.api.ApiCharacteristic;
import eu.roofz.crawler.model.api.ApiHandover;
import eu.roofz.crawler.model.api.ApiLayout;
import eu.roofz.crawler.model.api.ApiListing;
import eu.roofz.crawler.model.api.ApiMedia;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CrawlerServiceRefreshTest {

    private final CrawlerService service =
            new CrawlerService(
                    Mockito.mock(RoofzApiClient.class),
                    new FilterService(),
                    Mockito.mock(NotificationService.class),
                    Mockito.mock(AutoApplyService.class),
                    Mockito.mock(eu.roofz.crawler.repository.ListingRepository.class),
                    Mockito.mock(eu.roofz.crawler.repository.CrawlerConfigRepository.class),
                    Mockito.mock(eu.roofz.crawler.repository.CrawlLogRepository.class),
                    Mockito.mock(InquiryHistoryService.class));

    @Test
    void backfillsAvailableAtAndPublishedAtAndSubtypeOnExistingListingWithNullValues() {
        Listing existing = new Listing();
        existing.setId(1L);
        existing.setSlug("jan-van-galenstraat-454");
        existing.setTitle("Jan van Galenstraat 454");
        existing.setStage("occupied");
        existing.setCity("Amsterdam");
        existing.setLivingArea(50);
        existing.setPrice(1118);
        // availableAt, publishedAt, subtype are null (row predates the schema columns)

        long availableMs = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli();
        long publishedMs = Instant.parse("2026-05-20T11:49:25Z").toEpochMilli();
        ApiListing api = new ApiListing(
                1L,
                "jan-van-galenstraat-454",
                "Jan van Galenstraat 454",
                "occupied",
                new ApiCharacteristic(50, "50 m²", "studio", new ApiLayout(1)),
                new ApiHandover(1118, "€ 1.118", 100, "€ 100"),
                new ApiAddress(null, null, null, null, "Amsterdam"),
                new ApiMedia("https://example.com/photo.webp"),
                null,
                null,
                null,
                publishedMs,
                "2026-05-20T11:49:25Z",
                availableMs,
                null,
                null);

        boolean changed = service.applyApiFields(existing, api);

        assertThat(changed).isTrue();
        assertThat(existing.getAvailableAt()).isEqualTo(Instant.ofEpochMilli(availableMs));
        assertThat(existing.getPublishedAt()).isEqualTo(Instant.ofEpochMilli(publishedMs));
        assertThat(existing.getSubtype()).isEqualTo("studio");
        assertThat(existing.getImageUrl()).isEqualTo("https://example.com/photo.webp");
    }

    @Test
    void returnsFalseWhenAllFieldsAlreadyMatchApi() {
        Listing existing = new Listing();
        existing.setId(1L);
        existing.setSlug("slug");
        existing.setTitle("Title");
        existing.setCity("Amsterdam");
        existing.setLivingArea(50);
        existing.setSubtype("studio");
        existing.setBedrooms(1);
        existing.setPrice(1118);
        existing.setServiceCosts(100);
        existing.setImageUrl("https://example.com/photo.webp");
        existing.setAvailableAt(Instant.ofEpochMilli(1_780_000_000_000L));
        existing.setPublishedAt(Instant.ofEpochMilli(1_770_000_000_000L));

        ApiListing api = new ApiListing(
                1L,
                "slug",
                "Title",
                "occupied",
                new ApiCharacteristic(50, "50 m²", "studio", new ApiLayout(1)),
                new ApiHandover(1118, "€ 1.118", 100, "€ 100"),
                new ApiAddress(null, null, null, null, "Amsterdam"),
                new ApiMedia("https://example.com/photo.webp"),
                null,
                null,
                null,
                1_770_000_000_000L,
                null,
                1_780_000_000_000L,
                null,
                null);

        boolean changed = service.applyApiFields(existing, api);

        assertThat(changed).isFalse();
    }
}

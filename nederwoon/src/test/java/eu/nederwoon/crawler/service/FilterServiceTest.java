package eu.nederwoon.crawler.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nederwoon.crawler.model.CrawlerConfig;
import org.junit.jupiter.api.Test;

class FilterServiceTest {

    private final FilterService filterService = new FilterService();

    private static ScrapedListing listing(Integer price, Integer livingArea) {
        return new ScrapedListing(
                "id-1", "slug", "Title", "1234AB", "Amsterdam",
                price, livingArea, 2, "available", "http://img");
    }

    private static CrawlerConfig config(Integer minSqm, Integer maxPrice) {
        CrawlerConfig c = CrawlerConfig.defaults();
        c.setTargetCity(null);
        c.setMinBedrooms(null);
        c.setMinSquareMeters(minSqm);
        c.setMaxPrice(maxPrice);
        return c;
    }

    @Test
    void nullPricePassesConfiguredMaxCeiling_failOpen() {
        // Unparseable price (null) must still be surfaced when a max ceiling is set.
        assertTrue(filterService.matches(listing(null, 60), config(null, 1500)));
    }

    @Test
    void nullAreaPassesConfiguredMinFloor_failOpen() {
        // Unparseable living area (null) must still be surfaced when a min floor is set.
        assertTrue(filterService.matches(listing(1200, null), config(50, null)));
    }

    @Test
    void nullPriceAndAreaPassBothCeilings_failOpen() {
        assertTrue(filterService.matches(listing(null, null), config(50, 1500)));
    }

    @Test
    void realOutOfRangePriceStillFails() {
        // A parsed, genuinely-too-expensive price is still dropped — fail-open only covers nulls.
        assertFalse(filterService.matches(listing(2000, 60), config(null, 1500)));
    }

    @Test
    void realTooSmallAreaStillFails() {
        assertFalse(filterService.matches(listing(1200, 30), config(50, null)));
    }

    @Test
    void inRangeListingPasses() {
        assertTrue(filterService.matches(listing(1200, 60), config(50, 1500)));
    }
}

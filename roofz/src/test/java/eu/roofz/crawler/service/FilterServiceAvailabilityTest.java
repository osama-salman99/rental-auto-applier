package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;

import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.Listing;
import eu.roofz.crawler.model.api.ApiListing;
import eu.roofz.crawler.model.api.ApiStatus;
import org.junit.jupiter.api.Test;

/**
 * Availability is decided by {@code status.code}, not the coarser {@code stage}. The bug this guards
 * against: a home reading {@code stage="option"} while {@code status.code="available"} is still open
 * for applications (a human sees "Beschikbaar"), yet keying on {@code stage} skipped it — so it was
 * never notified or auto-applied. We only fall back to {@code stage} when the status is absent.
 */
class FilterServiceAvailabilityTest {

    private final FilterService filter = new FilterService();

    private static ApiListing listing(String stage, String statusCode) {
        return new ApiListing(
                1L, "slug", "Title", stage,
                null, null,
                new eu.roofz.crawler.model.api.ApiAddress(null, null, null, null, "Amsterdam"),
                null, null, null, null, null, null, null,
                statusCode == null ? null : new ApiStatus(statusCode, statusCode),
                null);
    }

    private static CrawlerConfig onlyAvailableConfig() {
        CrawlerConfig c = new CrawlerConfig();
        c.setTargetCity("Amsterdam");
        c.setOnlyAvailable(true);
        // Isolate the availability rule: drop the size/bedroom/price filters (minSquareMeters
        // defaults to 48) so matches() turns purely on isAvailable for these bare test listings.
        c.setMinSquareMeters(null);
        c.setMinBedrooms(null);
        c.setMaxPrice(null);
        return c;
    }

    /** The whole point: stage=option but status=available is applyable, and now matches. */
    @Test
    void anOptionStagedButStatusAvailableHomeMatches() {
        assertThat(filter.isAvailable(listing("option", "available"))).isTrue();
        assertThat(filter.matches(listing("option", "available"), onlyAvailableConfig())).isTrue();
    }

    /** stage=option AND status=unavailable ("Onder optie") is genuinely taken — still excluded. */
    @Test
    void anOptionStagedAndStatusUnavailableHomeDoesNotMatch() {
        assertThat(filter.isAvailable(listing("option", "unavailable"))).isFalse();
        assertThat(filter.matches(listing("option", "unavailable"), onlyAvailableConfig())).isFalse();
    }

    /** An occupied home is excluded even though status agrees. */
    @Test
    void anOccupiedHomeDoesNotMatch() {
        assertThat(filter.isAvailable(listing("occupied", "occupied"))).isFalse();
    }

    /** When status is absent (older rows, mocks), we still honour the stage as before. */
    @Test
    void fallsBackToStageWhenStatusIsAbsent() {
        assertThat(filter.isAvailable(listing("available", null))).isTrue();
        assertThat(filter.isAvailable(listing("option", null))).isFalse();
    }

    /** The stored-entity overload uses the same rule via the persisted statusCode. */
    @Test
    void storedEntityUsesStatusCodeToo() {
        Listing l = new Listing();
        l.setStage("option");
        l.setStatusCode("available");
        assertThat(filter.isAvailable(l)).isTrue();

        l.setStatusCode("unavailable");
        assertThat(filter.isAvailable(l)).isFalse();

        l.setStatusCode(null); // fall back to stage
        assertThat(filter.isAvailable(l)).isFalse();
        l.setStage("available");
        assertThat(filter.isAvailable(l)).isTrue();
    }
}

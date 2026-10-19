package eu.roofz.crawler.service;

import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.Listing;
import eu.roofz.crawler.model.api.ApiListing;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class FilterService {

    public List<ApiListing> applyFilters(List<ApiListing> listings, CrawlerConfig config) {
        return listings.stream().filter(l -> matches(l, config)).toList();
    }

    public boolean matches(ApiListing l, CrawlerConfig config) {
        return matchesCity(l, config.getTargetCity())
                && meetsMinArea(l, config.getMinSquareMeters())
                && meetsMinBedrooms(l, config.getMinBedrooms())
                && meetsMaxPrice(l, config.getMaxPrice())
                && (!config.isOnlyAvailable() || isAvailable(l));
    }

    public boolean matches(Listing l, CrawlerConfig config) {
        return matchesCity(l.getCity(), config.getTargetCity())
                && meetsMin(l.getLivingArea(), config.getMinSquareMeters())
                && meetsMin(l.getBedrooms(), config.getMinBedrooms())
                && meetsMax(l.getPrice(), config.getMaxPrice())
                && (!config.isOnlyAvailable() || isAvailable(l));
    }

    /**
     * Whether a home is open for applications. The API's {@code status.code} is the truth: a
     * {@code stage="option"} property whose {@code status.code="available"} is still applyable and
     * shows as "Beschikbaar" to a human on roofz.eu. We only fall back to {@code stage} when the
     * status is absent (older stored rows before the column was backfilled, or a mock in a test).
     */
    public boolean isAvailable(ApiListing l) {
        String code = l.status() == null ? null : l.status().code();
        return availableFrom(code, l.stage());
    }

    public boolean isAvailable(Listing l) {
        return availableFrom(l.getStatusCode(), l.getStage());
    }

    private boolean availableFrom(String statusCode, String stage) {
        if (statusCode != null && !statusCode.isBlank()) {
            return "available".equals(statusCode);
        }
        return "available".equals(stage);
    }

    private boolean matchesCity(String city, String targetCity) {
        if (targetCity == null || targetCity.isBlank()) return true;
        return targetCity.equalsIgnoreCase(city);
    }

    private boolean meetsMin(Integer value, Integer min) {
        if (min == null) return true;
        return value != null && value >= min;
    }

    private boolean meetsMax(Integer value, Integer max) {
        if (max == null) return true;
        return value != null && value <= max;
    }

    private boolean matchesCity(ApiListing l, String city) {
        if (city == null || city.isBlank()) return true;
        return l.address() != null && city.equalsIgnoreCase(l.address().location());
    }

    private boolean meetsMinArea(ApiListing l, Integer minArea) {
        if (minArea == null) return true;
        Integer area = l.characteristic() != null ? l.characteristic().livingArea() : null;
        return area != null && area >= minArea;
    }

    private boolean meetsMinBedrooms(ApiListing l, Integer minBedrooms) {
        if (minBedrooms == null) return true;
        Integer bedrooms =
                l.characteristic() != null && l.characteristic().layout() != null
                        ? l.characteristic().layout().numberOfBedrooms()
                        : null;
        return bedrooms != null && bedrooms >= minBedrooms;
    }

    private boolean meetsMaxPrice(ApiListing l, Integer maxPrice) {
        if (maxPrice == null) return true;
        Integer price = l.handover() != null ? l.handover().price() : null;
        return price != null && price <= maxPrice;
    }
}

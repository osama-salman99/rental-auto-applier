package eu.vesteda.crawler.service;

import eu.vesteda.crawler.model.CrawlerConfig;
import eu.vesteda.crawler.model.Listing;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class FilterService {

    public List<ScrapedListing> applyFilters(List<ScrapedListing> listings, CrawlerConfig config) {
        return listings.stream().filter(l -> matches(l, config)).toList();
    }

    public boolean matches(ScrapedListing l, CrawlerConfig config) {
        return matchesCity(l.city(), config.getTargetCity())
                && meetsMin(l.livingArea(), config.getMinSquareMeters())
                && meetsMin(l.bedrooms(), config.getMinBedrooms())
                && meetsMax(l.price(), config.getMaxPrice())
                && (!config.isOnlyAvailable() || "available".equals(l.stage()));
    }

    public boolean matches(Listing l, CrawlerConfig config) {
        return matchesPreferences(l, config)
                && (!config.isOnlyAvailable() || "available".equals(l.getStage()));
    }

    /**
     * The same preferences, minus the availability check.
     *
     * <p>Auto-apply needs this because the two sources disagree about what "available" means, and
     * the portal is the one that counts. A home the API calls "reserved" is one the portal will
     * still take a viewing request for — you simply join as a reserve — so judging it by the API's
     * stage would silently drop homes we are still allowed to enter the draw for. Availability is
     * decided by the portal's own status instead; this method covers only what the user asked for.
     */
    public boolean matchesPreferences(Listing l, CrawlerConfig config) {
        return matchesCity(l.getCity(), config.getTargetCity())
                && meetsMin(l.getLivingArea(), config.getMinSquareMeters())
                && meetsMin(l.getBedrooms(), config.getMinBedrooms())
                && meetsMax(l.rent(), config.getMaxPrice());
    }

    private boolean matchesCity(String city, String targetCity) {
        if (targetCity == null || targetCity.isBlank()) return true;
        return city != null && targetCity.equalsIgnoreCase(city);
    }

    private boolean meetsMin(Integer value, Integer min) {
        if (min == null) return true;
        return value != null && value >= min;
    }

    private boolean meetsMax(Integer value, Integer max) {
        if (max == null) return true;
        return value != null && value <= max;
    }
}

package eu.holland2stay.crawler.service;

import eu.holland2stay.crawler.model.CrawlerConfig;
import eu.holland2stay.crawler.model.Listing;
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
        return matchesCity(l.getCity(), config.getTargetCity())
                && meetsMin(l.getLivingArea(), config.getMinSquareMeters())
                && meetsMin(l.getBedrooms(), config.getMinBedrooms())
                && meetsMax(l.getPrice(), config.getMaxPrice())
                && (!config.isOnlyAvailable() || "available".equals(l.getStage()));
    }

    private boolean matchesCity(String city, String targetCity) {
        if (targetCity == null || targetCity.isBlank()) return true;
        return city != null && targetCity.equalsIgnoreCase(city);
    }

    private boolean meetsMin(Integer value, Integer min) {
        if (min == null) return true;
        // Fail open: a listing the scraper couldn't parse (null value) passes the ceiling check so
        // it is still surfaced/notified. On a first-come search an extra notification beats a missed
        // home. Reverse by making the null case return false again.
        if (value == null) return true;
        return value >= min;
    }

    private boolean meetsMax(Integer value, Integer max) {
        if (max == null) return true;
        // Fail open: null (unparseable) value passes the ceiling check. See meetsMin.
        if (value == null) return true;
        return value <= max;
    }
}

package eu.ikwilhuren.crawler.service;

import eu.ikwilhuren.crawler.model.CrawlerConfig;
import eu.ikwilhuren.crawler.model.Listing;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class FilterService {

    public List<ScrapedListing> applyFilters(List<ScrapedListing> listings, CrawlerConfig config) {
        return listings.stream().filter(l -> matches(l, config)).toList();
    }

    public boolean matches(ScrapedListing l, CrawlerConfig config) {
        return config.matchesCity(l.city())
                && meetsMin(l.livingArea(), config.getMinSquareMeters())
                && meetsMin(l.bedrooms(), config.getMinBedrooms())
                && meetsMax(l.price(), config.getMaxPrice())
                && (!config.isOnlyAvailable() || "available".equals(l.stage()));
    }

    public boolean matches(Listing l, CrawlerConfig config) {
        return config.matchesCity(l.getCity())
                && meetsMin(l.getLivingArea(), config.getMinSquareMeters())
                && meetsMin(l.getBedrooms(), config.getMinBedrooms())
                && meetsMax(l.getPrice(), config.getMaxPrice())
                && (!config.isOnlyAvailable() || "available".equals(l.getStage()));
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

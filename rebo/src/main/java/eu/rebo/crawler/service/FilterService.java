package eu.rebo.crawler.service;

import eu.rebo.crawler.model.CrawlerConfig;
import eu.rebo.crawler.model.Listing;
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
                && withinBudget(l.grossRent(), l.price(), config)
                && (!config.isOnlyAvailable() || "available".equals(l.stage()));
    }

    public boolean matches(Listing l, CrawlerConfig config) {
        return matchesCity(l.getCity(), config.getTargetCity())
                && meetsMin(l.getLivingArea(), config.getMinSquareMeters())
                && meetsMin(l.getBedrooms(), config.getMinBedrooms())
                && withinBudget(l.getGrossRent(), l.getPrice(), config)
                && (!config.isOnlyAvailable() || "available".equals(l.getStage()));
    }

    /**
     * A listing is in budget if the agent could act on it. When an income is configured, that means
     * gross rent (incl. service costs) within the income ÷ 4 ceiling, widened by the boundary band so
     * homes the agent would flag for review still surface. "Max rent" is then only an optional extra
     * cap; with no income it is the sole affordability test, matching the old behaviour.
     */
    private boolean withinBudget(double grossRent, Integer basePrice, CrawlerConfig config) {
        var ceiling = config.maxGrossRent();
        if (ceiling.isPresent()
                && grossRent > ceiling.getAsDouble() + AutoApplyService.BOUNDARY_MARGIN_EUR) {
            return false;
        }
        return meetsMax(basePrice, config.getMaxPrice());
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

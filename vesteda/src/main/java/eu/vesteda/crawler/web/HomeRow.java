package eu.vesteda.crawler.web;

import eu.vesteda.crawler.model.CrawlerConfig;
import eu.vesteda.crawler.model.Listing;
import eu.vesteda.crawler.service.FilterService;
import java.util.Comparator;

/**
 * One home as the browse page shows it: the listing plus the few derived facts the page filters and
 * sorts on, worked out once here rather than re-derived in the template.
 */
public record HomeRow(
        Listing listing,
        Integer rent,
        String status,
        boolean available,
        boolean inBudget,
        boolean canApply,
        boolean applied,
        long seenEpoch) {

    /** Applyable first, then newest — a sensible default before the reader touches the controls. */
    public static final Comparator<HomeRow> DEFAULT_ORDER =
            Comparator.comparing(HomeRow::canApply)
                    .reversed()
                    .thenComparing(Comparator.comparingLong(HomeRow::seenEpoch).reversed());

    /**
     * @param applied whether a viewing request is actually held on this home — decided by the caller
     *     from the portal's own account of our candidacies, not the listing's stored flag, which
     *     drifts (a home applied to by hand, or since let, keeps a true flag it no longer earns).
     */
    public static HomeRow of(
            Listing l, CrawlerConfig config, Integer ceiling, FilterService filter, boolean applied) {
        Integer rent = l.rent();
        boolean available = "available".equals(l.getStage());
        boolean inBudget = ceiling != null && rent != null && rent <= ceiling;

        // Mirrors AutoApplyService.isEligible: a home we could actually hold a request on.
        boolean canApply =
                l.getPortalHash() != null
                        && l.portalAcceptsRequests()
                        && !l.hasHardEligibilityGate()
                        && filter.matchesPreferences(l, config)
                        && inBudget;

        long seen = l.getFirstSeenAt() == null ? 0L : l.getFirstSeenAt().toEpochMilli();

        return new HomeRow(l, rent, l.getStage(), available, inBudget, canApply, applied, seen);
    }

    // Convenience accessors the template reads off the nested listing.
    public String title() {
        return listing.getTitle();
    }

    public String portalHash() {
        return listing.getPortalHash();
    }

    public Integer area() {
        return listing.getLivingArea();
    }

    public Integer bedrooms() {
        return listing.getBedrooms();
    }

    public java.time.Instant firstSeenAt() {
        return listing.getFirstSeenAt();
    }

    /** Target-group restrictions to show as badges — why a home may be harder to win, or off-limits. */
    public java.util.List<String> restrictions() {
        return listing.restrictionLabels();
    }

    // Detail-page facts (may be null until the enrichment pass has read the home's own page).
    public Integer serviceCosts() {
        return listing.getServiceCosts();
    }

    public String availableFrom() {
        return listing.getAvailableFrom();
    }

    public String availabilityLabel() {
        return listing.availabilityLabel();
    }

    public String energyLabel() {
        return listing.getEnergyLabel();
    }

    public String dwellingType() {
        return listing.getDwellingType();
    }
}

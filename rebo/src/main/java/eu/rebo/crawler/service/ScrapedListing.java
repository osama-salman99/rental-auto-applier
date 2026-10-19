package eu.rebo.crawler.service;

public record ScrapedListing(
        String id,
        String slug,
        String title,
        String postalCode,
        String city,
        Integer price,
        Double serviceCosts,
        Integer livingArea,
        Integer bedrooms,
        String stage,
        String imageUrl) {

    /** The rent the 4×-income rule is computed on: base rent plus the service-charge advance. */
    public double grossRent() {
        double base = price != null ? price : 0.0;
        double service = serviceCosts != null ? serviceCosts : 0.0;
        return base + service;
    }
}

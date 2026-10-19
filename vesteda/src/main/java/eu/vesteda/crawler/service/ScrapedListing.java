package eu.vesteda.crawler.service;

public record ScrapedListing(
        String id,
        String slug,
        String title,
        String postalCode,
        String city,
        Integer price,
        Integer livingArea,
        Integer bedrooms,
        String stage,
        String imageUrl,
        // Target-group flags the search feed carries on every unit. See Listing for why they matter.
        Boolean keyProfessions,
        Boolean sixtyFivePlus,
        Boolean prioritySocialHousing,
        Boolean middleRent,
        Integer minAge,
        String dwellingType) {}

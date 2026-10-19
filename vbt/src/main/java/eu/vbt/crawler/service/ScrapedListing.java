package eu.vbt.crawler.service;

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
        String imageUrl) {}

package eu.roofz.crawler.model.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiProject(
        Long id,
        String slug,
        String title,
        String stage,
        @JsonProperty("is_future_offer") Boolean isFutureOffer,
        ApiProjectAddress address,
        ApiProjectStatistics statistics,
        @JsonProperty("target_delivery_date") String targetDeliveryDate,
        // False means the property cannot be subscribed to (e.g. the search-profile placeholders);
        // auto-apply must not POST to those.
        @JsonProperty("allow_property_subscription") Boolean allowPropertySubscription) {}

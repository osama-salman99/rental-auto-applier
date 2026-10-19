package eu.roofz.crawler.model.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiListing(
        Long id,
        String slug,
        String title,
        String stage,
        ApiCharacteristic characteristic,
        ApiHandover handover,
        ApiAddress address,
        ApiMedia media,
        ApiStatistics statistics,
        ApiProject project,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("published_at") Long publishedAt,
        @JsonProperty("published_at_date") String publishedAtDate,
        @JsonProperty("available_at") Long availableAt,
        // The true availability signal (status.code == "available"), which can disagree with stage —
        // a stage="option" home whose status is still "available" is open for applications.
        ApiStatus status,
        // When set, applications for this listing are handled off-site; our candidate POST does not
        // apply to it, so auto-apply must skip it (and only notify).
        @JsonProperty("external_subscription_url") String externalSubscriptionUrl) {}

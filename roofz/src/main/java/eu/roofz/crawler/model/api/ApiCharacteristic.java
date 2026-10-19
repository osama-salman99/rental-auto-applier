package eu.roofz.crawler.model.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiCharacteristic(
        @JsonProperty("living_area") Integer livingArea,
        @JsonProperty("living_area_formatted") String livingAreaFormatted,
        String subtype,
        ApiLayout layout) {}

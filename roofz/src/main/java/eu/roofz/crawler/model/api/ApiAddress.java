package eu.roofz.crawler.model.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiAddress(
        String street,
        @JsonProperty("house_number") String houseNumber,
        @JsonProperty("house_number_extension") String houseNumberExtension,
        @JsonProperty("postal_code") String postalCode,
        String location) {}

package eu.roofz.crawler.model.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiHandover(
        Integer price,
        @JsonProperty("price_formatted") String priceFormatted,
        @JsonProperty("service_costs") Integer serviceCosts,
        @JsonProperty("service_costs_formatted") String serviceCostsFormatted) {}

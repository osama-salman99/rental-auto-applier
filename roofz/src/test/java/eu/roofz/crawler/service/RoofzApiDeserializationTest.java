package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.roofz.crawler.model.api.ApiListing;
import eu.roofz.crawler.model.api.ApiResponse;
import org.junit.jupiter.api.Test;

/**
 * Pins the new fields to the live roofz.eu JSON shape (a real {@code stage="option"} /
 * {@code status.code="available"} listing, captured 2026-07-20). If Jackson stops populating any of
 * these — status, external_subscription_url, project.allow_property_subscription — availability
 * silently regresses to the old stage-only behaviour, which is exactly the bug we fixed.
 */
class RoofzApiDeserializationTest {

    // Trimmed from the real properties API response for Panamalaan (id 12775392).
    private static final String JSON =
            """
            {"data":[{
              "id":12775392,
              "slug":"panamalaan-147",
              "title":"Panamalaan 147",
              "stage":"option",
              "status":{"label":"Beschikbaar","code":"available"},
              "external_subscription_url":null,
              "project":{
                "id":3769,
                "slug":"the-cohesion-little-manhattan",
                "title":"Little Manhattan",
                "allow_property_subscription":true
              },
              "handover":{"price":1309,"price_formatted":"€ 1.309","deposit_formatted":"€ 2.618"},
              "address":{"location":"Amsterdam","postal_code":"1019"}
            }]}
            """;

    @Test
    void statusAllowAndExternalUrlAreDeserialized() throws Exception {
        ApiResponse response = new ObjectMapper().readValue(JSON, ApiResponse.class);
        ApiListing l = response.data().get(0);

        assertThat(l.stage()).isEqualTo("option");
        assertThat(l.status()).isNotNull();
        assertThat(l.status().code()).isEqualTo("available");
        assertThat(l.status().label()).isEqualTo("Beschikbaar");
        assertThat(l.externalSubscriptionUrl()).isNull();
        assertThat(l.project()).isNotNull();
        assertThat(l.project().allowPropertySubscription()).isTrue();

        // And the payoff: the filter treats this option-staged home as available.
        assertThat(new FilterService().isAvailable(l)).isTrue();
    }
}

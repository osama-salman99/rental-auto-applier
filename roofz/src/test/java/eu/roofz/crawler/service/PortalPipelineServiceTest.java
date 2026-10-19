package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for the pieces that build/merge JSON payloads. The pipeline's HTTP-driven paths
 * are exercised end-to-end against the live API during dry-run validation.
 */
class PortalPipelineServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private PortalPipelineService service() {
        return new PortalPipelineService(null, null, null, null, null, null, null, mapper);
    }

    @Test
    void buildsProspectPayloadFromProfile() throws Exception {
        JsonNode profile =
                mapper.readTree("{\"person\":{\"workSituation\":{\"workSituation\":\"employed\"}}}");

        ObjectNode payload = service().buildProspectPayload("inv-9", profile);

        assertThat(payload.get("invitationId").asText()).isEqualTo("inv-9");
        assertThat(payload.get("partnerSituation").asBoolean()).isFalse();
        assertThat(payload.get("partner").isNull()).isTrue();
        assertThat(payload.get("workSituation").get("workSituation").asText()).isEqualTo("employed");
        // Salary is deliberately 0 at the prospect stage — collected in the full application.
        assertThat(payload.get("workSituation").get("workMonthlySalary").asInt()).isZero();
    }

    @Test
    void buildsPreApplicationPayloadForSingleApplicant() throws Exception {
        // The scaffold the prospect step creates carries a placeholder DOB (a timestamp) and a
        // local-format phone; buildPreApplicationPayload must repair both.
        JsonNode scaffoldPerson =
                mapper.readTree(
                        """
                        {"personalDetails": {"firstName": "Jan",
                          "phoneNumber": "0612345678", "dateOfBirth": "2026-07-09 12:49:45"}}
                        """);
        JsonNode answers =
                mapper.readTree(
                        """
                        {
                          "partnerSituation": false,
                          "currentHousingSituation": "alone",
                          "familyComposition": "single_without_children",
                          "maritalState": "single"
                        }
                        """);

        ObjectNode payload = service().buildPreApplicationPayload("inv-123", scaffoldPerson, answers);

        assertThat(payload.get("invitationId").asText()).isEqualTo("inv-123");
        JsonNode app = payload.get("application");
        assertThat(app.get("partnerSituation").asBoolean()).isFalse();
        assertThat(app.get("partner").isNull()).isTrue();
        assertThat(app.get("currentHousingSituation").asText()).isEqualTo("alone");
        assertThat(app.get("familyComposition").asText()).isEqualTo("single_without_children");
        assertThat(app.get("maritalState").asText()).isEqualTo("single");
        JsonNode pd = app.get("person").get("personalDetails");
        assertThat(pd.get("firstName").asText()).isEqualTo("Jan");
        // The placeholder DOB is nulled and the phone is normalized to E.164.
        assertThat(pd.get("dateOfBirth").isNull()).isTrue();
        assertThat(pd.get("phoneNumber").asText()).isEqualTo("+31612345678");
    }

    @Test
    void rejectsPartnerPreApplication() throws Exception {
        JsonNode answers = mapper.readTree("{\"partnerSituation\": true}");
        assertThatThrownBy(() -> service().buildPreApplicationPayload("inv-1", null, answers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("partner");
    }

    @Test
    void deepMergeOverlaysSourceOntoTargetRecursively() throws Exception {
        JsonNode target =
                mapper.readTree(
                        "{\"person\":{\"a\":1,\"nested\":{\"keep\":true,\"x\":1}},\"other\":9}");
        JsonNode source =
                mapper.readTree("{\"person\":{\"a\":2,\"nested\":{\"x\":5,\"y\":6}},\"extra\":7}");

        JsonNode merged = PortalPipelineService.deepMerge(target.deepCopy(), source);

        assertThat(merged.get("person").get("a").asInt()).isEqualTo(2); // source wins
        assertThat(merged.get("person").get("nested").get("keep").asBoolean()).isTrue(); // kept
        assertThat(merged.get("person").get("nested").get("x").asInt()).isEqualTo(5); // overwritten
        assertThat(merged.get("person").get("nested").get("y").asInt()).isEqualTo(6); // added
        assertThat(merged.get("other").asInt()).isEqualTo(9); // untouched
        assertThat(merged.get("extra").asInt()).isEqualTo(7); // added
    }
}

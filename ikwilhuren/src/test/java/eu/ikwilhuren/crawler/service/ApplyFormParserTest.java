package eu.ikwilhuren.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Pinned against a real viewing-request page captured from the portal (with the personal details
 * replaced). These assertions encode the two things that could quietly ruin an application: sending
 * a payload that isn't what the browser sends, and failing to notice a gap that would keep us out
 * of the draw.
 */
class ApplyFormParserTest {

    private ApplyFormParser parser;
    private String html;

    @BeforeEach
    void setUp() throws IOException {
        parser = new ApplyFormParser();
        html =
                new String(
                        new ClassPathResource("bezichtigingsaanvraag.html")
                                .getInputStream()
                                .readAllBytes(),
                        StandardCharsets.UTF_8);
    }

    @Test
    void echoesBackTheProfileThePortalPrefilled() {
        ApplyForm form = parser.parse(html);

        assertThat(form.fields())
                .containsEntry("a_roepnaam", "Testy")
                .containsEntry("a_achternaam", "Tester")
                .containsEntry("a_postcode", "1000AA")
                .containsEntry("a_maandinkomen", "4200")
                .containsEntry("postrequest", "processoptieform");
        assertThat(form.fields().get("_token")).isNotBlank();
    }

    @Test
    void sendsOnlyTheCheckedRadioJustLikeABrowser() {
        ApplyForm form = parser.parse(html);

        // a_woonsituatie offers Koop/Verkocht/Huur/Inwonend; only Huur is checked.
        assertThat(form.fields()).containsEntry("a_woonsituatie", "Huur");
        assertThat(form.fields()).containsEntry("a_werksituatie", "Loondienst");
    }

    @Test
    void readsSelectedOptionFromDropdowns() {
        ApplyForm form = parser.parse(html);

        assertThat(form.fields()).containsEntry("a_land", "NL");
        assertThat(form.fields()).containsEntry("a_wanneerhuren", "nu");
    }

    @Test
    void treatsTheHiddenMarkerAsTheDocumentsValueNotTheFileInput() {
        ApplyForm form = parser.parse(html);

        // The employer statement is on file: its marker carries "1", so it auto-attaches.
        assertThat(form.fields()).containsEntry("a_uwv_verkl", "1");
        assertThat(form.uploadedDocuments()).containsKey("a_uwv_verkl");
    }

    @Test
    void doesNotDemandDocumentsThatDoNotApply() {
        ApplyForm form = parser.parse(html);

        // aansl_a (tax return) is required only for the self-employed; this profile is Loondienst,
        // so an empty aansl_a must not be reported as a gap.
        assertThat(form.missingDocuments()).doesNotContain("aansl_a");
        assertThat(form.missingFields()).doesNotContain("p_achternaam"); // renting alone
    }

    @Test
    void spotsTheOneAnswerTheProfileIsMissing() {
        ApplyForm form = parser.parse(html);

        // Captured before the parking question had ever been answered — it is the sole gap, and
        // the reason this application could not have entered the draw as-is.
        assertThat(form.missingFields()).containsExactly("a_parkeerplaats");
        assertThat(form.isComplete()).isFalse();
        assertThat(form.describeGaps()).contains("a_parkeerplaats");
    }

    @Test
    void isCompleteOnceTheParkingAnswerIsSupplied() {
        ApplyForm form = parser.parse(html.replace(
                "id=\"fld2726_Nee\" value=\"Nee\" >",
                "id=\"fld2726_Nee\" value=\"Nee\" checked>"));

        assertThat(form.missingFields()).isEmpty();
        assertThat(form.missingDocuments()).isEmpty();
        assertThat(form.isComplete()).isTrue();
    }

    @Test
    void failsLoudlyWhenThePageIsNotTheForm() {
        assertThatThrownBy(() -> parser.parse("<html><body>Logged out</body></html>"))
                .isInstanceOf(PortalException.class)
                .hasMessageContaining("not found");
    }
}

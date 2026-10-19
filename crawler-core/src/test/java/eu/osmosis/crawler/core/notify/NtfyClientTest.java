package eu.osmosis.crawler.core.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * One test for the ntfy encoding rules, run inside every consuming crawler's build (crawler-core
 * has no CI of its own; its tests execute as part of each crawler's {@code ./gradlew build}). This
 * is the guard the fleet lacked: the ntfy Title/charset bug had been fixed piecemeal across five
 * shifts and seven copy-pasted files precisely because no single test covered them all.
 */
class NtfyClientTest {

    private MockRestServiceServer server;

    private NtfyClient bound() {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        return new NtfyClient(builder.build());
    }

    // The Title-header sanitizer every crawler now shares. ikwilhuren/vesteda used to POST the raw
    // title, one Dutch diacritic (or a "→" in a status change) away from a silently dropped push.
    @Test
    void asciiHeader_mapsSymbols_stripsDiacritics_replacesRemainingNonAscii() {
        assertThat(NtfyClient.asciiHeader("option → available")).isEqualTo("option -> available");
        assertThat(NtfyClient.asciiHeader("72 m² • €1300")).isEqualTo("72 m2 - EUR1300");
        assertThat(NtfyClient.asciiHeader("Muntplein Zürich")).isEqualTo("Muntplein Zurich");
        // em/en dashes (roofz pipeline titles) fold to '-'
        assertThat(NtfyClient.asciiHeader("New month — upload documents (2026-07)"))
                .isEqualTo("New month - upload documents (2026-07)");
        // un-mappable non-ASCII (emoji, lambda) is dropped, not left as '?'; the double space a
        // dropped char leaves is collapsed and the result trimmed
        assertThat(NtfyClient.asciiHeader("Application submitted 🎉")).isEqualTo("Application submitted");
        assertThat(NtfyClient.asciiHeader("aλb")).isEqualTo("ab");
        assertThat(NtfyClient.asciiHeader(null)).isEmpty();
    }

    @Test
    void asciiHeader_leavesPlainAsciiUntouched() {
        assertThat(NtfyClient.asciiHeader("[REBO] New: Kerkstraat 12"))
                .isEqualTo("[REBO] New: Kerkstraat 12");
    }

    /**
     * The whole reason this class exists: the body is POSTed as {@code text/plain; charset=utf-8}
     * (or ntfy files a "€"/"m²" push away as attachment.txt), and the title is ASCII-sanitized (or
     * a "→"/diacritic throws {@code IllegalArgumentException} and drops the push entirely).
     */
    @Test
    void send_declaresUtf8Body_sanitizesTitle_andSetsHeaders() {
        NtfyClient client = bound();
        server.expect(requestTo("https://ntfy.sh/example-rebo-alerts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Title", "[REBO] Kerkstraat: option -> available"))
                .andExpect(header("Priority", "high"))
                .andExpect(header("Tags", "house"))
                .andExpect(header("Click", "https://rebo.example/x"))
                .andExpect(content().contentType(MediaType.parseMediaType("text/plain;charset=utf-8")))
                .andExpect(content().string(containsString("72 m² • €1300")))
                .andRespond(withSuccess());

        boolean ok =
                client.send(
                        "https://ntfy.sh",
                        "example-rebo-alerts",
                        "[REBO] Kerkstraat: option → available",
                        "72 m² • €1300",
                        "https://rebo.example/x",
                        "house");

        assertThat(ok).isTrue();
        server.verify();
    }

    @Test
    void send_fallsBackToPublicServer_andOmitsBlankOptionalHeaders() {
        NtfyClient client = bound();
        server.expect(requestTo("https://ntfy.sh/example-vbt-alerts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Title", "hi"))
                .andExpect(headerDoesNotExist("Click"))
                .andExpect(headerDoesNotExist("Tags"))
                .andRespond(withSuccess());

        // blank server -> ntfy.sh; null clickUrl and null tags -> those headers omitted
        assertThat(client.send("", "example-vbt-alerts", "hi", "body", null, null)).isTrue();
        server.verify();
    }

    @Test
    void send_returnsFalse_andSendsNothing_whenTopicMissing() {
        NtfyClient client = bound();
        // no expectation registered: any outbound request would fail verify()
        assertThat(client.send("https://ntfy.sh", "   ", "t", "b", null, null)).isFalse();
        server.verify();
    }
}

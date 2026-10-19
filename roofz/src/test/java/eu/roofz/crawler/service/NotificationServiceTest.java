package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import eu.osmosis.crawler.core.notify.NtfyClient;
import eu.roofz.crawler.model.CrawlerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NotificationServiceTest {

    // Title ASCII-folding moved to the shared NtfyClient.asciiHeader (crawler-core), covered by
    // NtfyClientTest — including the em/en-dash + emoji cases roofz's own tests used to assert.

    /**
     * Regression guard for the ntfy body-encoding bug: unlike the Title (an ASCII HTTP header),
     * the message body is a UTF-8 payload and must be POSTed as {@code text/plain; charset=utf-8}.
     * Without the charset, Spring encodes the String as ISO-8859-1 and ntfy — finding bytes that
     * are not valid UTF-8 — quietly files the whole push away as "attachment.txt". This exercises
     * the {@code sendStandaloneNotification} path (batched-listings / email / pipeline notices),
     * which shipped without the charset while the single-listing path had it — the exact drift that
     * kept this bug half-open on roofz.
     */
    @Test
    void standaloneNotification_declaresUtf8Charset_soNtfyDoesNotFileItAsAttachment() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        NotificationService svc =
                new NotificationService(
                        new NtfyClient(builder.build()),
                        mock(TwilioCallService.class),
                        "https://roofz.example.com");

        server.expect(requestTo("https://ntfy.sh/test-topic"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.parseMediaType("text/plain;charset=utf-8")))
                .andExpect(content().string(containsString("€1300")))
                .andRespond(withSuccess());

        CrawlerConfig config = new CrawlerConfig();
        config.setNtfyTopic("test-topic");
        svc.sendPreApplicationSubmitted("Teststraat 1 — €1300", config);

        server.verify();
    }
}

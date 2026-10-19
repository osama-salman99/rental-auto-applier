package eu.osmosis.crawler.core.notify;

import java.text.Normalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * The one ntfy sender for every crawler.
 *
 * <p>It owns the two encoding rules that used to live — copy-pasted, and repeatedly only
 * half-fixed — in each crawler's own {@code NotificationService}:
 *
 * <ol>
 *   <li><b>Body charset.</b> The request body is POSTed as {@code text/plain; charset=utf-8}.
 *       Without the explicit charset Spring encodes it ISO-8859-1, ntfy finds bytes that are not
 *       valid utf-8, and files the whole push away as a download called {@code attachment.txt}
 *       instead of showing the message. A single {@code €}, {@code m²} or {@code •} triggers it.
 *   <li><b>Title header.</b> ntfy carries the notification title in the {@code Title} HTTP header,
 *       whose value must be ASCII — a non-ASCII character throws
 *       {@code IllegalArgumentException: invalid header value} and the <em>entire</em> notification
 *       is dropped. Every title goes through {@link #asciiHeader(String)} here, so no caller can
 *       forget it (ikwilhuren and vesteda both used to).
 * </ol>
 *
 * <p>Message <em>formatting</em> (labels, listing details, batched summaries) stays in each
 * crawler's {@code NotificationService}; only the transport lives here.
 */
public class NtfyClient {

    private static final Logger log = LoggerFactory.getLogger(NtfyClient.class);
    private static final MediaType TEXT_PLAIN_UTF8 =
            MediaType.parseMediaType("text/plain; charset=utf-8");
    private static final String DEFAULT_SERVER = "https://ntfy.sh";

    private final RestClient restClient;

    public NtfyClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * POST a notification to {@code <serverUrl>/<topic>}.
     *
     * @param serverUrl ntfy server; null/blank falls back to {@value #DEFAULT_SERVER}
     * @param topic ntfy topic; null/blank skips the send and returns {@code false}
     * @param title notification title — always ASCII-sanitized before it becomes the Title header
     * @param message notification body — always sent as utf-8
     * @param clickUrl optional {@code Click} action URL; null/blank omits the header
     * @param tags optional ntfy {@code Tags}; null/blank omits the header
     * @return {@code true} if the POST succeeded, {@code false} on missing topic or any error
     */
    public boolean send(
            String serverUrl,
            String topic,
            String title,
            String message,
            String clickUrl,
            String tags) {
        if (topic == null || topic.isBlank()) {
            log.warn("ntfy topic not configured, skipping notification: {}", title);
            return false;
        }
        String base = (serverUrl == null || serverUrl.isBlank()) ? DEFAULT_SERVER : serverUrl;
        try {
            RestClient.RequestBodySpec request =
                    restClient
                            .post()
                            .uri(base + "/" + topic)
                            .header("Title", asciiHeader(title))
                            .header("Priority", "high");
            if (tags != null && !tags.isBlank()) {
                request = request.header("Tags", tags);
            }
            if (clickUrl != null && !clickUrl.isBlank()) {
                request = request.header("Click", clickUrl);
            }
            request.contentType(TEXT_PLAIN_UTF8).body(message).retrieve().toBodilessEntity();
            log.info("ntfy notification sent: {}", title);
            return true;
        } catch (Exception e) {
            log.error("Failed to send ntfy notification: {}", title, e);
            return false;
        }
    }

    /**
     * Sanitize a string to ASCII so it is a legal HTTP header value. This is the one canonical
     * title-folder for every crawler: it maps the symbols the crawlers actually emit
     * ({@code → • ² € — –}), strips diacritics via NFKD, drops anything still non-ASCII (an emoji,
     * a CJK char) rather than leaving a {@code ?} in the title, then collapses any double space a
     * dropped character left and trims. The dash/emoji/whitespace handling was folded in from
     * roofz's own richer sanitizer so roofz no longer needs a private copy.
     */
    public static String asciiHeader(String s) {
        if (s == null) {
            return "";
        }
        String t =
                s.replace("→", "->")
                        .replace("—", "-")
                        .replace("–", "-")
                        .replace("•", "-")
                        .replace("²", "2")
                        .replace("€", "EUR");
        t = Normalizer.normalize(t, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        t = t.replaceAll("[^\\x20-\\x7E]", "");
        return t.replaceAll("\\s{2,}", " ").trim();
    }
}

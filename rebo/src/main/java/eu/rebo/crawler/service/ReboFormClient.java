package eu.rebo.crawler.service;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Posts an Interesse registration to REBO's Statamic form at cms.rebogroep.nl.
 *
 * <p>Two things make this awkward enough to shell out to the system {@code curl} rather than use a
 * Java HTTP client. First, the endpoint is CSRF-protected: you must GET {@code /sanctum/csrf-cookie}
 * to be issued {@code XSRF-TOKEN} and session cookies, then echo the (URL-decoded) token back in an
 * {@code X-XSRF-TOKEN} header on the POST — miss it and the server answers 419, not a validation
 * error. Second, these portals are known to reject non-browser TLS fingerprints; curl is the client
 * whose handshake was validated against this endpoint by hand. The app runs on the VPS, where curl is
 * present.
 *
 * <p>The submit route is {@code /!/forms/<handle>} (Statamic's front-end form route). The sibling
 * {@code /api/forms/<handle>} path is read-only and 404s on POST.
 */
@Service
public class ReboFormClient {

    private static final Logger log = LoggerFactory.getLogger(ReboFormClient.class);

    private static final String CMS = "https://cms.rebogroep.nl";
    private static final String FORM_HANDLE = "property_to_market";
    private static final String ORIGIN = "https://www.rebogroep.nl";

    private final String curlPath;
    private final String userAgent;

    public ReboFormClient(
            @Value("${crawler.curl.path:curl}") String curlPath,
            @Value("${crawler.http.user-agent:Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36}")
            String userAgent) {
        this.curlPath = curlPath;
        this.userAgent = userAgent;
    }

    /** Outcome of a submission. {@code accepted} is true only on a 2xx from the form route. */
    public record SubmitResult(boolean accepted, int httpStatus, String body) {
        public boolean validationRejected() {
            return httpStatus == 422;
        }
    }

    public SubmitResult submit(ApplyPayload payload) {
        Path jar = null;
        try {
            jar = Files.createTempFile("rebo-cms-jar-", ".txt");

            // 1. Handshake: obtain XSRF-TOKEN + session cookies. curl's -w prints the HTTP status to
            //    stdout (the process exit code is 0 on any HTTP response, so it cannot be the check).
            //    --max-time bounds curl itself: run() reads stdout to EOF *before* its 30s waitFor, so
            //    a stalled connection would otherwise hang this thread forever (waitFor never reached).
            //    Matches crawler-core CurlFetcher's --max-time 30; this hand-rolled client predated it.
            ProcResult handshakeRes = run(List.of(
                    curlPath, "-sS", "--connect-timeout", "10", "--max-time", "30",
                    "-c", jar.toString(), "-A", userAgent,
                    "-o", "/dev/null", "-w", "%{http_code}",
                    CMS + "/sanctum/csrf-cookie"));
            int handshake = parseInt(handshakeRes.stdout().trim());
            if (handshake != 204 && handshake != 200) {
                return new SubmitResult(false, handshake, "csrf-cookie handshake failed");
            }

            String token = readXsrfToken(jar);
            if (token == null) {
                return new SubmitResult(false, 0, "no XSRF-TOKEN cookie issued");
            }

            // 2. Submit. Body is form-encoded; --data-urlencode escapes each value safely.
            List<String> cmd = new ArrayList<>(List.of(
                    curlPath, "-sS", "--connect-timeout", "10", "--max-time", "30",
                    "-b", jar.toString(), "-A", userAgent,
                    "-X", "POST", CMS + "/!/forms/" + FORM_HANDLE,
                    "-H", "Accept: application/json",
                    "-H", "X-XSRF-TOKEN: " + token,
                    "-H", "Origin: " + ORIGIN,
                    "-H", "Referer: " + ORIGIN + "/",
                    "-w", "\n__HTTP__%{http_code}"));
            addField(cmd, "first_name", payload.firstName());
            addField(cmd, "last_name", payload.lastName());
            addField(cmd, "email", payload.email());
            addField(cmd, "phone", payload.phone());
            addField(cmd, "income_type", payload.incomeType());
            addField(cmd, "comment", payload.comment() == null ? "" : payload.comment());
            addField(cmd, "privacy_verklaring", "1");
            addField(cmd, "credit_check", "1");
            addField(cmd, "registered_in_netherlands", "1");
            addField(cmd, "object_id", payload.objectId());
            addField(cmd, "object_address", payload.objectAddress());
            addField(cmd, "object_postal", payload.objectPostal());

            ProcResult res = run(cmd);
            int status = parseTrailingStatus(res.stdout());
            String body = stripTrailingStatus(res.stdout());
            boolean accepted = status >= 200 && status < 300;
            if (!accepted) {
                log.warn("REBO form submit for object {} returned {}: {}",
                        payload.objectId(), status, truncate(body, 300));
            }
            return new SubmitResult(accepted, status, body);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("REBO form submit failed for object {}: {}", payload.objectId(), e.getMessage());
            return new SubmitResult(false, 0, e.getMessage());
        } finally {
            if (jar != null) {
                try {
                    Files.deleteIfExists(jar);
                } catch (IOException ignored) {
                    // best-effort; the jar carries only this session's short-lived CSRF cookies
                }
            }
        }
    }

    private static void addField(List<String> cmd, String name, String value) {
        cmd.add("--data-urlencode");
        cmd.add(name + "=" + (value == null ? "" : value));
    }

    /** Netscape cookie jar: the XSRF-TOKEN value is the 7th tab-separated field; it is URL-encoded. */
    static String readXsrfToken(Path jar) throws IOException {
        for (String line : Files.readAllLines(jar)) {
            if (line.startsWith("#") || line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length >= 7 && "XSRF-TOKEN".equals(parts[5])) {
                return URLDecoder.decode(parts[6], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static int parseTrailingStatus(String out) {
        int i = out.lastIndexOf("__HTTP__");
        if (i < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(out.substring(i + "__HTTP__".length()).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String stripTrailingStatus(String out) {
        int i = out.lastIndexOf("__HTTP__");
        return i < 0 ? out : out.substring(0, i).stripTrailing();
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n));
    }

    private ProcResult run(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean done = p.waitFor(Duration.ofSeconds(30).toSeconds(), TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new IOException("curl timed out");
        }
        return new ProcResult(p.exitValue(), stdout);
    }

    private record ProcResult(int exitStatusCode, String stdout) {}
}

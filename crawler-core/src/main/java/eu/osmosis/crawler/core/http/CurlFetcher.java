package eu.osmosis.crawler.core.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches URLs by shelling out to the system `curl`, carrying a cookie jar across calls.
 *
 * <p>Shared across the portal crawlers whose session is a server-set cookie pair and whose every
 * apply is a browser-shaped form POST (hurenbij.vesteda.com, ikwilhuren.nu). Driving those through
 * curl keeps each request identical to the one validated by hand against the live site, and
 * sidesteps the TLS-fingerprint bot checks that 403 Java HTTP clients on those portals. Anonymous
 * JSON APIs (e.g. www.vesteda.com) do not need this and keep using a plain Java HttpClient.
 *
 * <p>Not a Spring component: wired by {@link HttpFetchConfig} so the {@code Origin} header can be
 * bound per crawler from {@code crawler.curl.base-origin} — a crawler that does not set that
 * property never gets this bean.
 */
public class CurlFetcher {

    private static final Logger log = LoggerFactory.getLogger(CurlFetcher.class);

    /** The portal origin sent as the {@code Origin} header on form POSTs; bound per crawler. */
    private final String baseOrigin;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"
                    + " (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final List<String> BROWSER_HEADERS =
            List.of(
                    "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,"
                            + "image/webp,image/apng,*/*;q=0.8",
                    "Accept-Language: en-US,en;q=0.9,nl;q=0.8",
                    "Sec-Fetch-Dest: document",
                    "Sec-Fetch-Mode: navigate",
                    "Sec-Fetch-Site: none",
                    "Sec-Fetch-User: ?1",
                    "Upgrade-Insecure-Requests: 1",
                    "sec-ch-ua: \"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\","
                            + " \"Google Chrome\";v=\"120\"",
                    "sec-ch-ua-mobile: ?0",
                    "sec-ch-ua-platform: \"macOS\"");

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 5_000;

    public CurlFetcher(String baseOrigin) {
        this.baseOrigin = baseOrigin;
    }

    /** Anonymous GET, no cookies. Used by the scraper, which must stay logged out. */
    public String fetch(String url) throws CurlFetchException {
        return retrying(url, () -> request(url, null, null, null));
    }

    /** GET carrying (and updating) a session cookie jar. Used by the authenticated apply flow. */
    public String get(String url, Path cookieJar, String referer) throws CurlFetchException {
        return retrying(url, () -> request(url, cookieJar, referer, null));
    }

    /**
     * Form POST (application/x-www-form-urlencoded) carrying a session cookie jar. Never retried:
     * the portal's POSTs are state-changing (log in, submit a viewing request, withdraw one), so a
     * blind retry could double-submit. Callers decide what to do with a failure.
     */
    public String postForm(String url, Map<String, String> form, Path cookieJar, String referer)
            throws CurlFetchException {
        return request(url, cookieJar, referer, form);
    }

    private String retrying(String url, Supplier<String> call) throws CurlFetchException {
        CurlFetchException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (CurlFetchException e) {
                last = e;
                if (attempt < MAX_ATTEMPTS && e.isTransient()) {
                    log.warn("curl timeout for {} (attempt {}/{}), retrying in {}s",
                            url, attempt, MAX_ATTEMPTS, RETRY_DELAY_MS / 1000);
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                } else {
                    throw e;
                }
            }
        }
        throw last;
    }

    private String request(String url, Path cookieJar, String referer, Map<String, String> form)
            throws CurlFetchException {
        ProcessBuilder pb = new ProcessBuilder(buildCommand(url, cookieJar, referer, form));
        pb.redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new CurlFetchException("Failed to start curl: " + e.getMessage(), e);
        }

        byte[] stdout;
        byte[] stderr;
        boolean finished;
        try {
            stdout = process.getInputStream().readAllBytes();
            stderr = process.getErrorStream().readAllBytes();
            finished = process.waitFor(45, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            process.destroyForcibly();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new CurlFetchException("curl I/O failed: " + e.getMessage(), e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new CurlFetchException("curl timed out for " + url);
        }

        int exit = process.exitValue();
        if (exit != 0) {
            String err = new String(stderr, StandardCharsets.UTF_8).trim();
            String snippet =
                    new String(stdout, StandardCharsets.UTF_8)
                            .trim()
                            .replaceAll("\\s+", " ");
            if (snippet.length() > 200) snippet = snippet.substring(0, 200) + "...";
            throw new CurlFetchException(
                    "curl exit " + exit + " for " + url + ": " + err + " | body: " + snippet,
                    exit);
        }

        return new String(stdout, StandardCharsets.UTF_8);
    }

    List<String> buildCommand(String url, Path cookieJar, String referer, Map<String, String> form) {
        java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
        cmd.add("curl");
        cmd.add("--silent");
        cmd.add("--show-error");
        cmd.add("--fail-with-body");
        cmd.add("--compressed");
        cmd.add("--http2");
        cmd.add("--max-time");
        cmd.add("30");
        cmd.add("--max-filesize");
        cmd.add("5000000");
        cmd.add("--user-agent");
        cmd.add(USER_AGENT);
        for (String h : BROWSER_HEADERS) {
            cmd.add("-H");
            cmd.add(h);
        }
        if (cookieJar != null) {
            // -b reads, -c writes: the same file, so the session survives across calls.
            cmd.add("-b");
            cmd.add(cookieJar.toString());
            cmd.add("-c");
            cmd.add(cookieJar.toString());
            // The portal answers a successful login with a redirect to "/".
            cmd.add("--location");
        }
        if (referer != null) {
            cmd.add("-H");
            cmd.add("Referer: " + referer);
        }
        if (form != null) {
            cmd.add("-H");
            cmd.add("Origin: " + baseOrigin);
            cmd.add("-H");
            cmd.add("Content-Type: application/x-www-form-urlencoded");
            for (Map.Entry<String, String> e : form.entrySet()) {
                // --data-urlencode makes curl encode the value; we never hand-roll escaping.
                cmd.add("--data-urlencode");
                cmd.add(e.getKey() + "=" + (e.getValue() == null ? "" : e.getValue()));
            }
        }
        cmd.add(url);
        log.debug("curl {} {}", form != null ? "POST" : "GET", url);
        return cmd;
    }
}

package eu.nederwoon.crawler.service;

import eu.nederwoon.crawler.model.CrawlerConfig;
import eu.nederwoon.crawler.repository.CrawlerConfigRepository;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Reads NederWoon's public rental catalogue by scraping the server-rendered search pages.
 *
 * <p>NederWoon is a plain (non-SPA) verhuurmakelaar site: the search results carry every field we
 * need in the HTML — id, address, area, rooms and rent — so a single GET per page, parsed with jsoup,
 * is enough and there is no need to open each detail page.
 *
 * <p>The fetch shells out to the system {@code curl} rather than using a Java HTTP client on purpose.
 * The sibling MVGM portals 403 Java/JVM TLS fingerprints while answering {@code curl} with a browser
 * user-agent 200; NederWoon has not been observed to do this, but the app runs on the VPS where curl
 * is present and the browser-shaped handshake is the safe default across all of these Dutch sites.
 */
@Service
public class NederwoonClient {

    private static final Logger log = LoggerFactory.getLogger(NederwoonClient.class);

    static final String BASE_URL = "https://www.nederwoon.nl";
    private static final String DEFAULT_CITY = "Amsterdam";
    private static final int MAX_PAGES = 12;

    private static final Pattern HREF =
            Pattern.compile("/huurwoning/([^/]+)/(\\d+)/([^/?#\"]+)");
    private static final Pattern POSTAL = Pattern.compile("(\\d{4}\\s?[A-Z]{2})\\s+(.+)");
    private static final Pattern AREA = Pattern.compile("Woonoppervlakte\\s+(\\d+)\\s*m");
    private static final Pattern ROOMS = Pattern.compile("(\\d+)\\s+kamer");
    private static final Pattern EURO = Pattern.compile("€\\s*([0-9][0-9.]{2,})");

    private final CrawlerConfigRepository configRepository;
    private final String curlPath;
    private final String userAgent;

    public NederwoonClient(
            CrawlerConfigRepository configRepository,
            @Value("${crawler.curl.path:curl}") String curlPath,
            @Value("${crawler.http.user-agent:Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36}")
            String userAgent) {
        this.configRepository = configRepository;
        this.curlPath = curlPath;
        this.userAgent = userAgent;
    }

    public List<ScrapedListing> fetchAllListings() {
        String city = searchCity();
        List<ScrapedListing> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int page = 1; page <= MAX_PAGES; page++) {
            String url = BASE_URL + "/search?city=" + enc(city) + "&page=" + page;
            String html = fetch(url);
            if (html == null) {
                if (page == 1) {
                    throw new CrawlerFetchException("Could not reach nederwoon.nl", null);
                }
                break; // a later page failing just ends the walk
            }
            Document doc = Jsoup.parse(html, BASE_URL);
            var anchors = doc.select("a.see-page-button");
            if (anchors.isEmpty()) {
                break;
            }
            int added = 0;
            for (Element a : anchors) {
                ScrapedListing l = parseCard(a);
                if (l != null && seen.add(l.id())) {
                    all.add(l);
                    added++;
                }
            }
            if (added == 0) {
                break; // pagination looped back to already-seen cards
            }
        }

        log.info("Fetched {} NederWoon rental listings (search city={})", all.size(), city);
        return all;
    }

    ScrapedListing parseCard(Element anchor) {  // package-private for NederwoonClientParseTest
        Matcher m = HREF.matcher(anchor.attr("href"));
        if (!m.find()) {
            return null;
        }
        String id = m.group(2);
        String slug = anchor.attr("href");
        String city = capitalize(m.group(1));
        String title = anchor.text().trim();

        Element col = anchor.closest("div.click-see-page-button");
        Element row = col != null ? col.parent() : anchor.parent();
        String rowText = row != null ? row.text() : anchor.parent().text();

        String postalCode = null;
        Element pc = row != null ? row.selectFirst("p.color-medium") : null;
        if (pc != null) {
            Matcher pm = POSTAL.matcher(pc.text().trim());
            if (pm.find()) {
                postalCode = pm.group(1).replace(" ", "");
                city = pm.group(2).trim();
            }
        }

        Integer area = firstInt(AREA, rowText);
        Integer rooms = firstInt(ROOMS, rowText);
        Integer price = maxEuro(rowText);

        String imageUrl = null;
        Element img = row != null ? row.selectFirst("img[data-src]") : null;
        if (img != null && !img.attr("data-src").isBlank()) {
            String src = img.attr("data-src");
            imageUrl = src.startsWith("http") ? src : BASE_URL + src;
        }

        // A card on the live search feed is, by definition, currently offered.
        return new ScrapedListing(id, slug, title, postalCode, city, price, area, rooms,
                "available", imageUrl);
    }

    private String searchCity() {
        CrawlerConfig config = configRepository.findById(1L).orElse(null);
        if (config == null || config.getTargetCity() == null || config.getTargetCity().isBlank()) {
            return DEFAULT_CITY;
        }
        // targetCity may be a comma-separated metro list; the site's city search is a radius, so the
        // first token pulls in the neighbours too and FilterService keeps only the exact matches.
        return config.getTargetCity().split(",")[0].trim();
    }

    /** One retry, so a single slow response doesn't throw away the whole crawl. */
    static final int FETCH_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 3_000;

    /**
     * GETs a page via curl, retrying once on a <em>transient</em> failure — a 30s timeout, which
     * curl reports as exit 28 (and, if curl itself hangs, our own {@code waitFor} tripping). A lone
     * slow response otherwise throws away the whole crawl cycle (page-1 null → {@link
     * CrawlerFetchException}) or, on later pages, silently truncates the catalogue at the break. A
     * hard curl error (DNS failure, connection refused, any non-timeout exit) is <em>not</em>
     * retried — it won't clear in 3 seconds. Returns null when every attempt fails or the body is
     * blank. (Mirrors the same fix in the sibling vb&t crawler's client.)
     */
    String fetch(String url) {
        for (int attempt = 1; attempt <= FETCH_ATTEMPTS; attempt++) {
            boolean lastAttempt = attempt == FETCH_ATTEMPTS;
            try {
                Process p = new ProcessBuilder(
                        curlPath, "-sS", "--compressed", "--max-time", "30", "-A", userAgent, url)
                        .redirectErrorStream(false)
                        .start();
                byte[] out = p.getInputStream().readAllBytes();
                boolean done = p.waitFor(40, TimeUnit.SECONDS);
                if (!done) {
                    p.destroyForcibly();
                    if (!lastAttempt) {
                        log.warn("curl hung for {} (attempt {}), retrying", url, attempt);
                        Thread.sleep(RETRY_DELAY_MS);
                        continue;
                    }
                    log.warn("curl timed out for {}", url);
                    return null;
                }
                int exit = p.exitValue();
                if (exit != 0) {
                    if (exit == 28 && !lastAttempt) {
                        log.warn("curl timed out (exit 28) for {} (attempt {}), retrying", url, attempt);
                        Thread.sleep(RETRY_DELAY_MS);
                        continue;
                    }
                    log.warn("curl exit {} for {}", exit, url);
                    return null;
                }
                String body = new String(out, StandardCharsets.UTF_8);
                return body.isBlank() ? null : body;
            } catch (IOException e) {
                log.warn("curl I/O error for {}: {}", url, e.getMessage());
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    static Integer firstInt(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            try {
                return Integer.valueOf(m.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** The largest euro figure on the card — rent dominates the smaller service-cost line. */
    static Integer maxEuro(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = EURO.matcher(text);
        Integer max = null;
        while (m.find()) {
            String digits = m.group(1).replace(".", "");
            try {
                int v = Integer.parseInt(digits);
                if (max == null || v > max) {
                    max = v;
                }
            } catch (NumberFormatException ignored) {
                // skip malformed figure
            }
        }
        return max;
    }

    static String capitalize(String s) {
        if (s == null || s.isBlank()) {
            return s;
        }
        String t = s.replace('-', ' ').trim();
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

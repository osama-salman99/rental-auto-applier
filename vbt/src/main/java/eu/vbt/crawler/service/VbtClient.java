package eu.vbt.crawler.service;

import java.io.IOException;
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
 * Reads vb&t Verhuurmakelaars' rental catalogue from its server-rendered listing pages.
 *
 * <p>vb&t is a SvelteKit site but the {@code /woningen} pages are rendered server-side: each home is
 * an {@code a.property} card carrying address, rent, surface, rooms and status inline, so one GET per
 * paginated page parsed with jsoup is enough — no need to open each detail page. Fetching shells out
 * to {@code curl} with a browser user-agent, the safe handshake across these Dutch portals.
 */
@Service
public class VbtClient {

    private static final Logger log = LoggerFactory.getLogger(VbtClient.class);

    static final String BASE_URL = "https://vbtverhuurmakelaars.nl";
    private static final int MAX_PAGES = 12;

    private static final Pattern SLUG = Pattern.compile("/woning/([^/?#\"]+)");
    private static final Pattern AREA = Pattern.compile("(\\d+)\\s*m");
    private static final Pattern ROOMS = Pattern.compile("(\\d+)");
    private static final Pattern EURO = Pattern.compile("€\\s*([0-9][0-9.]{2,})");

    private final String curlPath;
    private final String userAgent;

    public VbtClient(
            @Value("${crawler.curl.path:curl}") String curlPath,
            @Value("${crawler.http.user-agent:Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36}")
            String userAgent) {
        this.curlPath = curlPath;
        this.userAgent = userAgent;
    }

    public List<ScrapedListing> fetchAllListings() {
        List<ScrapedListing> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int page = 1; page <= MAX_PAGES; page++) {
            // Page 1 lives at /woningen; /woningen/1 just 302s to it. Pages 2+ are /woningen/N.
            String url = page == 1 ? BASE_URL + "/woningen" : BASE_URL + "/woningen/" + page;
            String html = fetch(url);
            if (html == null) {
                if (page == 1) {
                    throw new CrawlerFetchException("Could not reach vbtverhuurmakelaars.nl", null);
                }
                break;
            }
            Document doc = Jsoup.parse(html, BASE_URL);
            var cards = doc.select("a.property");
            if (cards.isEmpty()) {
                break;
            }
            int added = 0;
            for (Element card : cards) {
                ScrapedListing l = parseCard(card);
                if (l != null && seen.add(l.id())) {
                    all.add(l);
                    added++;
                }
            }
            if (added == 0) {
                break;
            }
        }

        log.info("Fetched {} vb&t rental listings", all.size());
        return all;
    }

    ScrapedListing parseCard(Element card) {  // package-private for VbtClientParseTest
        Matcher m = SLUG.matcher(card.attr("href"));
        if (!m.find()) {
            return null;
        }
        String id = m.group(1);
        String slug = card.attr("href");

        Element items = card.selectFirst("div.items");
        String city = null;
        String street = null;
        Integer price = null;
        if (items != null) {
            Element cityEl = items.selectFirst("> div");
            if (cityEl != null) {
                city = cityEl.text().trim();
            }
            Element streetEl = items.selectFirst("span.normal");
            if (streetEl != null) {
                street = streetEl.text().trim();
            }
            Element priceEl = items.selectFirst("div.price");
            if (priceEl != null) {
                price = firstEuro(priceEl.text());
            }
        }

        Integer area = tableValueInt(card, "Woonoppervlakte", AREA);
        Integer rooms = tableValueInt(card, "Kamers", ROOMS);
        String stage = mapStatus(card.selectFirst("span.status"));

        String imageUrl = null;
        Element vis = card.selectFirst("div.visimage");
        if (vis != null) {
            Matcher bg = Pattern.compile("url\\(([^)]+)\\)").matcher(vis.attr("style"));
            if (bg.find()) {
                String u = bg.group(1).replace("\"", "").replace("'", "").trim();
                imageUrl = u.startsWith("http") ? u : BASE_URL + u;
            }
        }

        String title = street != null ? street : id;
        return new ScrapedListing(id, slug, title, null, city, price, area, rooms, stage, imageUrl);
    }

    /** Reads a value cell from the card's spec table by its label ("Woonoppervlakte", "Kamers"). */
    private static Integer tableValueInt(Element card, String label, Pattern extract) {
        for (Element tr : card.select("table tr")) {
            var tds = tr.select("td");
            if (tds.size() >= 2 && tds.get(0).text().trim().startsWith(label)) {
                Matcher m = extract.matcher(tds.get(1).text());
                if (m.find()) {
                    try {
                        return Integer.valueOf(m.group(1));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static String mapStatus(Element status) {
        if (status == null) {
            return "unknown";
        }
        String cls = status.className().toLowerCase();
        String txt = status.text().toLowerCase();
        if (cls.contains("available") || txt.contains("beschikbaar")) {
            return "available";
        }
        if (txt.contains("verhuurd")) {
            return "rented";
        }
        if (txt.contains("optie") || txt.contains("gereserveerd")) {
            return "reserved";
        }
        return "unknown";
    }

    static Integer firstEuro(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = EURO.matcher(text);
        if (m.find()) {
            try {
                return Integer.valueOf(m.group(1).replace(".", ""));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** One retry, so a single slow response doesn't throw away the whole crawl. */
    static final int FETCH_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 3_000;

    /**
     * GETs a page via curl, retrying once on a <em>transient</em> failure — a 30s timeout, which
     * curl reports as exit 28 (and, if curl itself hangs, our own {@code waitFor} tripping).
     * vb&t's server-rendered pages occasionally stall past 30s; a lone blip was throwing away the
     * entire crawl cycle (page-1 null → {@link CrawlerFetchException}) and, on later pages, silently
     * truncating the catalogue at the break. A hard curl error (DNS failure, connection refused, any
     * non-timeout exit) is <em>not</em> retried — that won't clear in 3 seconds. Returns null when
     * every attempt fails or the body is blank.
     */
    String fetch(String url) {
        for (int attempt = 1; attempt <= FETCH_ATTEMPTS; attempt++) {
            boolean lastAttempt = attempt == FETCH_ATTEMPTS;
            try {
                Process p = new ProcessBuilder(
                        curlPath, "-sS", "-L", "--compressed", "--max-time", "30", "-A", userAgent, url)
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
}

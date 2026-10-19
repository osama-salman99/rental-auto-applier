package eu.holland2stay.crawler.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Reads Holland2Stay's rental catalogue from its Adobe Commerce (Magento) GraphQL endpoint.
 *
 * <p><b>Cloudflare-gated.</b> {@code www.holland2stay.com/graphql} sits behind Cloudflare's managed
 * challenge, which 403s a plain server-side request (the VPS included). To read it, this client sends
 * a {@code cf_clearance} cookie plus the matching user-agent, both supplied out of band via
 * {@code holland2stay.cf-clearance} / {@code holland2stay.user-agent} (a systemd drop-in on the VPS).
 * A {@code cf_clearance} is obtained once from a real browser session and is IP+UA bound; it expires,
 * so it must be refreshed (see the deploy handoff — a headless-Chromium refresher is the intended
 * long-term source). Without it this client logs a warning and returns nothing, so the service runs
 * cleanly in observe mode until clearance is provided.
 *
 * <p>The GraphQL query and the mapping of Holland2Stay's custom product attributes (city, rent, area,
 * rooms, availability) must be finalised against the live schema once clearance is in place — the
 * query below is the starting point and parsing is deliberately defensive.
 */
@Service
public class Holland2stayClient {

    private static final Logger log = LoggerFactory.getLogger(Holland2stayClient.class);

    static final String BASE_URL = "https://www.holland2stay.com";
    private static final String GRAPHQL = BASE_URL + "/graphql";

    /** Core Magento fields (always present) plus Holland2Stay's known custom city/rent attributes. */
    private static final String DEFAULT_QUERY =
            "{ products(filter: {}, pageSize: 500, currentPage: 1) { total_count items { "
                    + "name sku url_key city available_to_book maximum_number_of_persons "
                    + "number_of_rooms living_area basic_rent "
                    + "price_range { minimum_price { regular_price { value } } } "
                    + "small_image { url } } } }";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String curlPath;
    private final String cfClearance;
    private final String userAgent;
    private final String query;

    /**
     * cf_clearance is a constant {@code @Value}, so the "not configured" branch never changes at
     * runtime — without this guard it warned on every scheduled crawl (~1/min, ~1440/day of the
     * identical line), burying real warnings. Warn once, then stay quiet.
     */
    private final AtomicBoolean noClearanceWarned = new AtomicBoolean(false);

    public Holland2stayClient(
            @Value("${crawler.curl.path:curl}") String curlPath,
            @Value("${holland2stay.cf-clearance:}") String cfClearance,
            @Value("${holland2stay.user-agent:Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36}")
            String userAgent,
            @Value("${holland2stay.graphql-query:}") String query) {
        this.curlPath = curlPath;
        this.cfClearance = cfClearance;
        this.userAgent = userAgent;
        this.query = (query == null || query.isBlank()) ? DEFAULT_QUERY : query;
    }

    public List<ScrapedListing> fetchAllListings() {
        if (cfClearance == null || cfClearance.isBlank()) {
            if (noClearanceWarned.compareAndSet(false, true)) {
                log.warn("Holland2Stay: no cf_clearance configured — the GraphQL API is Cloudflare-gated, "
                        + "so no listings can be read yet. Set HOLLAND2STAY_CF_CLEARANCE (see the README). "
                        + "Running in observe mode. (This warning is logged once; further crawls stay silent "
                        + "until clearance is set.)");
            }
            return List.of();
        }

        String json = post(query);
        if (json == null) {
            log.warn("Holland2Stay: GraphQL request blocked or failed (Cloudflare clearance may be stale).");
            return List.of();
        }

        try {
            JsonNode root = mapper.readTree(json);
            JsonNode items = root.path("data").path("products").path("items");
            if (!items.isArray()) {
                log.warn("Holland2Stay: unexpected GraphQL response (no products.items) — {}",
                        truncate(json, 200));
                return List.of();
            }
            List<ScrapedListing> out = new ArrayList<>();
            for (JsonNode item : items) {
                ScrapedListing l = parseItem(item);
                if (l != null) {
                    out.add(l);
                }
            }
            log.info("Fetched {} Holland2Stay listings", out.size());
            return out;
        } catch (IOException e) {
            log.warn("Holland2Stay: could not parse GraphQL response: {}", e.getMessage());
            return List.of();
        }
    }

    private ScrapedListing parseItem(JsonNode item) {
        String sku = text(item, "sku");
        String urlKey = text(item, "url_key");
        String id = sku != null ? sku : urlKey;
        if (id == null) {
            return null;
        }
        String slug = urlKey != null ? "/" + urlKey : null;
        String title = text(item, "name");
        String city = firstMatching(item, "city");
        Integer area = intOf(item, "living_area");
        Integer rooms = intOf(item, "number_of_rooms");
        Integer price = intOf(item, "basic_rent");
        if (price == null) {
            JsonNode v = item.path("price_range").path("minimum_price").path("regular_price").path("value");
            if (v.isNumber()) {
                price = (int) Math.round(v.asDouble());
            }
        }
        // available_to_book is Holland2Stay's "can you respond now" flag.
        JsonNode avail = item.path("available_to_book");
        String stage = avail.isMissingNode() ? "available"
                : (avail.asInt(0) > 0 || avail.asBoolean(false) ? "available" : "reserved");
        String imageUrl = item.path("small_image").path("url").asText(null);

        return new ScrapedListing(id, slug, title, null, city, price, area, rooms, stage, imageUrl);
    }

    private String post(String q) {
        try {
            String body = mapper.writeValueAsString(Map.of("query", q));
            Process p = new ProcessBuilder(
                    curlPath, "-sS", "--compressed", "--max-time", "30",
                    "-X", "POST", GRAPHQL,
                    "-H", "Content-Type: application/json",
                    "-H", "Accept: application/json",
                    "-A", userAgent,
                    "-b", "cf_clearance=" + cfClearance,
                    "--data-binary", body)
                    .redirectErrorStream(false)
                    .start();
            byte[] out = p.getInputStream().readAllBytes();
            boolean done = p.waitFor(40, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                return null;
            }
            String s = new String(out, StandardCharsets.UTF_8);
            // A Cloudflare challenge answers with an HTML page, not JSON.
            if (s.isBlank() || s.stripLeading().startsWith("<")) {
                return null;
            }
            return s;
        } catch (IOException e) {
            log.warn("Holland2Stay GraphQL I/O error: {}", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Integer intOf(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return (int) Math.round(v.asDouble());
        }
        try {
            return (int) Math.round(Double.parseDouble(v.asText().replaceAll("[^0-9.]", "")));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Best-effort read of a city-like attribute whose exact key/shape is not yet pinned. */
    private static String firstMatching(JsonNode item, String keyContains) {
        JsonNode direct = item.get(keyContains);
        if (direct != null && direct.isTextual()) {
            return direct.asText();
        }
        for (Iterator<String> it = item.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (name.toLowerCase().contains(keyContains)) {
                JsonNode v = item.get(name);
                if (v != null && v.isTextual() && !v.asText().isBlank()) {
                    return v.asText();
                }
                if (v != null && v.isObject() && v.has("label")) {
                    return v.get("label").asText();
                }
            }
        }
        return null;
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n));
    }
}

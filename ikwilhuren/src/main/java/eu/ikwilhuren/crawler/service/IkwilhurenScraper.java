package eu.ikwilhuren.crawler.service;

import eu.osmosis.crawler.core.http.CurlFetcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IkwilhurenScraper {

    private static final Logger log = LoggerFactory.getLogger(IkwilhurenScraper.class);
    static final String BASE_URL = "https://ikwilhuren.nu";
    public static final int DEFAULT_MAX_PAGES = 100;

    /** The site's own sort key for publication date, newest first. */
    static final String NEWEST_FIRST = "aanbodDESC";

    private static final Pattern POSTCODE_PATTERN = Pattern.compile("(\\d{4}\\s?[A-Z]{2})\\s+(.*)");
    private static final Pattern AREA_PATTERN = Pattern.compile("(\\d+)\\s*m");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");
    private static final Pattern STATUS_CLASS_PATTERN = Pattern.compile("bg-status-([a-z0-9-]+)");

    private static final Map<String, String> STATUS_MAP =
            Map.of(
                    "te-huur", "available",
                    "gereserveerd", "reserved",
                    "verhuurd-onder-voorbehoud", "rented",
                    "verhuurd", "rented",
                    "optieaanvraag", "optioned",
                    "optie", "optioned");

    private final CurlFetcher curlFetcher;

    public IkwilhurenScraper(CurlFetcher curlFetcher) {
        this.curlFetcher = curlFetcher;
    }

    public record FetchResult(List<ScrapedListing> listings, boolean complete) {}

    public FetchResult fetchAllListings() {
        return fetchAllListings(DEFAULT_MAX_PAGES);
    }

    public FetchResult fetchAllListings(int maxPages) {
        Map<String, ScrapedListing> bySlug = new LinkedHashMap<>();
        boolean complete = false;

        for (int page = 1; page <= maxPages; page++) {
            // Newest first, always. The quick poll only reads the first couple of pages, and the
            // site's default order is something else entirely — not one of the ten homes on the
            // default first page is among the ten most recently published. A brand-new home would
            // therefore have gone unseen until the hourly deep crawl, which is fatal here: MVGM caps
            // viewing requests per home, so a home you find an hour late is a home that has already
            // filled up.
            String url = BASE_URL + "/aanbod/?sort=" + NEWEST_FIRST + "&page=" + page;
            log.debug("Fetching page {}: {}", page, url);

            String html;
            try {
                html = curlFetcher.fetch(url);
            } catch (Exception e) {
                log.error("Failed to fetch page {}: {}", page, e.getMessage());
                if (page == 1) {
                    throw new CrawlerFetchException(
                            "Failed to fetch page 1: " + e.getMessage(), e);
                }
                break;
            }
            if (html == null || html.isBlank()) {
                complete = true;
                break;
            }

            Document doc = Jsoup.parse(html, BASE_URL);
            List<ScrapedListing> pageListings = parseCards(doc);
            if (pageListings.isEmpty()) {
                complete = true;
                break;
            }

            int before = bySlug.size();
            for (ScrapedListing l : pageListings) {
                bySlug.putIfAbsent(l.id(), l);
            }
            if (bySlug.size() == before) {
                // pagination looped or no new content
                complete = true;
                break;
            }
            if (!hasNextPage(doc, page)) {
                complete = true;
                break;
            }
        }

        log.info("Scraped {} total listings from ikwilhuren.nu", bySlug.size());
        return new FetchResult(new ArrayList<>(bySlug.values()), complete);
    }

    List<ScrapedListing> parseCards(Document doc) {
        List<ScrapedListing> out = new ArrayList<>();
        Elements cards = doc.select("div.card.card-woning");
        if (cards.isEmpty()) cards = doc.select(".col-sm-6 .card");
        for (Element card : cards) {
            try {
                ScrapedListing l = parseCard(card);
                if (l != null) out.add(l);
            } catch (Exception e) {
                log.warn("Failed to parse a card: {}", e.getMessage());
            }
        }
        return out;
    }

    private ScrapedListing parseCard(Element card) {
        Element link = card.selectFirst(".card-title a.stretched-link");
        if (link == null) link = card.selectFirst("a[href*=/object/]");
        if (link == null) return null;

        String href = link.attr("href");
        if (href.isBlank()) return null;
        String url = href.startsWith("http") ? href : BASE_URL + href;

        String title = link.text().trim();

        String[] parts = href.replaceAll("/+$", "").split("/");
        String slug = parts.length > 0 ? parts[parts.length - 1] : "";
        if (slug.isBlank()) return null;

        String postalCode = null;
        String city = null;
        for (Element span : card.select("div.card-body > span")) {
            String text = span.text().trim();
            Matcher m = POSTCODE_PATTERN.matcher(text);
            if (m.find()) {
                postalCode = m.group(1).replace(" ", "");
                city = m.group(2).trim();
                break;
            }
        }

        Integer price = null;
        Integer area = null;
        Integer bedrooms = null;
        Elements dotted = card.select("div.dotted-spans span");
        for (Element span : dotted) {
            String text = span.text().trim();
            String lower = text.toLowerCase();
            if (text.contains("€") || lower.contains("/mnd") || lower.contains("p/m")) {
                price = parseEuroPrice(text);
            } else if (lower.contains("m²") || lower.contains("m2") || lower.matches(".*\\d+\\s*m\\b.*")) {
                Matcher m = AREA_PATTERN.matcher(text);
                if (m.find()) area = Integer.parseInt(m.group(1));
            } else if (lower.contains("slaapkamer") || lower.contains("kamer")) {
                Matcher m = NUMBER_PATTERN.matcher(text);
                if (m.find()) bedrooms = Integer.parseInt(m.group(1));
            }
        }
        if (price == null) return null;

        String stage = "available";
        Element statusDot = card.selectFirst("span.status-dot[class*=bg-status-]");
        if (statusDot != null) {
            Matcher m = STATUS_CLASS_PATTERN.matcher(statusDot.className());
            if (m.find()) {
                String slugStatus = m.group(1);
                stage = STATUS_MAP.getOrDefault(slugStatus, slugStatus);
            }
        }

        String imageUrl = null;
        Element img = card.selectFirst("img");
        if (img != null) {
            String src = img.attr("src");
            if (src.startsWith("//")) src = "https:" + src;
            if (!src.isBlank()) imageUrl = src;
        }

        boolean nieuw =
                card.select("span").stream()
                        .anyMatch(s -> "Nieuw".equalsIgnoreCase(s.text().trim()));

        return new ScrapedListing(
                slug, slug, url, title, postalCode, city, price, area, bedrooms, stage, imageUrl,
                nieuw);
    }

    static Integer parseEuroPrice(String text) {
        // Examples: "€ 1.285,- /mnd", "€ 1.500,50 p/m", "€ 850,-", "€ 1285" (no thousands dot).
        // Match a whole digit run that may carry "." thousands separators, then an optional ",dd"
        // decimal. The old "[0-9]{1,3}(?:[.][0-9]{3})*" REQUIRED the dot, so a dot-less 4-digit price
        // like "€ 1285" captured only "128" -> 128 (ten times too cheap) -- in the auto-apply path
        // that scores the home as max-headroom and could spend a viewing slot on a mis-priced home.
        String cleaned = text.replace(" ", " ");
        Matcher m =
                Pattern.compile("€\\s*([0-9][0-9.]*(?:[,][0-9]{1,2})?)")
                        .matcher(cleaned);
        if (m.find()) {
            String num = m.group(1).replace(".", "").replace(",", ".");
            try {
                return (int) Double.parseDouble(num);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean hasNextPage(Document doc, int currentPage) {
        for (Element a : doc.select("a.btn.btn-primary, a.page-link")) {
            String text = a.text().toLowerCase();
            if (text.contains("volgende") || text.contains("next")) return true;
            String href = a.attr("href");
            Matcher m = Pattern.compile("page=(\\d+)").matcher(href);
            if (m.find() && Integer.parseInt(m.group(1)) > currentPage) return true;
        }
        return false;
    }
}

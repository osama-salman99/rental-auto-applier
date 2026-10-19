package eu.vesteda.crawler.service;

import eu.vesteda.crawler.model.Listing;
import eu.vesteda.crawler.repository.ListingRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the portal's search-profile page and folds what it says onto our listings.
 *
 * <p>Everything the apply flow needs that the public API cannot give us lives here: the object hash
 * (there is no other way to reach the request form), the rent the income rule is actually computed
 * from, whether the portal will still take a request, and its "many viewing requests" warning.
 *
 * <p>The whole catalogue — all 460-odd homes, every status — arrives on one page with no
 * pagination, which is a quiet gift: unlike the sibling crawler, there is no sort order that can
 * hide a new home behind page two, so a single poll always sees everything. It is 2.6 MB of HTML
 * but ~110 KB on the wire gzipped, which is cheap enough to poll on a tight loop.
 *
 * <p>Listings are matched to the API's rows on normalised address + city, which resolves all 460 of
 * them exactly (the portal HTML-escapes city names like {@code &#039;s-gravenhage}, so unescaping
 * before comparing is not optional). Matching this way means a portal home inherits the
 * {@code firstSeenAt} we have been tracking since long before auto-apply existed — which is the
 * clock the whole freshness score runs on.
 */
@Service
public class PortalListingService {

    private static final Logger log = LoggerFactory.getLogger(PortalListingService.class);

    static final String SEARCH_URL = PortalSession.BASE + "/zoekopdracht/";

    private static final Pattern HASH = Pattern.compile("/object/([0-9a-f]{32})/");
    private static final Pattern PRICE = Pattern.compile("Rental price:\\s*([\\d.,]+)");
    private static final Pattern BEDS = Pattern.compile("Amount of bedrooms:\\s*(\\d+)");
    private static final Pattern AREA = Pattern.compile("Living area:\\s*(\\d+)");
    /** The Dutch rendering of the same three sr-only labels. */
    private static final Pattern PRICE_NL = Pattern.compile("Huurprijs:\\s*([\\d.,]+)");
    private static final Pattern BEDS_NL = Pattern.compile("Aantal slaapkamers:\\s*(\\d+)");
    private static final Pattern AREA_NL = Pattern.compile("Woonoppervlakte:\\s*(\\d+)");

    private final PortalSession session;
    private final ListingRepository listingRepository;

    public PortalListingService(PortalSession session, ListingRepository listingRepository) {
        this.session = session;
        this.listingRepository = listingRepository;
    }

    /**
     * Pulls the portal catalogue and merges it into our listings. Returns true when something the
     * allocator cares about changed, so the caller can re-plan immediately rather than waiting for
     * the next timer.
     */
    @Transactional
    public boolean refresh() {
        List<PortalListing> portal = parse(session.getAuthenticated(SEARCH_URL, PortalSession.BASE + "/"));
        if (portal.isEmpty()) {
            log.warn("Portal search page yielded no listings — not touching what we have");
            return false;
        }

        Map<String, Listing> ours = new LinkedHashMap<>();
        for (Listing l : listingRepository.findAll()) {
            ours.put(key(l.getTitle(), l.getCity()), l);
        }

        boolean changed = false;
        int matched = 0;
        for (PortalListing p : portal) {
            Listing l = ours.get(key(p.address(), p.city()));
            if (l == null) {
                continue; // a home outside our target city; the API crawl never stored it
            }
            matched++;
            changed |= merge(l, p);
        }

        log.debug("Portal refresh: {} objects, {} matched to our listings", portal.size(), matched);
        return changed;
    }

    /** Folds one portal row onto a listing. Returns true if the allocator's inputs moved. */
    private boolean merge(Listing l, PortalListing p) {
        boolean changed = false;

        if (!p.objectHash().equals(l.getPortalHash())) {
            l.setPortalHash(p.objectHash());
            changed = true;
        }
        if (p.price() != null && !p.price().equals(l.getPortalPrice())) {
            l.setPortalPrice(p.price());
            changed = true;
        }
        String status = p.status().name();
        if (!status.equals(l.getPortalStatus())) {
            l.setPortalStatus(status);
            changed = true;
        }
        // Saturation is monotonic: a home that filled up does not un-fill. The warning is also
        // hidden from anyone who already holds a request on that home — we watched it vanish the
        // moment our own application landed — so treating its absence as "not crowded" would have
        // us re-rating a home upwards precisely because we applied to it.
        if (p.crowded() && !Boolean.TRUE.equals(l.getCrowded())) {
            l.setCrowded(true);
            changed = true;
        }

        if (changed) {
            listingRepository.save(l);
        }
        return changed;
    }

    static List<PortalListing> parse(String html) {
        Document doc = Jsoup.parse(html);
        List<PortalListing> out = new ArrayList<>();

        for (Element card : doc.select("div.card-result-list")) {
            Element link = card.selectFirst("a[href*=/object/]");
            if (link == null) {
                continue;
            }
            Matcher m = HASH.matcher(link.attr("href"));
            if (!m.find()) {
                continue;
            }
            Element cityEl = card.selectFirst("div.card-text");
            Element labelEl = card.selectFirst("div.card-image-label");

            String text = card.text();
            out.add(
                    new PortalListing(
                            m.group(1),
                            link.text().trim(),
                            cityEl == null ? "" : cityEl.text().trim(),
                            money(text),
                            intOf(text, BEDS, BEDS_NL),
                            intOf(text, AREA, AREA_NL),
                            PortalListing.Status.fromLabel(labelEl == null ? "" : labelEl.text()),
                            isCrowded(text)));
        }
        return out;
    }

    /** The portal's own warning, which it prints on the card itself. */
    private static boolean isCrowded(String text) {
        String t = text.toLowerCase();
        return t.contains("many viewing requests") || t.contains("veel bezichtigingsaanvragen");
    }

    /** "1.945,-" → 1945. Dutch thousands separator, comma for the (always empty) cents. */
    private static Integer money(String text) {
        Matcher m = PRICE.matcher(text);
        if (!m.find()) {
            m = PRICE_NL.matcher(text);
            if (!m.find()) {
                return null;
            }
        }
        String raw = m.group(1).replace(".", "");
        int comma = raw.indexOf(',');
        if (comma >= 0) {
            raw = raw.substring(0, comma);
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOf(String text, Pattern en, Pattern nl) {
        Matcher m = en.matcher(text);
        if (!m.find()) {
            m = nl.matcher(text);
            if (!m.find()) {
                return null;
            }
        }
        return Integer.valueOf(m.group(1));
    }

    /**
     * The join key between the portal and the public API. Jsoup has already unescaped the entities
     * the portal writes into city names; stripping everything but letters and digits absorbs the
     * rest of the disagreement ("Proeflaan 15 F" on the API, "Proeflaan 15F" on the portal).
     */
    static String key(String address, String city) {
        return norm(stripCity(address)) + "|" + norm(city);
    }

    /** Our listing titles are "Street 1 A, City"; the portal prints the address alone. */
    private static String stripCity(String title) {
        if (title == null) {
            return "";
        }
        int comma = title.lastIndexOf(',');
        return comma < 0 ? title : title.substring(0, comma);
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}

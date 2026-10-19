package eu.vesteda.crawler.service;

import eu.vesteda.crawler.model.CrawlerConfig;
import eu.vesteda.crawler.model.Listing;
import eu.vesteda.crawler.repository.CrawlerConfigRepository;
import eu.vesteda.crawler.repository.ListingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads the few facts that live only on a home's own detail page — service costs, the move-in date,
 * the energy label — none of which the search feed carries.
 *
 * <p>Those pages are fetched one at a time and there are 500-odd of them, so this never sweeps the
 * whole catalogue. It enriches only the homes actually worth showing — the ones inside budget the
 * user could apply for, plus anything already applied for — and only when their detail is missing or
 * has gone stale. That keeps it to a handful of requests on a slow timer, next to the search feed's
 * one call a minute.
 */
@Service
public class UnitDetailService {

    private static final Logger log = LoggerFactory.getLogger(UnitDetailService.class);

    /** Service costs and a move-in date barely move, so a day-stale copy is fine; refresh past that. */
    private static final Duration STALE_AFTER = Duration.ofHours(24);

    /** A ceiling on the work one pass will do, so a cold start can never fire hundreds of requests. */
    private static final int MAX_PER_PASS = 20;

    private final RestClient restClient;
    private final ListingRepository listingRepository;
    private final CrawlerConfigRepository configRepository;
    private final FilterService filterService;

    public UnitDetailService(
            RestClient restClient,
            ListingRepository listingRepository,
            CrawlerConfigRepository configRepository,
            FilterService filterService) {
        this.restClient = restClient;
        this.listingRepository = listingRepository;
        this.configRepository = configRepository;
        this.filterService = filterService;
    }

    /** One enrichment pass: read the detail page for the homes worth showing that need a refresh. */
    @Transactional
    public void enrichPending() {
        CrawlerConfig config = configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
        Integer ceiling = config.getRentCeiling();

        List<Listing> due =
                listingRepository.findAll().stream()
                        .filter(l -> l.getSlug() != null && !l.getSlug().isBlank())
                        .filter(l -> worthShowing(l, config, ceiling))
                        .filter(this::isStale)
                        // Never-fetched first, then the oldest, so a backlog drains fairly.
                        .sorted(Comparator.comparing(
                                l -> l.getDetailFetchedAt() == null ? Instant.EPOCH : l.getDetailFetchedAt()))
                        .limit(MAX_PER_PASS)
                        .toList();

        if (due.isEmpty()) {
            return;
        }
        int ok = 0;
        for (Listing l : due) {
            if (enrich(l)) {
                ok++;
            }
        }
        log.debug("Detail enrichment: {} of {} homes refreshed", ok, due.size());
    }

    /** The homes the user actually sees: in-budget applyable ones, plus anything already applied for. */
    private boolean worthShowing(Listing l, CrawlerConfig config, Integer ceiling) {
        if (l.isApplied()) {
            return true;
        }
        return l.getPortalHash() != null
                && l.portalAcceptsRequests()
                && !l.hasHardEligibilityGate()
                && filterService.matchesPreferences(l, config)
                && ceiling != null
                && l.rent() != null
                && l.rent() <= ceiling;
    }

    private boolean isStale(Listing l) {
        return l.getDetailFetchedAt() == null
                || Duration.between(l.getDetailFetchedAt(), Instant.now()).compareTo(STALE_AFTER) > 0;
    }

    /** Fetch and fold one home's detail page onto it. Returns true when the page was read and stored. */
    boolean enrich(Listing l) {
        String url = VestedaApiClient.BASE_URL + l.getSlug();
        try {
            String html = restClient.get()
                    .uri(url)
                    .header("Accept", "text/html")
                    .retrieve()
                    .body(String.class);
            if (html == null || html.isBlank()) {
                return false;
            }
            UnitDetail d = parse(html);
            l.setServiceCosts(d.serviceCosts());
            l.setAvailableFrom(d.availableFrom());
            l.setEnergyLabel(d.energyLabel());
            l.setDetailFetchedAt(Instant.now());
            listingRepository.save(l);
            return true;
        } catch (RestClientException e) {
            // One unreachable page must not stop the pass; it will be retried next time round.
            log.debug("Could not fetch detail page for {}: {}", l.getTitle(), e.getMessage());
            return false;
        }
    }

    /**
     * Pulls service costs, move-in date and energy label out of the detail page's definition list.
     * The page prints them as {@code <dt>label<dd>value} pairs; the labels are Dutch and some wrap the
     * label in a help link, so matching is on a contained keyword rather than an exact string.
     */
    static UnitDetail parse(String html) {
        Document doc = Jsoup.parse(html);
        Integer serviceCosts = null;
        String availableFrom = null;
        String energyLabel = null;

        for (Element dt : doc.select("dl.c-definition-list dt")) {
            Element dd = dt.nextElementSibling();
            if (dd == null || !"dd".equals(dd.tagName())) {
                continue;
            }
            String label = dt.text().trim().toLowerCase();
            String value = dd.text().trim();
            if (value.isEmpty()) {
                continue;
            }
            if (serviceCosts == null && label.contains("servicekosten")) {
                serviceCosts = money(value);
            } else if (availableFrom == null && label.contains("beschikbaar")) {
                availableFrom = value;
            } else if (energyLabel == null && label.contains("energielabel")) {
                energyLabel = value;
            }
        }
        return new UnitDetail(serviceCosts, availableFrom, energyLabel);
    }

    /** "€ 1.093,-" → 1093. Dutch thousands dot, comma for the (always empty) cents. */
    private static Integer money(String text) {
        String digits = text.replaceAll("[^0-9.,]", "");
        int comma = digits.indexOf(',');
        if (comma >= 0) {
            digits = digits.substring(0, comma);
        }
        digits = digits.replace(".", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The three facts the detail page adds, and nothing else. */
    public record UnitDetail(Integer serviceCosts, String availableFrom, String energyLabel) {}
}

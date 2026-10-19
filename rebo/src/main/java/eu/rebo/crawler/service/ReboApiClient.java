package eu.rebo.crawler.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class ReboApiClient {

    private static final Logger log = LoggerFactory.getLogger(ReboApiClient.class);

    static final String BASE_URL = "https://www.rebogroep.nl";
    private static final String API_BASE =
            "https://cms.rebogroep.nl/api/collections/private_objects_rent_sale/entries";
    private static final int PAGE_SIZE = 50;
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 10_000;

    private static final Map<String, String> STATUS_MAP = Map.of(
            "available", "available",
            "for_rent", "available",
            "on_option", "reserved",
            "rented", "rented",
            "sold", "sold",
            "subject_to_contract", "reserved");

    private final RestClient restClient;

    public ReboApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public List<ScrapedListing> fetchAllListings() {
        List<ScrapedListing> all = new ArrayList<>();
        int page = 1;
        int lastPage = 1;

        do {
            ApiResponse response = fetchPage(page);
            if (response == null || response.data() == null) break;

            if (response.meta() != null) {
                lastPage = response.meta().lastPage();
            }

            for (ApiEntry entry : response.data()) {
                if (!"rent".equals(typeKey(entry))) continue;
                ScrapedListing listing = mapEntry(entry);
                if (listing != null) all.add(listing);
            }

            page++;
        } while (page <= lastPage);

        log.info("Fetched {} rental listings from REBO API", all.size());
        return all;
    }

    /**
     * Two things here are load-bearing and neither is visible from the call site.
     *
     * <p>The URI must be built as a {@link URI}, not handed to {@code RestClient} as a String:
     * a String is treated as a URI template and re-encoded, so the {@code %5B} of
     * {@code filter[site:is]} becomes {@code %255B}. The CMS then silently ignores the filter and
     * serves entries from every locale — including stale ones, where a home that is "Onder optie"
     * on the Dutch site still reads {@code for_rent}. That is how a rented home shows as available.
     *
     * <p>Sorting is by {@code id} because every entry's {@code order} field is null: {@code
     * sort=order} is not a total order, so rows shift between pages and the walk drops some
     * entirely.
     */
    static URI pageUri(int page) {
        return URI.create(API_BASE
                + "?filter%5Bsite%3Ais%5D=nl"
                + "&limit=" + PAGE_SIZE
                + "&sort=id"
                + "&page=" + page);
    }

    private ApiResponse fetchPage(int page) {
        URI url = pageUri(page);
        RestClientException lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                log.warn("REBO API error, retrying (attempt {}/{}): {}",
                        attempt + 1, MAX_RETRIES + 1, lastException.getMessage());
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CrawlerFetchException("Interrupted while retrying REBO API", ie);
                }
            }
            try {
                return restClient.get()
                        .uri(url)
                        .header("Accept", "application/json")
                        .retrieve()
                        .body(ApiResponse.class);
            } catch (RestClientException e) {
                lastException = e;
            }
        }

        throw new CrawlerFetchException(
                "Failed to fetch REBO listings after " + (MAX_RETRIES + 1) + " attempts: "
                        + lastException.getMessage(),
                lastException);
    }

    private String typeKey(ApiEntry entry) {
        if (entry.type() == null) return null;
        return entry.type().get("key");
    }

    private ScrapedListing mapEntry(ApiEntry entry) {
        if (entry.id() == null) return null;

        String id = entry.id();
        String slug = entry.url();
        String title = entry.title();
        String postalCode = entry.postalCode() != null
                ? entry.postalCode().replace(" ", "")
                : null;
        String city = entry.city();
        Integer price = entry.rentalPrice();
        Double serviceCosts = entry.serviceCosts();
        Integer livingArea = entry.surfaceLiving();
        Integer bedrooms = entry.numberOfBedrooms();

        String statusKey = entry.statusMapped() != null ? entry.statusMapped().get("key") : null;
        String stage = STATUS_MAP.getOrDefault(statusKey, "unknown");

        String imageUrl = null;
        if (entry.mainImage() != null) {
            imageUrl = entry.mainImage().get("permalink");
        }

        return new ScrapedListing(id, slug, title, postalCode, city, price, serviceCosts,
                livingArea, bedrooms, stage, imageUrl);
    }

    // API model records

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiResponse(List<ApiEntry> data, ApiMeta meta) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiMeta(@JsonProperty("last_page") int lastPage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiEntry(
            String id,
            String slug,
            String title,
            String city,
            @JsonProperty("postal_code") String postalCode,
            String url,
            @JsonProperty("rental_price") Integer rentalPrice,
            @JsonProperty("service_costs") Double serviceCosts,
            @JsonProperty("surface_living") Integer surfaceLiving,
            @JsonProperty("number_of_bedrooms") Integer numberOfBedrooms,
            @JsonProperty("status_mapped") Map<String, String> statusMapped,
            Map<String, String> type,
            @JsonProperty("main_image") Map<String, String> mainImage) {}
}

package eu.vesteda.crawler.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class VestedaApiClient {

    private static final Logger log = LoggerFactory.getLogger(VestedaApiClient.class);

    static final String BASE_URL = "https://www.vesteda.com";
    private static final String SEARCH_URL = BASE_URL + "/api/units/search/facet";

    // Status codes from Vesteda's JS: ForRent=1, Rented=2, RentedWithReservation=3, Reserved=4, New=5
    private static final int STATUS_FOR_RENT = 1;
    private static final int STATUS_RENTED = 2;
    private static final int STATUS_RENTED_WITH_RESERVATION = 3;
    private static final int STATUS_RESERVED = 4;
    private static final int STATUS_NEW = 5;

    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 10_000;

    private final RestClient restClient;

    public VestedaApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public List<ScrapedListing> fetchAllListings() {
        SearchRequest request = new SearchRequest(
                0, 1, 20, "", "woning", 0.0, 0.0, List.of(), "[]", 500, 9999);

        RestClientException lastException = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                log.warn("Vesteda API error, retrying (attempt {}/{}): {}",
                        attempt + 1, MAX_RETRIES + 1, lastException.getMessage());
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CrawlerFetchException("Interrupted while retrying Vesteda API", ie);
                }
            }
            try {
                ApiResponse response = restClient.post()
                        .uri(SEARCH_URL)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("Referer", BASE_URL + "/en/unit-search")
                        .body(request)
                        .retrieve()
                        .body(ApiResponse.class);

                if (response == null || response.results() == null || response.results().objects() == null) {
                    throw new CrawlerFetchException("Empty response from Vesteda API", null);
                }

                List<ScrapedListing> listings = response.results().objects().stream()
                        .map(this::mapUnit)
                        .toList();

                log.info("Fetched {} listings from Vesteda API", listings.size());
                return listings;
            } catch (RestClientException e) {
                lastException = e;
            }
        }

        throw new CrawlerFetchException(
                "Failed to fetch Vesteda listings after " + (MAX_RETRIES + 1) + " attempts: "
                        + lastException.getMessage(),
                lastException);
    }

    private ScrapedListing mapUnit(ApiUnit unit) {
        String id = String.valueOf(unit.id());

        StringBuilder titleBuilder = new StringBuilder();
        titleBuilder.append(unit.street()).append(" ").append(unit.houseNumber());
        if (unit.houseNumberAddition() != null && !unit.houseNumberAddition().isBlank()) {
            titleBuilder.append(" ").append(unit.houseNumberAddition());
        }
        titleBuilder.append(", ").append(unit.city());
        String title = titleBuilder.toString();

        String stage = switch (unit.status()) {
            case STATUS_FOR_RENT, STATUS_NEW -> "available";
            case STATUS_RENTED, STATUS_RENTED_WITH_RESERVATION -> "rented";
            case STATUS_RESERVED -> "reserved";
            default -> "unknown";
        };

        return new ScrapedListing(
                id,
                unit.url(),
                title,
                unit.postalCode(),
                unit.city(),
                (int) Math.round(unit.priceUnformatted()),
                unit.size(),
                unit.numberOfBedRooms(),
                stage,
                unit.imageSmall(),
                unit.prioritizeKeyProfessions(),
                unit.onlySixtyFivePlus(),
                unit.priorityArrangement(),
                unit.onlyMiddleRent(),
                unit.ageFrom(),
                unit.entitysubtypelabel());
    }

    // API model records

    record SearchRequest(
            int placeType,
            int sortType,
            int radius,
            String s,
            String sc,
            double latitude,
            double longitude,
            List<Integer> filters,
            String filterRequestJson,
            int priceFrom,
            int priceTo) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiResponse(List<Object> facets, ApiResults results, int count) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiResults(List<ApiUnit> objects, int objectsCount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiUnit(
            int id,
            String vgoNumber,
            String street,
            String houseNumber,
            String houseNumberAddition,
            String postalCode,
            String city,
            String complex,
            int size,
            double priceUnformatted,
            int numberOfBedRooms,
            String imageSmall,
            int status,
            String url,
            // Target-group flags. onlyMiddleRent / onlySixtyFivePlus / prioritizeKeyProfessions are
            // always present as booleans; priorityArrangement is often JSON null, so it is the boxed
            // Boolean. ageFrom is 0 when there is no minimum; entitysubtypelabel carries "Zorgwoning".
            boolean onlyMiddleRent,
            boolean onlySixtyFivePlus,
            boolean prioritizeKeyProfessions,
            Boolean priorityArrangement,
            int ageFrom,
            String entitysubtypelabel) {}
}

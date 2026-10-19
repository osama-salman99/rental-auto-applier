package eu.roofz.crawler.service;

import eu.roofz.crawler.model.api.ApiListing;
import eu.roofz.crawler.model.api.ApiProject;
import eu.roofz.crawler.model.api.ApiProjectListResponse;
import eu.roofz.crawler.model.api.ApiResponse;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class RoofzApiClient {

    private static final Logger log = LoggerFactory.getLogger(RoofzApiClient.class);
    private static final String BASE_URL = "https://roofz.eu/api/ms/listing/properties";
    private static final String PROJECTS_URL = "https://roofz.eu/api/ms/listing/projects";
    private static final int MAX_PAGES = 20;
    private static final int PER_PAGE = 50;
    /**
     * Roofz exposes one dummy listing per city under the "zoekprofiel-placeholder" project so
     * their internal search-profile feature has a target. They aren't real properties — exclude
     * them from every downstream path (listings table, stats, notifications, prune).
     */
    private static final String PLACEHOLDER_PROJECT_SLUG = "zoekprofiel-placeholder";

    private final RestClient restClient;

    public RoofzApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public List<ApiListing> fetchAllListings(String targetCity) {
        List<ApiListing> allListings = new ArrayList<>();

        for (int page = 1; page <= MAX_PAGES; page++) {
            UriComponentsBuilder builder =
                    UriComponentsBuilder.fromUriString(BASE_URL)
                            .queryParam("page", page)
                            .queryParam("perPage", PER_PAGE);
            if (targetCity != null && !targetCity.isBlank()) {
                builder.queryParam("filter[location]", targetCity);
            }
            String uri = builder.build().toUriString();
            log.debug("Fetching {}", uri);

            ApiResponse response = restClient.get().uri(uri).retrieve().body(ApiResponse.class);

            if (response == null || response.data() == null || response.data().isEmpty()) {
                break;
            }
            int pageSize = response.data().size();
            for (ApiListing l : response.data()) {
                if (isPlaceholder(l)) continue;
                allListings.add(l);
            }
            if (pageSize < PER_PAGE) {
                break;
            }
        }

        log.debug(
                "Fetched {} listings from API (city filter: {})",
                allListings.size(),
                targetCity == null || targetCity.isBlank() ? "<none>" : targetCity);
        return allListings;
    }

    private static boolean isPlaceholder(ApiListing l) {
        return l.project() != null
                && PLACEHOLDER_PROJECT_SLUG.equals(l.project().slug());
    }

    public List<ApiProject> fetchAllProjects() {
        String uri =
                UriComponentsBuilder.fromUriString(PROJECTS_URL)
                        .queryParam("perPage", 100)
                        .build()
                        .toUriString();
        ApiProjectListResponse response =
                restClient.get().uri(uri).retrieve().body(ApiProjectListResponse.class);
        List<ApiProject> projects =
                response == null || response.data() == null ? List.of() : response.data();
        log.debug("Fetched {} projects from API", projects.size());
        return projects;
    }
}

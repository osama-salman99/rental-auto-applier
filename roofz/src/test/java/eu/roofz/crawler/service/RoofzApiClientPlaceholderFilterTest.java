package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import eu.roofz.crawler.model.api.ApiListing;
import eu.roofz.crawler.model.api.ApiProject;
import eu.roofz.crawler.model.api.ApiResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class RoofzApiClientPlaceholderFilterTest {

    @Test
    void dropsListingsWhoseProjectSlugIsZoekprofielPlaceholder() {
        // Stub a RestClient that returns one real listing and one placeholder.
        RestClient.RequestHeadersUriSpec<?> uriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        RestClient client = mock(RestClient.class);

        ApiProject placeholder = new ApiProject(
                42L, "zoekprofiel-placeholder", "Zoekprofiel placeholder", null, false, null, null, null, false);
        ApiProject realProject = new ApiProject(
                2631L, "roofz", "Roofz", null, false, null, null, null, true);

        ApiListing real = new ApiListing(
                1L, "real", "Real", "available",
                null, null, null, null, null, realProject, null, null, null, null, null, null);
        ApiListing dummy = new ApiListing(
                2L, "amsterdam-placeholder", "Amsterdam placeholder", "occupied",
                null, null, null, null, null, placeholder, null, null, null, null, null, null);
        ApiListing emptyResponse = null; // unused; just for clarity

        ApiResponse page1 = new ApiResponse(List.of(real, dummy));
        ApiResponse page2 = new ApiResponse(List.of()); // signals end

        when(client.get()).thenReturn((RestClient.RequestHeadersUriSpec) uriSpec);
        when(uriSpec.uri(any(String.class))).thenReturn((RestClient.RequestHeadersSpec) headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(eq(ApiResponse.class)))
                .thenReturn(page1)   // first page contains real + placeholder
                .thenReturn(page2);  // second page is empty, loop terminates

        RoofzApiClient api = new RoofzApiClient(client);
        List<ApiListing> result = api.fetchAllListings("Amsterdam");

        assertThat(result).extracting(ApiListing::id).containsExactly(1L);
    }
}

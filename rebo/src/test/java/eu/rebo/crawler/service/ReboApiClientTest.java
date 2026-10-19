package eu.rebo.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ReboApiClientTest {

    private static final String PAGE_1_URI =
            "https://cms.rebogroep.nl/api/collections/private_objects_rent_sale/entries"
                    + "?filter%5Bsite%3Ais%5D=nl&limit=50&sort=id&page=1";

    private static final String EMPTY_PAGE = """
            {"data": [], "meta": {"last_page": 1}}
            """;

    private static final String ONE_HOME_ON_OPTION = """
            {"data": [{
               "id": "ea7ec824",
               "title": "Markengouw 259, 1024 EB, Amsterdam",
               "city": "Amsterdam",
               "url": "/nl/aanbod/markengouw-259",
               "rental_price": 1950,
               "type": {"key": "rent"},
               "status_mapped": {"key": "on_option", "label": "Onder optie"}
            }], "meta": {"last_page": 1}}
            """;

    /**
     * The CMS ignores a query parameter it does not recognise instead of rejecting it, so a mangled
     * filter never surfaces as an error — it just quietly serves every locale, including stale ones.
     * Nothing downstream can detect that, so the exact request URI is the only place to catch it.
     */
    @Test
    void asksTheCmsForTheDutchSiteOnly() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URI.create(PAGE_1_URI)))
                .andRespond(withSuccess(EMPTY_PAGE, MediaType.APPLICATION_JSON));

        new ReboApiClient(builder.build()).fetchAllListings();

        server.verify();
    }

    @Test
    void treatsAHomeUnderOptionAsUnavailable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URI.create(PAGE_1_URI)))
                .andRespond(withSuccess(ONE_HOME_ON_OPTION, MediaType.APPLICATION_JSON));

        List<ScrapedListing> listings = new ReboApiClient(builder.build()).fetchAllListings();

        assertThat(listings).singleElement()
                .extracting(ScrapedListing::id, ScrapedListing::stage)
                .containsExactly("ea7ec824", "reserved");
    }
}

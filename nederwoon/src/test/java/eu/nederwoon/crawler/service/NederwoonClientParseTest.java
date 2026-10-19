package eu.nederwoon.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link NederwoonClient#parseCard} against a captured live /search?city=Amsterdam snapshot
 * (src/test/resources/nederwoon-search-sample.html, grabbed 2026-07-30). NederWoon's parse is the
 * fragile one — it reaches the anchor's ancestor row via closest()/parent(), reads area/rooms/price
 * off the row text, and takes the LARGEST euro on the row as the rent — so a regression guard here
 * is worth more than most. Hardens this observe crawler's parse layer before it is armed.
 */
class NederwoonClientParseTest {

    private static List<ScrapedListing> parseAll() throws IOException {
        try (InputStream in =
                NederwoonClientParseTest.class.getResourceAsStream("/nederwoon-search-sample.html")) {
            assertThat(in).as("fixture on classpath").isNotNull();
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Document doc = Jsoup.parse(html, NederwoonClient.BASE_URL);
            NederwoonClient client = new NederwoonClient(null, "curl", "ua"); // curl never invoked
            return doc.select("a.see-page-button").stream()
                    .map(client::parseCard)
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    @Test
    void extractsCardsWithStableFields() throws IOException {
        List<ScrapedListing> all = parseAll();
        assertThat(all).isNotEmpty();
        assertThat(all).allSatisfy(l -> {
            assertThat(l.id()).isNotBlank();
            assertThat(l.title()).isNotBlank();
            assertThat(l.city()).isNotBlank();
            assertThat(l.stage()).isEqualTo("available");
            if (l.price() != null) {
                assertThat(l.price()).as("rent for %s", l.id()).isBetween(300, 10000);
            }
            if (l.livingArea() != null) {
                assertThat(l.livingArea()).as("area for %s", l.id()).isPositive();
            }
        });
    }

    @Test
    void parsesTheDiemenCardFieldsFromItsRow() throws IOException {
        ScrapedListing diemen = parseAll().stream()
                .filter(l -> l.id().equals("38283"))
                .findFirst()
                .orElseThrow();
        // city + postcode come from the p.color-medium "1113LC Diemen" override, area/rooms from the ul.
        assertThat(diemen.city()).isEqualTo("Diemen");
        assertThat(diemen.postalCode()).isEqualTo("1113LC");
        assertThat(diemen.livingArea()).isEqualTo(96);
        assertThat(diemen.bedrooms()).isEqualTo(3);
    }

    @Test
    void readsRentFromTheRowWhenPresent() throws IOException {
        // At least one card on the page carries a rent (e.g. € 1.950,00); maxEuro must pick it up.
        assertThat(parseAll()).anySatisfy(l -> assertThat(l.price()).isNotNull().isBetween(300, 10000));
    }
}

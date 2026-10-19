package eu.vbt.crawler.service;

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
 * Pins {@link VbtClient#parseCard} against a captured snapshot of a real vb&t /woningen page
 * (src/test/resources/vbt-woningen-sample.html, 6 cards, grabbed 2026-07-30). Guards the observe
 * crawler's parse layer against silent regressions before it is armed — in particular that the
 * rent comes from div.price (€ 1.775 → 1775) and NOT the servicekosten / parking euros elsewhere
 * on the card, which is the kind of mis-cost that would matter the day auto-apply is enabled.
 */
class VbtClientParseTest {

    private static Document fixture() throws IOException {
        try (InputStream in = VbtClientParseTest.class.getResourceAsStream("/vbt-woningen-sample.html")) {
            assertThat(in).as("fixture on classpath").isNotNull();
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Jsoup.parse(html, VbtClient.BASE_URL);
        }
    }

    private static List<ScrapedListing> parseAll() throws IOException {
        VbtClient client = new VbtClient("curl", "ua"); // curl is never invoked in this test
        return fixture().select("a.property").stream()
                .map(client::parseCard)
                .filter(Objects::nonNull)
                .toList();
    }

    @Test
    void extractsEveryCardWithSaneFields() throws IOException {
        List<ScrapedListing> all = parseAll();
        assertThat(all).hasSize(6);
        assertThat(all).allSatisfy(l -> {
            assertThat(l.id()).isNotBlank();
            assertThat(l.title()).isNotBlank();
            assertThat(l.city()).isNotBlank();
            assertThat(l.price()).as("rent for %s", l.id()).isBetween(500, 5000);
            assertThat(l.livingArea()).as("area for %s", l.id()).isPositive();
            assertThat(l.bedrooms()).as("rooms for %s", l.id()).isPositive();
        });
    }

    @Test
    void firstCardParsesRentFromPriceNotServicekosten() throws IOException {
        ScrapedListing first = parseAll().get(0);
        assertThat(first.id()).isEqualTo("badhoevedorp-oleanderlaan-218");
        assertThat(first.city()).isEqualTo("Badhoevedorp");
        assertThat(first.title()).isEqualTo("Oleanderlaan 218");
        assertThat(first.price()).isEqualTo(1775); // € 1.775,- rent, not € 70 servicekosten / € 75 parking
        assertThat(first.livingArea()).isEqualTo(85);
        assertThat(first.bedrooms()).isEqualTo(3);
        assertThat(first.stage()).isEqualTo("available");
    }

    @Test
    void thousandsSeparatedPricesParseAcrossCities() throws IOException {
        List<ScrapedListing> all = parseAll();
        assertThat(all).anySatisfy(l -> {
            assertThat(l.city()).isEqualTo("Diemen");
            assertThat(l.price()).isEqualTo(2111); // € 2.111,-
        });
        assertThat(all).anySatisfy(l -> {
            assertThat(l.id()).isEqualTo("eindhoven-willemstraat-91d");
            assertThat(l.price()).isEqualTo(1220);
        });
    }
}

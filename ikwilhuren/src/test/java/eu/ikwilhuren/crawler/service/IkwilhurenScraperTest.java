package eu.ikwilhuren.crawler.service;

import eu.osmosis.crawler.core.http.CurlFetcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

class IkwilhurenScraperTest {

    private static final String CARD_HTML =
            "<div class='card card-woning'>"
                    + "<div class='card-img-top'>"
                    + "<span class='status-dot bg-status-te-huur'></span>"
                    + "<img src='//a.static.nbo.nl/media/abc.jpg' />"
                    + "</div>"
                    + "<div class='card-body'>"
                    + "<span class='card-title h5'>"
                    + "<a class='stretched-link' href='/object/amsterdam-1018dh-50-a6-plantage-7fc/'>"
                    + "Appartement Plantage Middenlaan 50"
                    + "</a></span>"
                    + "<span>1018DH Amsterdam</span>"
                    + "<div class='dotted-spans'>"
                    + "<span class='fw-bold'>€ 1.615,- /mnd</span>"
                    + "<span>60 m<sup>2</sup></span>"
                    + "<span>2 slaapkamers</span>"
                    + "</div></div></div>";

    private final IkwilhurenScraper scraper = new IkwilhurenScraper((CurlFetcher) null);

    @Test
    void parseCard_extractsAllFields() {
        Document doc = Jsoup.parse("<html><body>" + CARD_HTML + "</body></html>");
        List<ScrapedListing> out = scraper.parseCards(doc);
        assertEquals(1, out.size());
        ScrapedListing l = out.get(0);
        assertEquals("amsterdam-1018dh-50-a6-plantage-7fc", l.id());
        assertEquals(
                "https://ikwilhuren.nu/object/amsterdam-1018dh-50-a6-plantage-7fc/", l.url());
        assertEquals("Appartement Plantage Middenlaan 50", l.title());
        assertEquals("1018DH", l.postalCode());
        assertEquals("Amsterdam", l.city());
        assertEquals(1615, l.price());
        assertEquals(60, l.livingArea());
        assertEquals(2, l.bedrooms());
        assertEquals("available", l.stage());
        assertNotNull(l.imageUrl());
        assertTrue(l.imageUrl().startsWith("https:"));
        assertFalse(l.nieuw());
    }

    @Test
    void parseCard_detectsNieuwBadge() {
        String html = CARD_HTML.replace(
                "<span>1018DH Amsterdam</span>",
                "<span>1018DH Amsterdam</span><span>Nieuw</span>");
        Document doc = Jsoup.parse("<html><body>" + html + "</body></html>");
        ScrapedListing l = scraper.parseCards(doc).get(0);
        assertTrue(l.nieuw());
    }

    @Test
    void parseCard_mapsRentedStatus() {
        String html = CARD_HTML.replace("bg-status-te-huur", "bg-status-verhuurd");
        Document doc = Jsoup.parse("<html><body>" + html + "</body></html>");
        ScrapedListing l = scraper.parseCards(doc).get(0);
        assertEquals("rented", l.stage());
    }

    @Test
    void parseEuroPrice_handlesDutchFormat() {
        assertEquals(1285, IkwilhurenScraper.parseEuroPrice("€ 1.285,- /mnd"));
        assertEquals(1500, IkwilhurenScraper.parseEuroPrice("€ 1.500,50 p/m"));
        assertEquals(850, IkwilhurenScraper.parseEuroPrice("€ 850,-"));
        // A 4-digit price with no thousands separator must not be truncated to 3 digits: "€ 1285"
        // is 1285, not 128. The old regex captured only "128", ten times too cheap.
        assertEquals(1285, IkwilhurenScraper.parseEuroPrice("€ 1285"));
        assertEquals(1285, IkwilhurenScraper.parseEuroPrice("€ 1285 /mnd"));
    }
}

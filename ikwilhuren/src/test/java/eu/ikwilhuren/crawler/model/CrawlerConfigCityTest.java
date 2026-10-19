package eu.ikwilhuren.crawler.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The city filter drives both what we apply for and — via the purge — what gets deleted, so a wrong
 * answer here either narrows the search to nothing or wipes the store. It must read a single city, a
 * comma-separated list, and "everywhere" all correctly.
 */
class CrawlerConfigCityTest {

    private CrawlerConfig withCity(String city) {
        CrawlerConfig c = new CrawlerConfig();
        c.setTargetCity(city);
        return c;
    }

    @Test
    void aSingleCityStillWorksAsBefore() {
        CrawlerConfig c = withCity("Amsterdam");
        assertThat(c.matchesCity("Amsterdam")).isTrue();
        assertThat(c.matchesCity("amsterdam")).isTrue(); // case-insensitive
        assertThat(c.matchesCity("Diemen")).isFalse();
        assertThat(c.getTargetCities()).containsExactly("Amsterdam");
    }

    @Test
    void aCommaSeparatedListMatchesAnyOfTheCities() {
        CrawlerConfig c = withCity("Amsterdam, Diemen, Amstelveen");
        assertThat(c.matchesCity("Amsterdam")).isTrue();
        assertThat(c.matchesCity("Diemen")).isTrue();
        assertThat(c.matchesCity("amstelveen")).isTrue();
        assertThat(c.matchesCity("Haarlem")).isFalse();
        assertThat(c.getTargetCities()).containsExactly("Amsterdam", "Diemen", "Amstelveen");
    }

    @Test
    void whitespaceAndBlankEntriesAreTolerated() {
        CrawlerConfig c = withCity("  Amsterdam ,, Diemen  ");
        assertThat(c.getTargetCities()).containsExactly("Amsterdam", "Diemen");
        assertThat(c.matchesCity(" Diemen ")).isTrue(); // listing city is trimmed too
    }

    @Test
    void aBlankFieldMeansEverywhere() {
        assertThat(withCity("").matchesCity("Anywhere")).isTrue();
        assertThat(withCity("   ").matchesCity("Anywhere")).isTrue();
        assertThat(withCity(null).matchesCity("Anywhere")).isTrue();
        assertThat(withCity("").getTargetCities()).isEmpty();
    }

    @Test
    void aNullListingCityMatchesNothingWhenCitiesAreSet() {
        assertThat(withCity("Amsterdam").matchesCity(null)).isFalse();
    }
}

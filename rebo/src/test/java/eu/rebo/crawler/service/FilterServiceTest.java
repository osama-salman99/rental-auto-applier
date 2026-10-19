package eu.rebo.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;

import eu.rebo.crawler.model.CrawlerConfig;
import org.junit.jupiter.api.Test;

class FilterServiceTest {

    private final FilterService filter = new FilterService();

    /** income 6000/mo → ceiling 1500; + 75 boundary margin → matches up to gross 1575. */
    private CrawlerConfig configWithIncome() {
        CrawlerConfig c = new CrawlerConfig();
        c.setTargetCity("Amsterdam");
        c.setIncomeMonthlyGross(6000);
        c.setIncomeType("Employer");
        c.setOnlyAvailable(true);
        return c;
    }

    private ScrapedListing home(int base, double service) {
        return new ScrapedListing("id", "/slug", "Home", "1000 AA", "Amsterdam",
                base, service, 80, 3, "available", null);
    }

    @Test
    void matchesAHomeWithinTheIncomeCeiling() {
        assertThat(filter.matches(home(1500, 50), configWithIncome())).isTrue(); // gross 1550
    }

    @Test
    void rejectsAHomeAboveTheIncomeCeilingPlusMargin() {
        assertThat(filter.matches(home(1550, 50), configWithIncome())).isFalse(); // gross 1600
    }

    @Test
    void countsServiceCostsSoBaseRentAloneCannotSlipThrough() {
        // Base 1500 alone would clear a €1700 Max rent, but service costs push gross to 1600 > ceiling.
        CrawlerConfig c = configWithIncome();
        c.setMaxPrice(1700);
        assertThat(filter.matches(home(1500, 100), c)).isFalse();
    }

    @Test
    void maxRentStillTightensWhenSetBelowTheCeiling() {
        CrawlerConfig c = configWithIncome();
        c.setMaxPrice(1200); // stricter than the ceiling
        assertThat(filter.matches(home(1250, 0), c)).isFalse(); // gross 1250 under ceiling, but base > maxPrice
    }

    @Test
    void withoutIncomeFallsBackToMaxRentOnBaseRent() {
        CrawlerConfig c = new CrawlerConfig();
        c.setTargetCity("Amsterdam");
        c.setMaxPrice(1500);
        c.setOnlyAvailable(true);
        assertThat(filter.matches(home(1400, 200), c)).isTrue();  // base 1400 <= 1500, service ignored
        assertThat(filter.matches(home(1600, 0), c)).isFalse();   // base 1600 > 1500
    }
}

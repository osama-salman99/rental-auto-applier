package eu.vesteda.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class UnitDetailParseTest {

    private static String fixture(String name) throws IOException {
        return new String(
                new ClassPathResource("unit/" + name).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    /**
     * The three facts the search feed cannot give us, read off a real (public) detail page: the
     * service costs charged on top of the rent, the move-in date, and the energy label.
     */
    @Test
    void readsServiceCostsMoveInDateAndEnergyLabel() throws IOException {
        UnitDetailService.UnitDetail d = UnitDetailService.parse(fixture("unit-detail.html"));

        assertThat(d.serviceCosts()).isEqualTo(85); // "€ 85,-" — the euro sign and ",-" stripped
        assertThat(d.availableFrom()).isEqualTo("13 augustus 2026");
        assertThat(d.energyLabel()).isEqualTo("A++");
    }

    /** A page missing the block must yield nulls, not throw — enrichment simply leaves the row as is. */
    @Test
    void aPageWithoutADefinitionListYieldsNulls() {
        UnitDetailService.UnitDetail d = UnitDetailService.parse("<html><body><p>Sold</p></body></html>");
        assertThat(d.serviceCosts()).isNull();
        assertThat(d.availableFrom()).isNull();
        assertThat(d.energyLabel()).isNull();
    }
}

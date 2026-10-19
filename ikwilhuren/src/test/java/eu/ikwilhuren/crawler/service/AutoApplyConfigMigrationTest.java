package eu.ikwilhuren.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.ikwilhuren.crawler.model.CrawlerConfig;
import eu.ikwilhuren.crawler.repository.CrawlerConfigRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The income is personal, so the code carries none: it comes from the settings page, or from
 * {@code IKWILHUREN_SEED_INCOME} when the service starts. The figures here are made up.
 */
class AutoApplyConfigMigrationTest {

    private final CrawlerConfigRepository repo = mock(CrawlerConfigRepository.class);

    @Test
    void aFreshInstallHasNoIncomeAndSoNoCeiling() {
        CrawlerConfig config = CrawlerConfig.defaults();

        assertThat(config.getGrossMonthlyIncome()).isNull();
        assertThat(config.getRentCeiling()).isNull();
    }

    @Test
    void withoutASeedAFreshDatabaseIsLeftAlone() {
        when(repo.findById(1L)).thenReturn(Optional.empty());

        new AutoApplyConfigMigration(repo, null).run(null);

        verify(repo, never()).save(any());
    }

    @Test
    void theSeedGivesAFreshDatabaseItsIncome() {
        when(repo.findById(1L)).thenReturn(Optional.empty());

        new AutoApplyConfigMigration(repo, 4900).run(null);

        ArgumentCaptor<CrawlerConfig> saved = ArgumentCaptor.forClass(CrawlerConfig.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getGrossMonthlyIncome()).isEqualTo(4900);
        assertThat(saved.getValue().getRentCeiling()).isEqualTo(1400); // 4900 × 12 / 42
    }

    @Test
    void theSeedFillsAnEmptyStoredIncome() {
        CrawlerConfig stored = CrawlerConfig.defaults();
        when(repo.findById(1L)).thenReturn(Optional.of(stored));

        new AutoApplyConfigMigration(repo, 4900).run(null);

        assertThat(stored.getGrossMonthlyIncome()).isEqualTo(4900);
        verify(repo).save(stored);
    }

    @Test
    void theSeedNeverReplacesAnIncomeSetOnTheSettingsPage() {
        CrawlerConfig stored = CrawlerConfig.defaults();
        stored.setGrossMonthlyIncome(3800);
        when(repo.findById(1L)).thenReturn(Optional.of(stored));

        new AutoApplyConfigMigration(repo, 4900).run(null);

        assertThat(stored.getGrossMonthlyIncome()).isEqualTo(3800);
        verify(repo, never()).save(any());
    }
}

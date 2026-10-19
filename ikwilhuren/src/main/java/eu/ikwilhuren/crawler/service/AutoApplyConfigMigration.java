package eu.ikwilhuren.crawler.service;

import eu.ikwilhuren.crawler.model.CrawlerConfig;
import eu.ikwilhuren.crawler.repository.CrawlerConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backfills the auto-apply settings on the pre-existing config row.
 *
 * <p>Hibernate's {@code ddl-auto: update} adds the new columns as NULL, which would leave the saved
 * row with no income ceiling — and an unset ceiling is precisely the failure this feature exists to
 * prevent, since a viewing request above it cannot be allocated yet still burns one of the five
 * slots. Field initialisers only apply to brand-new rows, so the stored one needs filling in.
 *
 * <p>The income is personal, so it has no built-in value. It is set on the settings page, or seeded
 * from {@code ikwilhuren.seed.income} ({@code IKWILHUREN_SEED_INCOME}) while the stored value is
 * empty; with neither, nothing is applied to.
 */
@Component
public class AutoApplyConfigMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AutoApplyConfigMigration.class);

    private final CrawlerConfigRepository configRepository;
    private final Integer seedIncome;

    public AutoApplyConfigMigration(
            CrawlerConfigRepository configRepository,
            @Value("${ikwilhuren.seed.income:#{null}}") Integer seedIncome) {
        this.configRepository = configRepository;
        this.seedIncome = seedIncome;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        CrawlerConfig stored = configRepository.findById(1L).orElse(null);
        if (stored == null) {
            if (seedIncome == null) {
                return; // a fresh row gets the defaults from the field initialisers
            }
            stored = CrawlerConfig.defaults(); // a fresh database, but with an income to start from
        }

        CrawlerConfig defaults = CrawlerConfig.defaults();
        boolean changed = false;

        if (stored.getAutoApplyEnabled() == null) {
            stored.setAutoApplyEnabled(defaults.getAutoApplyEnabled());
            changed = true;
        }
        if (stored.getAutoApplyDryRun() == null) {
            stored.setAutoApplyDryRun(defaults.getAutoApplyDryRun());
            changed = true;
        }
        if (stored.getAutoWithdrawEnabled() == null) {
            stored.setAutoWithdrawEnabled(defaults.getAutoWithdrawEnabled());
            changed = true;
        }
        if (stored.getMaxActiveRequests() == null) {
            stored.setMaxActiveRequests(defaults.getMaxActiveRequests());
            changed = true;
        }
        if (stored.getGrossMonthlyIncome() == null && seedIncome != null) {
            stored.setGrossMonthlyIncome(seedIncome);
            changed = true;
        }
        if (stored.getIncomeMultiplier() == null) {
            stored.setIncomeMultiplier(defaults.getIncomeMultiplier());
            changed = true;
        }
        if (stored.getParkingPreference() == null) {
            stored.setParkingPreference(defaults.getParkingPreference());
            changed = true;
        }
        if (stored.getSkipCrowdedListings() == null) {
            stored.setSkipCrowdedListings(defaults.getSkipCrowdedListings());
            changed = true;
        }

        if (changed) {
            configRepository.save(stored);
            log.info(
                    "Backfilled auto-apply config: enabled={}, dryRun={}, autoWithdraw={}, rent ceiling EUR {}",
                    stored.isAutoApplyEnabled(),
                    stored.isAutoApplyDryRun(),
                    stored.isAutoWithdrawEnabled(),
                    stored.getRentCeiling());
        }
    }
}

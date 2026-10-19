package eu.vesteda.crawler.service;

import eu.vesteda.crawler.model.CrawlerConfig;
import eu.vesteda.crawler.repository.CrawlerConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bootstraps auto-apply settings from properties, so the system can be configured — and armed —
 * from the VPS without the dashboard password, and without the user's income ever entering git.
 *
 * <p>Income is seeded once: it fills the stored value only while it is still blank, so the settings
 * page stays authoritative afterwards. The enable and dry-run switches, by contrast, are applied
 * whenever their property is present, because that is the mechanism for arming the system: set the
 * property, restart, confirm, then remove the property so the dashboard owns the switch again.
 *
 * <p>All three properties default to absent, so on an ordinary boot this runner does nothing.
 */
@Component
public class AutoApplyConfigSeed implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AutoApplyConfigSeed.class);

    private final CrawlerConfigRepository configRepository;
    private final Integer seedIncome;
    private final Integer seedSavings;
    private final Boolean seedEnabled;
    private final Boolean seedDryRun;

    public AutoApplyConfigSeed(
            CrawlerConfigRepository configRepository,
            @Value("${vesteda.seed.income:#{null}}") Integer seedIncome,
            @Value("${vesteda.seed.savings:#{null}}") Integer seedSavings,
            @Value("${vesteda.seed.enabled:#{null}}") Boolean seedEnabled,
            @Value("${vesteda.seed.dry-run:#{null}}") Boolean seedDryRun) {
        this.configRepository = configRepository;
        this.seedIncome = seedIncome;
        this.seedSavings = seedSavings;
        this.seedEnabled = seedEnabled;
        this.seedDryRun = seedDryRun;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        CrawlerConfig config = configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
        config.setId(1L);
        boolean changed = false;

        // Fields the schema added to a row that already existed arrive as NULL, since a field
        // initialiser only touches new rows. The allocator reads maxActiveRequests as 5 when null, so
        // this is not a correctness fix — it is so the Settings page shows 5 rather than a blank box.
        CrawlerConfig defaults = CrawlerConfig.defaults();
        if (config.getMaxActiveRequests() == null) {
            config.setMaxActiveRequests(defaults.getMaxActiveRequests());
            changed = true;
        }
        if (config.getRentToleranceEuros() == null) {
            config.setRentToleranceEuros(defaults.getRentToleranceEuros());
            changed = true;
        }

        // Income and savings: bootstrap-once, so the settings page can override them later.
        if (seedIncome != null && config.getGrossMonthlyIncome() == null) {
            config.setGrossMonthlyIncome(seedIncome);
            changed = true;
        }
        if (seedSavings != null && config.getSavings() == null) {
            config.setSavings(seedSavings);
            changed = true;
        }

        // The two switches that arm the system: applied whenever the property is set.
        if (seedEnabled != null && seedEnabled != config.isAutoApplyEnabled()) {
            config.setAutoApplyEnabled(seedEnabled);
            changed = true;
        }
        if (seedDryRun != null && seedDryRun != config.isAutoApplyDryRun()) {
            config.setAutoApplyDryRun(seedDryRun);
            changed = true;
        }

        if (changed) {
            configRepository.save(config);
            log.info(
                    "Seeded auto-apply config: enabled={}, dryRun={}, rent ceiling EUR {}",
                    config.isAutoApplyEnabled(),
                    config.isAutoApplyDryRun(),
                    config.getRentCeiling());
        }
    }
}

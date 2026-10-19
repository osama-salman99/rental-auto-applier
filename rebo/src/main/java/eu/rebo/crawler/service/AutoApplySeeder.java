package eu.rebo.crawler.service;

import eu.rebo.crawler.model.CrawlerConfig;
import eu.rebo.crawler.repository.CrawlerConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Seeds auto-apply settings from the environment on startup, so income, phone and the armed/dry-run
 * flags live in a systemd drop-in on the VPS rather than in git or the H2 file. Present variables win
 * over whatever is stored; absent ones leave the stored value untouched. Arming is therefore: edit the
 * drop-in, restart the unit.
 */
@Component
public class AutoApplySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AutoApplySeeder.class);

    private final CrawlerConfigRepository configRepository;
    private final Environment env;

    public AutoApplySeeder(CrawlerConfigRepository configRepository, Environment env) {
        this.configRepository = configRepository;
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        CrawlerConfig config = configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
        boolean changed = false;

        changed |= applyString("REBO_AUTOAPPLY_FIRST_NAME", config::setApplicantFirstName);
        changed |= applyString("REBO_AUTOAPPLY_LAST_NAME", config::setApplicantLastName);
        changed |= applyString("REBO_AUTOAPPLY_EMAIL", config::setApplicantEmail);
        changed |= applyString("REBO_AUTOAPPLY_PHONE", config::setApplicantPhone);
        changed |= applyString("REBO_AUTOAPPLY_INCOME_TYPE", config::setIncomeType);

        String income = env.getProperty("REBO_AUTOAPPLY_INCOME");
        if (income != null && !income.isBlank()) {
            try {
                config.setIncomeMonthlyGross(Integer.parseInt(income.trim()));
                changed = true;
            } catch (NumberFormatException e) {
                log.warn("REBO_AUTOAPPLY_INCOME is not an integer: '{}' — leaving income unset", income);
            }
        }

        String enabled = env.getProperty("REBO_AUTOAPPLY_ENABLED");
        if (enabled != null) {
            config.setAutoApplyEnabled(Boolean.parseBoolean(enabled.trim()));
            changed = true;
        }
        String dryRun = env.getProperty("REBO_AUTOAPPLY_DRY_RUN");
        if (dryRun != null) {
            config.setAutoApplyDryRun(Boolean.parseBoolean(dryRun.trim()));
            changed = true;
        }

        // NB: the arming boundary (autoApplyArmedAt) is deliberately NOT stamped here. It is stamped
        // by the first apply pass instead (AutoApplyService.runOnce), once the DB is populated —
        // stamping it at startup, before the first crawl, made every listing that crawl then created
        // read as "new since arming", so a fresh/empty DB blasted the whole backlog with real
        // registrations at once.

        if (changed) {
            configRepository.save(config);
        }
        // Never log the applicant's details; state only the operational posture.
        log.info("Auto-apply seeded: enabled={} dryRun={} incomeSet={} contactComplete={}",
                config.isAutoApplyEnabled(), config.isAutoApplyDryRun(),
                config.getIncomeMonthlyGross() != null, contactComplete(config));
    }

    private boolean applyString(String key, java.util.function.Consumer<String> setter) {
        String v = env.getProperty(key);
        if (v == null || v.isBlank()) {
            return false;
        }
        setter.accept(v.trim());
        return true;
    }

    static boolean contactComplete(CrawlerConfig c) {
        return notBlank(c.getApplicantFirstName()) && notBlank(c.getApplicantLastName())
                && notBlank(c.getApplicantEmail()) && notBlank(c.getApplicantPhone());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}

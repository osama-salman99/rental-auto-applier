package eu.roofz.crawler.service;

import eu.roofz.crawler.model.ApplicantProfile;
import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.Listing;
import eu.roofz.crawler.repository.ApplicantProfileRepository;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import eu.roofz.crawler.repository.ListingRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Ensures the singleton CrawlerConfig row exists and backfills settings that are NULL on an
 * existing row (e.g. after schema migration adds new columns). Also seeds the singleton
 * {@link ApplicantProfile} on first boot from the file named by {@code ROOFZ_APPLICANT_PROFILE},
 * if one is set.
 *
 * <p>The applicant's details are personal, so none are built in: they are set on the settings and
 * Documents pages.
 */
@Component
public class ConfigInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ConfigInitializer.class);

    private final CrawlerConfigRepository repository;
    private final ApplicantProfileRepository profileRepository;
    private final ListingRepository listingRepository;
    private final String applicantProfilePath;

    public ConfigInitializer(
            CrawlerConfigRepository repository,
            ApplicantProfileRepository profileRepository,
            ListingRepository listingRepository,
            @Value("${roofz.applicant-profile:}") String applicantProfilePath) {
        this.repository = repository;
        this.profileRepository = profileRepository;
        this.listingRepository = listingRepository;
        this.applicantProfilePath = applicantProfilePath;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedApplicantProfile();
        CrawlerConfig existing = repository.findById(1L).orElse(null);
        if (existing == null) {
            repository.save(CrawlerConfig.defaults());
            log.info("Created default CrawlerConfig row");
            suppressWatchdogBacklogOnce();
            return;
        }

        CrawlerConfig defaults = CrawlerConfig.defaults();
        boolean changed = false;
        if (existing.getAutoApplyEnabled() == null) {
            existing.setAutoApplyEnabled(defaults.getAutoApplyEnabled());
            changed = true;
        }
        if (existing.getPollIntervalSeconds() == null) {
            existing.setPollIntervalSeconds(defaults.getPollIntervalSeconds());
            changed = true;
        }
        if (existing.getPortalPipelineEnabled() == null) {
            existing.setPortalPipelineEnabled(defaults.getPortalPipelineEnabled());
            changed = true;
        }
        if (existing.getPortalDryRun() == null) {
            existing.setPortalDryRun(defaults.getPortalDryRun());
            changed = true;
        }
        if (existing.getInviteWatchdogMinutes() == null) {
            existing.setInviteWatchdogMinutes(defaults.getInviteWatchdogMinutes());
            changed = true;
        }
        if (changed) {
            repository.save(existing);
            log.info("Backfilled defaults onto CrawlerConfig");
        }
        suppressWatchdogBacklogOnce();
    }

    /**
     * One-time: mark every already-applied listing as watchdog-alerted so that deploying the portal
     * pipeline doesn't fire an "applied but no invite" alert (and phone call) for the entire historical
     * backlog. Gated by a persistent config flag so it runs exactly once and never suppresses a
     * genuinely new application on later restarts.
     */
    private void suppressWatchdogBacklogOnce() {
        CrawlerConfig config = repository.findById(1L).orElse(null);
        if (config != null && config.isWatchdogBacklogSuppressed()) {
            return;
        }
        List<Listing> applied = listingRepository.findByAppliedAtIsNotNullAndInviteWatchdogAlertedFalse();
        for (Listing l : applied) {
            l.setInviteWatchdogAlerted(true);
        }
        if (!applied.isEmpty()) {
            listingRepository.saveAll(applied);
        }
        if (config == null) {
            config = CrawlerConfig.defaults();
        }
        config.setWatchdogBacklogSuppressed(true);
        repository.save(config);
        log.info("Suppressed watchdog backlog for {} previously-applied listing(s)", applied.size());
    }

    /**
     * Seeds the singleton ApplicantProfile on first boot from the file named by
     * {@code ROOFZ_APPLICANT_PROFILE}. Once stored, the file is not read again: the profile is
     * edited on the Documents page, where it is also filled in when there is no file.
     */
    private void seedApplicantProfile() {
        if (profileRepository.existsById(1L)) {
            return;
        }
        if (applicantProfilePath == null || applicantProfilePath.isBlank()) {
            log.info("No ROOFZ_APPLICANT_PROFILE set; fill the profile in on the Documents page");
            return;
        }
        try {
            String json = Files.readString(Path.of(applicantProfilePath), StandardCharsets.UTF_8);
            profileRepository.save(new ApplicantProfile(json));
            log.info("Seeded ApplicantProfile from {}", applicantProfilePath);
        } catch (IOException e) {
            log.error("Failed to seed ApplicantProfile from {}", applicantProfilePath, e);
        }
    }
}

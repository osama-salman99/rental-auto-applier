package eu.roofz.crawler.service;

import eu.roofz.crawler.model.DocumentView;
import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.Listing;
import eu.roofz.crawler.model.PortalApplication;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import eu.roofz.crawler.repository.ListingRepository;
import eu.roofz.crawler.repository.PortalApplicationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Anomaly detector for the whole application funnel. Every stage that should complete within a tight
 * SLA has a "did the expected confirmation arrive?" guard; if the SLA elapses without it, the user is
 * alerted (ntfy, plus a phone call for the first stage) — exactly once per stage per application. The
 * happy-path transitions that depend on the landlord ("no limit" in the funnel) are deliberately not
 * watched. It also nudges the user to upload documents at the start of each new month.
 *
 * <pre>
 *   W1  applied on roofz.eu           → pre-application invite email     (≤ stageSlaSeconds)
 *   W2  pre-application submitted      → pre-application confirmation     (≤ stageSlaSeconds)
 *   W3  full application finalized     → full-application confirmation    (≤ stageSlaSeconds)
 * </pre>
 *
 * <p>Per-application failures inside a step are escalated by {@link PortalPipelineService}; this
 * watchdog covers the silent-failure gap where a submission returns success but nothing follows.
 */
@Service
public class InviteWatchdog {

    private static final Logger log = LoggerFactory.getLogger(InviteWatchdog.class);

    private final ListingRepository listingRepository;
    private final PortalApplicationRepository portalRepository;
    private final CrawlerConfigRepository configRepository;
    private final NotificationService notificationService;
    private final TwilioCallService twilioCallService;
    private final ApplicantDocumentService documentService;
    private final Clock clock;

    public InviteWatchdog(
            ListingRepository listingRepository,
            PortalApplicationRepository portalRepository,
            CrawlerConfigRepository configRepository,
            NotificationService notificationService,
            TwilioCallService twilioCallService,
            ApplicantDocumentService documentService,
            Clock clock) {
        this.listingRepository = listingRepository;
        this.portalRepository = portalRepository;
        this.configRepository = configRepository;
        this.notificationService = notificationService;
        this.twilioCallService = twilioCallService;
        this.documentService = documentService;
        this.clock = clock;
    }

    public void check() {
        CrawlerConfig config = configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
        if (!config.isPortalPipelineEnabled()) {
            return;
        }
        // Each guard is independent — one failing stage must not block the others.
        runGuarded("applied-no-invite", () -> checkAppliedNoInvite(config));
        runGuarded("preapp-not-confirmed", () -> checkPreAppNotConfirmed(config));
        runGuarded("fullapp-not-confirmed", () -> checkFullAppNotConfirmed(config));
        runGuarded("monthly-doc-reminder", () -> checkMonthlyDocumentReminder(config));
    }

    /** W1: applied on roofz.eu but no pre-application invite appeared within the SLA. */
    private void checkAppliedNoInvite(CrawlerConfig config) {
        int slaSeconds = config.stageSlaSecondsOrDefault();
        Instant now = clock.instant();
        for (Listing l : listingRepository.findByAppliedAtIsNotNullAndInviteWatchdogAlertedFalse()) {
            if (l.getAppliedAt() == null || !elapsed(l.getAppliedAt(), now, slaSeconds)) {
                continue;
            }
            // Did any portal invitation appear at or after we applied? If so, the funnel is moving.
            if (portalRepository.existsByCreatedAtGreaterThanEqual(l.getAppliedAt())) {
                l.setInviteWatchdogAlerted(true);
                listingRepository.save(l);
                continue;
            }
            log.warn("Watchdog W1: applied to '{}' {}s ago but no pre-application invite appeared",
                    l.getTitle(), Duration.between(l.getAppliedAt(), now).toSeconds());
            if (config.isNotificationsEnabled()) {
                notificationService.sendAppliedNoInvite(l.getTitle(), config);
                twilioCallService.triggerNewListingCall();
            }
            l.setInviteWatchdogAlerted(true);
            listingRepository.save(l);
        }
    }

    /** W2: pre-application submitted but no confirmation email arrived within the SLA. */
    private void checkPreAppNotConfirmed(CrawlerConfig config) {
        int slaSeconds = config.stageSlaSecondsOrDefault();
        Instant now = clock.instant();
        for (PortalApplication p :
                portalRepository
                        .findByPreAppSubmittedAtIsNotNullAndPreAppConfirmedAtIsNullAndPreAppConfirmWatchdogAlertedFalse()) {
            if (!elapsed(p.getPreAppSubmittedAt(), now, slaSeconds)) {
                continue;
            }
            log.warn("Watchdog W2: pre-application for '{}' submitted {}s ago, still unconfirmed",
                    p.getResidenceAddress(), Duration.between(p.getPreAppSubmittedAt(), now).toSeconds());
            if (config.isNotificationsEnabled()) {
                notificationService.sendPreAppConfirmationMissing(p.getResidenceAddress(), config);
            }
            p.setPreAppConfirmWatchdogAlerted(true);
            portalRepository.save(p);
        }
    }

    /** W3: full application finalized but no confirmation email arrived within the SLA. */
    private void checkFullAppNotConfirmed(CrawlerConfig config) {
        int slaSeconds = config.stageSlaSecondsOrDefault();
        Instant now = clock.instant();
        for (PortalApplication p :
                portalRepository
                        .findByFinalizedAtIsNotNullAndFullAppConfirmedAtIsNullAndFullAppConfirmWatchdogAlertedFalse()) {
            if (!elapsed(p.getFinalizedAt(), now, slaSeconds)) {
                continue;
            }
            log.warn("Watchdog W3: full application for '{}' finalized {}s ago, still unconfirmed",
                    p.getResidenceAddress(), Duration.between(p.getFinalizedAt(), now).toSeconds());
            if (config.isNotificationsEnabled()) {
                notificationService.sendFullAppConfirmationMissing(p.getResidenceAddress(), config);
            }
            p.setFullAppConfirmWatchdogAlerted(true);
            portalRepository.save(p);
        }
    }

    /**
     * Once per calendar month, nudge the user to upload the new month's dated documents (payslip +
     * bank statement) so applications keep attaching the latest 3 months. Fires only when a dated type
     * is actually missing for the current month; either way the month marker advances so it's once/mo.
     */
    private void checkMonthlyDocumentReminder(CrawlerConfig config) {
        YearMonth current = YearMonth.now(clock);
        String currentKey = current.toString(); // e.g. "2026-07"
        if (currentKey.equals(config.getLastDocumentReminderMonth())) {
            return;
        }
        List<DocumentView> all = documentService.findAllMeta();
        List<String> missing = new ArrayList<>();
        for (String type : ApplicantDocumentService.DATED_TYPES) {
            boolean present =
                    all.stream()
                            .anyMatch(
                                    d ->
                                            type.equals(d.getDocType())
                                                    && d.getDocumentDate() != null
                                                    && YearMonth.from(d.getDocumentDate()).equals(current));
            if (!present) {
                missing.add(prettyDocType(type));
            }
        }
        if (!missing.isEmpty() && config.isNotificationsEnabled()) {
            notificationService.sendMonthlyDocumentReminder(
                    currentKey, "Missing for " + currentKey + ": " + String.join(", ", missing), config);
            log.info("Monthly document reminder fired for {} (missing: {})", currentKey, missing);
        }
        config.setLastDocumentReminderMonth(currentKey);
        configRepository.save(config);
    }

    private static boolean elapsed(Instant since, Instant now, int seconds) {
        return since != null && Duration.between(since, now).toSeconds() >= seconds;
    }

    private static String prettyDocType(String type) {
        return switch (type) {
            case "salarySlip" -> "payslip";
            case "bankStatement" -> "bank statement";
            default -> type;
        };
    }

    private void runGuarded(String name, Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.error("Watchdog stage '{}' failed", name, e);
        }
    }
}

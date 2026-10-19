package eu.rebo.crawler.service;

import eu.rebo.crawler.model.CrawlerConfig;
import eu.rebo.crawler.model.Listing;
import eu.rebo.crawler.repository.CrawlerConfigRepository;
import eu.rebo.crawler.repository.ListingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Registers "Interesse" on newly-available REBO listings the applicant can afford.
 *
 * <p>Unlike the sibling portals, REBO's entry point is an uncapped lead form, so there is no
 * allocator and nothing to hold or withdraw — the whole edge is being early on a new listing. This
 * service therefore fires on each freshly-seen, in-budget, open listing exactly once.
 *
 * <p>Every guard here is deliberate:
 * <ul>
 *   <li><b>Fail closed.</b> No income, or incomplete contact details, means register on nothing —
 *       guessing spends a real credit-check consent on the applicant's behalf.
 *   <li><b>Dry run is a pure read.</b> It selects and logs but performs no portal or DB write, so no
 *       future caller can reach a write through it. The guard is the first thing the write path sees.
 *   <li><b>No backlog blast.</b> Only listings first seen at/after {@code autoApplyArmedAt} are
 *       eligible, so arming does not fire on everything already open.
 *   <li><b>Boundary band.</b> REBO's own service-costs figure is unreliable (~7% of listings), and
 *       the affordability rule is computed on rent <i>including</i> service costs. Near the ceiling we
 *       notify for manual review rather than auto-fire on a number we do not trust.
 *   <li><b>The applied flag is our only record.</b> REBO exposes no candidacy list to read back, so
 *       unlike the siblings we cannot reconcile against the portal. We latch {@code applied} on a 2xx
 *       and never re-submit; this is a known limitation, not a reconciliation.
 * </ul>
 */
@Service
public class AutoApplyService {

    private static final Logger log = LoggerFactory.getLogger(AutoApplyService.class);

    /**
     * How far below the affordability ceiling a listing's gross rent must sit to be auto-registered.
     * Absorbs the error in REBO's service-costs figure; inside this band we notify instead of firing.
     */
    static final double BOUNDARY_MARGIN_EUR = 75.0;

    private final ListingRepository listingRepository;
    private final CrawlerConfigRepository configRepository;
    private final ReboFormClient formClient;
    private final NotificationService notificationService;
    private final Clock clock;

    public AutoApplyService(
            ListingRepository listingRepository,
            CrawlerConfigRepository configRepository,
            ReboFormClient formClient,
            NotificationService notificationService,
            Clock clock) {
        this.listingRepository = listingRepository;
        this.configRepository = configRepository;
        this.formClient = formClient;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    public record Summary(int considered, int registered, int boundary, int skipped, boolean dryRun) {}

    /**
     * A read-only snapshot for the UI. Holds both public fields (armed state, counts) and the
     * income-derived ceiling, which is personal — only the login-walled Control page may render it.
     */
    public record AgentStatus(
            boolean enabled, boolean dryRun, Instant armedAt, String targetCity,
            boolean incomeConfigured, boolean contactComplete, int ceiling,
            int total, int open, int openInScope, int affordable, int boundary, int registered) {
        public boolean armedLive() { return enabled && !dryRun; }
    }

    /** Computes the current agent status without acting. Safe to call from any request. */
    public AgentStatus status() {
        CrawlerConfig config = configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
        var ceilingOpt = config.maxGrossRent();
        double ceiling = ceilingOpt.orElse(0);

        int total = 0, open = 0, openInScope = 0, affordable = 0, boundary = 0, registered = 0;
        for (Listing l : listingRepository.findAll()) {
            total++;
            if (l.isApplied()) {
                registered++;
            }
            if (!"available".equals(l.getStage())) {
                continue;
            }
            open++;
            if (!isOpenAndInScope(l, config)) {
                continue;
            }
            openInScope++;
            if (ceilingOpt.isPresent()) {
                double rent = l.getGrossRent();
                if (rent <= ceiling - BOUNDARY_MARGIN_EUR) {
                    affordable++;
                } else if (rent <= ceiling + BOUNDARY_MARGIN_EUR) {
                    boundary++;
                }
            }
        }
        return new AgentStatus(
                config.isAutoApplyEnabled(), config.isAutoApplyDryRun(), config.getAutoApplyArmedAt(),
                config.getTargetCity(), ceilingOpt.isPresent(),
                AutoApplySeeder.contactComplete(config), (int) Math.round(ceiling),
                total, open, openInScope, affordable, boundary, registered);
    }

    public Summary runOnce() {
        CrawlerConfig config = configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
        if (!config.isAutoApplyEnabled()) {
            return new Summary(0, 0, 0, 0, true);
        }

        // Fail closed: without an income ceiling or complete contact details, register on nothing.
        var ceiling = config.maxGrossRent();
        if (ceiling.isEmpty()) {
            log.warn("Auto-apply enabled but no income configured — registering on nothing (fail closed)");
            return new Summary(0, 0, 0, 0, config.isAutoApplyDryRun());
        }
        if (!AutoApplySeeder.contactComplete(config)) {
            log.warn("Auto-apply enabled but contact details incomplete — registering on nothing");
            return new Summary(0, 0, 0, 0, config.isAutoApplyDryRun());
        }
        // First arm — including after a DB wipe/rebuild (this VPS has no backups). Stamp the arming
        // boundary here, on the first pass, not at startup: if it were stamped before the first crawl
        // populated the DB, every listing that crawl created would read as "new since arming" and the
        // whole pre-existing backlog would be blasted with real registrations at once. So the first
        // pass only establishes the baseline — it registers nothing; every later pass acts only on
        // listings first seen from now on. (This one-time stamp is control metadata, the same write
        // the seeder used to do; it is not an apply-write, so it is intentionally made even in dry-run
        // so a dry run establishes the same boundary a live arm would.) roofz guards the same way.
        if (config.getAutoApplyArmedAt() == null) {
            config.setAutoApplyArmedAt(clock.instant());
            configRepository.save(config);
            log.info("Auto-apply armed — established a baseline of {} pre-existing listings; will "
                    + "register only on listings first seen from now on", listingRepository.count());
            return new Summary(0, 0, 0, 0, config.isAutoApplyDryRun());
        }
        double maxRent = ceiling.getAsDouble();
        Instant armedAt = config.getAutoApplyArmedAt();
        boolean dryRun = config.isAutoApplyDryRun();

        List<Listing> eligible = new ArrayList<>();
        List<Listing> boundary = new ArrayList<>();
        for (Listing l : listingRepository.findAll()) {
            if (!isOpenAndInScope(l, config) || l.isApplied()) {
                continue;
            }
            if (l.getFirstSeenAt() == null || l.getFirstSeenAt().isBefore(armedAt)) {
                continue; // pre-existing backlog, or seen before arming
            }
            double rent = l.getGrossRent();
            if (rent <= maxRent - BOUNDARY_MARGIN_EUR) {
                eligible.add(l);
            } else if (rent <= maxRent + BOUNDARY_MARGIN_EUR) {
                boundary.add(l);
            }
            // else: clearly unaffordable — skip silently
        }
        eligible.sort(Comparator.comparing(Listing::getFirstSeenAt).reversed());

        int registered = 0;
        for (Listing l : eligible) {
            if (dryRun) {
                log.info("[DRY-RUN] would register Interesse on {} (gross EUR {}, ceiling EUR {})",
                        l.getTitle(), Math.round(l.getGrossRent()), Math.round(maxRent));
                continue;
            }
            if (register(l, config)) {
                registered++;
            }
        }

        for (Listing l : boundary) {
            // The review ping is a one-time heads-up. Without this latch it re-sent every pass
            // (~once a minute) for as long as the home sat in the band — a notification flood.
            if (l.isBoundaryNotified()) {
                continue;
            }
            log.info("{}near affordability ceiling, needs manual review: {} (gross EUR {}, ceiling EUR {})",
                    dryRun ? "[DRY-RUN] " : "", l.getTitle(),
                    Math.round(l.getGrossRent()), Math.round(maxRent));
            if (!dryRun) {
                notificationService.sendCrawlerStatusNotification(
                        "[REBO] Review needed",
                        l.getTitle() + " gross EUR " + Math.round(l.getGrossRent())
                                + " is near your ceiling EUR " + Math.round(maxRent)
                                + " - service costs uncertain, register by hand if wanted.",
                        "warning", config);
                l.setBoundaryNotified(true);
                listingRepository.save(l);
            }
        }

        log.info("Auto-apply pass: {} eligible, {} registered, {} boundary{}",
                eligible.size(), registered, boundary.size(), dryRun ? " (DRY-RUN, nothing written)" : "");
        return new Summary(eligible.size() + boundary.size(), registered, boundary.size(), 0, dryRun);
    }

    private boolean register(Listing l, CrawlerConfig config) {
        ApplyPayload payload = new ApplyPayload(
                config.getApplicantFirstName(),
                config.getApplicantLastName(),
                config.getApplicantEmail(),
                config.getApplicantPhone(),
                config.getIncomeType(),
                null,
                l.getId(),
                l.getTitle(),
                l.getPostalCode());

        ReboFormClient.SubmitResult result = formClient.submit(payload);
        if (!result.accepted()) {
            log.error("Interesse registration REJECTED for {} (http {})", l.getTitle(), result.httpStatus());
            notificationService.sendCrawlerStatusNotification(
                    "[REBO] Auto-apply failed",
                    "Could not register interest on " + l.getTitle() + " (http " + result.httpStatus()
                            + "). Check by hand.",
                    "rotating_light", config);
            return false;
        }

        l.setApplied(true);
        l.setAppliedAt(clock.instant());
        listingRepository.save(l);
        log.info("Registered Interesse on {} (gross EUR {})", l.getTitle(), Math.round(l.getGrossRent()));
        notificationService.sendCrawlerStatusNotification(
                "[REBO] Interest registered",
                "Registered interest on " + l.getTitle() + " (gross EUR " + Math.round(l.getGrossRent()) + ").",
                "white_check_mark", config);
        return true;
    }

    private boolean isOpenAndInScope(Listing l, CrawlerConfig config) {
        if (!"available".equals(l.getStage())) {
            return false;
        }
        // Fail closed on an unreadable rent. A "prijs op aanvraag" home — or any entry the API returned
        // without a rental_price — persists with price=null, and getGrossRent() then reports €0, which
        // sails under every affordability ceiling. Left unchecked that auto-registers a real Interesse
        // application (privacy + credit_check consent) on a home we cannot actually price and which may
        // be far over budget. If we can't read the rent we can't assess affordability, so it is out of
        // actionable scope until a later crawl fills the price in.
        if (l.getPrice() == null) {
            return false;
        }
        String targetCity = config.getTargetCity();
        if (targetCity != null && !targetCity.isBlank()) {
            return l.getCity() != null && targetCity.equalsIgnoreCase(l.getCity());
        }
        return true;
    }
}

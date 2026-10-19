package eu.vesteda.crawler.service;

import eu.vesteda.crawler.model.ApplicationStatus;
import eu.vesteda.crawler.model.CrawlerConfig;
import eu.vesteda.crawler.model.Listing;
import eu.vesteda.crawler.model.SlotMove;
import eu.vesteda.crawler.repository.CrawlerConfigRepository;
import eu.vesteda.crawler.repository.ListingRepository;
import eu.vesteda.crawler.repository.SlotMoveRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Keeps the best five homes applied for, at all times.
 *
 * <p>Vesteda lets you hold five viewing requests. So the question is never "is this one worth
 * applying to?" — it is "are these the best five available right now?" Everything eligible is scored
 * (see {@link ListingScore}), the top five are what we hold, and anything that drops out is traded
 * in for whatever displaced it. An idle slot wins nothing, so the budget is always spent; it is just
 * spent on the best five rather than the first five.
 *
 * <p>Three things the live portal taught us, each now load-bearing:
 *
 * <ul>
 *   <li><b>Saturation is invisible until you have paid for it.</b> A home reads "For rent" on the
 *       search page right up until you submit, at which point it may answer "there are currently no
 *       more viewings possible" and register you as a standby. That answer is remembered on the
 *       listing, so the same slot is never spent learning the same thing twice.
 *   <li><b>Winning looks exactly like losing.</b> When we are drawn, the home turns "rented with
 *       reservation" — which is also what it does when somebody else is drawn. So a listing's state
 *       may never authorise a withdrawal; only the candidacy's own status may, and anything past
 *       "request submitted" is untouchable.
 *   <li><b>Opening the request form is a write.</b> A bare GET of {@code /aanvraag/<hash>/} puts the
 *       home on the account's wishlist. It is never called speculatively — not to preview, not to
 *       populate a page, and not in dry-run.
 * </ul>
 */
@Service
public class AutoApplyService {

    private static final Logger log = LoggerFactory.getLogger(AutoApplyService.class);

    private final PortalSession session;
    private final PortalStatusService portalStatus;
    private final ApplyFormParser formParser;
    private final FilterService filterService;
    private final ListingRepository listingRepository;
    private final CrawlerConfigRepository configRepository;
    private final NotificationService notificationService;
    private final SlotMoveRepository slotMoveRepository;

    /**
     * What the portal said we held, the last time we asked. The console renders from this rather
     * than calling out to Vesteda on every page load — a dashboard should not be able to fail, or
     * hang, because a third party is slow.
     */
    private volatile List<Candidacy> lastKnown = List.of();

    public List<Candidacy> lastKnownCandidacies() {
        return lastKnown;
    }

    public AutoApplyService(
            PortalSession session,
            PortalStatusService portalStatus,
            ApplyFormParser formParser,
            FilterService filterService,
            ListingRepository listingRepository,
            CrawlerConfigRepository configRepository,
            NotificationService notificationService,
            SlotMoveRepository slotMoveRepository) {
        this.session = session;
        this.portalStatus = portalStatus;
        this.formParser = formParser;
        this.filterService = filterService;
        this.listingRepository = listingRepository;
        this.configRepository = configRepository;
        this.notificationService = notificationService;
        this.slotMoveRepository = slotMoveRepository;
    }

    /**
     * One pass: work out the best five, then make reality match.
     *
     * <p>Synchronised because it runs both on a timer and immediately after any crawl that changed
     * something — two passes at once would each see a stale picture of which slots are spent, and
     * could overspend the budget.
     */
    public synchronized void tick() {
        CrawlerConfig config = configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
        if (!config.isAutoApplyEnabled()) {
            return;
        }
        if (!session.isConfigured()) {
            log.warn("Auto-apply is on but VESTEDA_EMAIL / VESTEDA_PASSWORD are not set");
            return;
        }

        List<Candidacy> candidacies = portalStatus.candidacies();
        lastKnown = candidacies;
        rememberStandbys(candidacies);

        int cap = config.getMaxActiveRequests() == null ? 5 : config.getMaxActiveRequests();

        // A candidacy Vesteda has drawn holds a slot we cannot spend and must not disturb.
        List<Candidacy> drawn =
                candidacies.stream().filter(c -> c.status() == Candidacy.Status.ADVANCED).toList();
        drawn.forEach(
                c -> log.info("Candidacy {} has been drawn — holding its slot, never withdrawing", c.label()));

        List<Candidacy> held = candidacies.stream().filter(Candidacy::isWithdrawable).toList();
        int budget = Math.max(0, cap - drawn.size());

        Set<String> untouchable =
                drawn.stream().map(Candidacy::objectHash).collect(Collectors.toSet());

        List<Listing> target =
                rank(config).stream()
                        .filter(l -> !untouchable.contains(l.getPortalHash()))
                        .limit(budget)
                        .toList();
        Set<String> targetHashes = new LinkedHashSet<>();
        target.forEach(l -> targetHashes.add(l.getPortalHash()));

        Map<String, Listing> byHash = listingsByHash();
        Set<String> heldHashes = held.stream().map(Candidacy::objectHash).collect(Collectors.toSet());

        // Dry run is a pure read: it says what it would do and touches nothing. In particular it does
        // not open any request form — that is a write on this portal (it wishlists the home), so it
        // must never happen speculatively — and it records no withdrawals, real or in our own
        // database, so a poll every 45 seconds can't flood the moves timeline with no-ops.
        if (config.isAutoApplyDryRun()) {
            for (Candidacy c : held) {
                if (!targetHashes.contains(c.objectHash()) && config.isAutoWithdrawEnabled()) {
                    log.info("[DRY RUN] Would withdraw {} — a better home wants its slot", c.label());
                }
            }
            for (Listing l : target) {
                if (!heldHashes.contains(l.getPortalHash())) {
                    log.info(
                            "[DRY RUN] Would request a viewing for {} (EUR {}, score {})",
                            l.getTitle(),
                            l.rent(),
                            ListingScore.of(l, config).total());
                }
            }
            return;
        }

        // Give up anything we hold that no longer makes the cut — it was let, or vanished, or a
        // better home turned up and wants its slot. Count the non-drawn slots we keep this pass: a
        // held request we may not reclaim (auto-withdraw off) still occupies one, so it must be counted
        // or the fill loop below would apply on top of it and push the total past the cap.
        int slotsInUse = 0;
        for (Candidacy c : held) {
            if (targetHashes.contains(c.objectHash())) {
                slotsInUse++; // kept — still in the plan
                continue;
            }
            if (!config.isAutoWithdrawEnabled()) {
                slotsInUse++; // kept because we may not reclaim it — but it still costs a slot
                continue;
            }
            releaseSlot(c, byHash.get(c.objectHash()), config);
        }

        for (Listing listing : target) {
            if (heldHashes.contains(listing.getPortalHash())) {
                continue;
            }
            if (slotsInUse >= budget) {
                break; // no free slot left — respect the cap instead of overspending
            }
            if (apply(listing, config)) {
                slotsInUse++;
            }
        }
    }

    /**
     * The portal tells us, on our own candidacy card, when a home had already stopped accepting
     * viewings. That is the ground truth about saturation and the only place it is ever stated, so
     * it is written back onto the listing — otherwise the next pass would rate the home as freshly
     * winnable and keep paying to rediscover that it is not.
     */
    private void rememberStandbys(List<Candidacy> candidacies) {
        Map<String, Listing> byHash = listingsByHash();
        for (Candidacy c : candidacies) {
            if (!c.standby()) {
                continue;
            }
            Listing l = byHash.get(c.objectHash());
            if (l != null && !Boolean.TRUE.equals(l.getStandby())) {
                l.setStandby(true);
                l.setCrowded(true);
                l.setApplicationDetail("Standby candidate — the home was already full");
                listingRepository.save(l);
                log.info("{} came back as a standby — the home was already full", c.label());
            }
        }
    }

    /** Withdraw one request and put its slot back in the budget. */
    private void releaseSlot(Candidacy c, Listing listing, CrawlerConfig config) {
        String why;
        if (listing == null) {
            why = "it is no longer on offer";
        } else if (!listing.portalAcceptsRequests()) {
            why = "it went to someone else";
        } else if (Boolean.TRUE.equals(listing.getStandby())) {
            why = "we were only a reserve, and a better home needs the slot";
        } else {
            why = "a better home needs the slot";
        }

        try {
            withdraw(c, config);
            if (listing != null) {
                listing.setApplied(false);
                listing.setApplicationStatus(ApplicationStatus.WITHDRAWN);
                listing.setApplicationDetail("Withdrawn — " + why);
                listingRepository.save(listing);
            }
            slotMoveRepository.save(
                    SlotMove.withdrawn(c.label(), listing == null ? null : listing.rent(), why));
            log.info("Withdrew request for {} — {}", c.label(), why);
            notify(config, "Slot freed", c.label() + ": " + why + ".");
        } catch (Exception e) {
            log.error("Could not withdraw request for {}", c.label(), e);
        }
    }

    /** Every home we could hold, best first. */
    List<Listing> rank(CrawlerConfig config) {
        return listingRepository.findAll().stream()
                .filter(l -> isEligible(l, config))
                .sorted(byScore(config))
                .toList();
    }

    /** Ties break on id, so the plan is stable rather than merely correct. */
    private Comparator<Listing> byScore(CrawlerConfig config) {
        return Comparator.comparingInt((Listing l) -> -ListingScore.of(l, config).total())
                .thenComparing(Listing::getId);
    }

    boolean isEligible(Listing l, CrawlerConfig config) {
        // No hash means the portal poll has never matched this home, and without one there is no
        // request form to post to.
        if (l.getPortalHash() == null) {
            return false;
        }
        // The portal's own word on whether it would even take a request. Anything it does not
        // recognise reads as CLOSED, so an unfamiliar state can never be mistaken for an open one.
        if (!l.portalAcceptsRequests()) {
            return false;
        }
        // A home reserved for over-65s, or a care home needing a care indication, is one a general
        // applicant categorically cannot be allocated. Spending a slot on it wins nothing, so it is
        // shut out here rather than merely ranked low.
        if (l.hasHardEligibilityGate()) {
            return false;
        }
        if (!filterService.matchesPreferences(l, config)) {
            return false;
        }
        // Fail closed. With no income configured there is no ceiling, and we cannot tell a home we
        // could be allocated from one we could not — so apply to nothing until income is set, rather
        // than guess and spend a slot on a home Vesteda will reject us for.
        // Also fail closed on a zero/absent rent: the API feed's priceUnformatted is a primitive
        // double, so a null/missing price parses to 0 (not null), and 0 <= ceiling would otherwise
        // read as "comfortably affordable" and spend a slot on a home whose price we do not actually
        // know. No real rental is EUR 0, so rent > 0 never excludes a genuine home.
        Integer ceiling = config.getRentCeiling();
        Integer rent = l.rent();
        return ceiling != null && rent != null && rent > 0 && rent <= ceiling;
    }

    /**
     * Submits one viewing request. Returns true when a slot was actually spent.
     *
     * <p>Fetching the form is itself a write — it puts the home on the account's wishlist — so this
     * is only ever called for a home we intend to apply to.
     */
    public boolean apply(Listing listing, CrawlerConfig config) {
        // A hard floor under the whole method: opening the request form wishlists the home, so
        // apply() writes to the account from its very first line. In dry run it must therefore not
        // run at all. tick() already routes around this; the guard is here so no future caller, and
        // no refactor, can reach the form GET while dry run is on.
        if (config.isAutoApplyDryRun()) {
            log.info("[DRY RUN] apply() suppressed for {} — nothing was opened", listing.getTitle());
            return false;
        }

        String hash = listing.getPortalHash();
        String formUrl = PortalSession.BASE + "/aanvraag/" + hash + "/";
        String objectUrl = PortalSession.BASE + "/object/" + hash + "/";
        ListingScore score = ListingScore.of(listing, config);

        try {
            ApplyForm form = formParser.parse(session.getAuthenticated(formUrl, objectUrl));

            if (!form.isComplete()) {
                String gaps = form.describeGaps();
                log.warn("Not applying for {} — {}", listing.getTitle(), gaps);
                record(listing, ApplicationStatus.SKIPPED, gaps, false);
                notify(
                        config,
                        "Auto-apply blocked",
                        listing.getTitle() + " needs your Vesteda profile completed: " + gaps);
                return false;
            }

            // The form arrives prefilled from the account — every answer, and every document, is
            // already on file — so the payload is mostly the portal's own words echoed back. The two
            // things it does not prefill are the declaration tick and which tab we are on.
            Map<String, String> payload = new LinkedHashMap<>(form.fields());
            payload.put("postrequest", "processoptieform");
            // curtab is the only thing separating "save and next" from "send form".
            payload.put("curtab", "verklaring");
            payload.put("akkoord_a", "on");

            // Income comes from Settings, not from the portal's prefill, because the same number
            // decides the rent ceiling. One source, so the ceiling we filter by and the figure
            // Vesteda reads can never drift apart.
            if (config.getGrossMonthlyIncome() != null) {
                payload.put("a_maandinkomen", String.valueOf(config.getGrossMonthlyIncome()));
            }

            session.postAuthenticated(formUrl, payload, formUrl);

            // A 302 is not proof. The portal must actually show the request as received.
            Candidacy confirmed = confirm(hash);
            if (confirmed == null) {
                log.error("Submitted a request for {} but the portal does not show it", listing.getTitle());
                record(listing, ApplicationStatus.FAILED, "Portal did not confirm the request", false);
                notify(
                        config,
                        "Auto-apply failed",
                        "Submitted " + listing.getTitle() + " but the portal did not confirm it.");
                return false;
            }

            boolean standby = confirmed.standby();
            record(listing, ApplicationStatus.APPLIED, standby ? "Standby candidate" : null, true);
            if (standby) {
                listing.setStandby(true);
                listing.setCrowded(true);
                listingRepository.save(listing);
            }

            slotMoveRepository.save(
                    SlotMove.applied(listing.getTitle(), listing.rent(), score.total(), score.explain()));
            log.info(
                    "Viewing request submitted for {} (EUR {}, score {}){}",
                    listing.getTitle(),
                    listing.rent(),
                    score.total(),
                    standby ? " — but the home was already full, we are a standby" : "");
            notify(
                    config,
                    standby ? "Requested (standby)" : "Viewing requested",
                    listing.getTitle()
                            + " - EUR "
                            + listing.rent()
                            + "/mo. "
                            + (standby
                                    ? "The home was already full, so you are a reserve."
                                    : "You are in the draw."));
            return true;

        } catch (Exception e) {
            log.error("Auto-apply failed for {}", listing.getTitle(), e);
            record(listing, ApplicationStatus.FAILED, truncate(e.getMessage()), false);
            notify(config, "Auto-apply failed", listing.getTitle() + ": " + truncate(e.getMessage()));
            return false;
        }
    }

    private void withdraw(Candidacy candidacy, CrawlerConfig config) {
        if (config.isAutoApplyDryRun()) {
            log.info("[DRY RUN] Would withdraw candidacy {} ({})", candidacy.uuid(), candidacy.label());
            return;
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("_token", session.csrfToken());
        form.put("postrequest", "verwijderkandidatuur");
        form.put("uuid", candidacy.uuid());
        session.postAuthenticated(
                PortalStatusService.MIJN_WONING_URL, form, PortalStatusService.MIJN_WONING_URL);
    }

    /** Re-reads the portal and returns our candidacy on that home, or null if it isn't there. */
    private Candidacy confirm(String hash) {
        return portalStatus.candidacies().stream()
                .filter(c -> hash.equals(c.objectHash()) && c.occupiesSlot())
                .findFirst()
                .orElse(null);
    }

    private Map<String, Listing> listingsByHash() {
        Map<String, Listing> byHash = new LinkedHashMap<>();
        for (Listing l : listingRepository.findAll()) {
            if (l.getPortalHash() != null) {
                byHash.put(l.getPortalHash(), l);
            }
        }
        return byHash;
    }

    private void record(Listing listing, String status, String detail, boolean applied) {
        listing.setApplicationStatus(status);
        listing.setApplicationDetail(truncate(detail));
        listing.setApplied(applied);
        if (applied) {
            listing.setAppliedAt(Instant.now());
        }
        listingRepository.save(listing);
    }

    private void notify(CrawlerConfig config, String title, String message) {
        if (!config.isNotificationsEnabled()) {
            return;
        }
        // ntfy rejects non-ASCII header values, and the title travels as a header.
        notificationService.sendCrawlerStatusNotification("[Vesteda] " + title, message, "house", config);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }

    /** Exposed for the dashboard: the plan, so the UI can show why each home is where it is. */
    public List<Listing> currentPlan(CrawlerConfig config) {
        int cap = config.getMaxActiveRequests() == null ? 5 : config.getMaxActiveRequests();
        return new ArrayList<>(rank(config).stream().limit(cap).toList());
    }
}

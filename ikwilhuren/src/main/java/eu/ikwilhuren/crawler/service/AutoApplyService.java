package eu.ikwilhuren.crawler.service;

import eu.osmosis.crawler.core.http.CurlFetchException;

import eu.ikwilhuren.crawler.model.ApplicationStatus;
import eu.ikwilhuren.crawler.model.CrawlerConfig;
import eu.ikwilhuren.crawler.model.Listing;
import eu.ikwilhuren.crawler.model.SlotMove;
import eu.ikwilhuren.crawler.repository.CrawlerConfigRepository;
import eu.ikwilhuren.crawler.repository.ListingRepository;
import eu.ikwilhuren.crawler.repository.SlotMoveRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Keeps the best five listings applied for, at all times.
 *
 * <p>MVGM lets you hold five viewing requests. So the question is never "is this one worth applying
 * to?" — it is "are these the best five available right now?" Every eligible listing is scored (see
 * {@link ListingScore}), the top five are the ones we hold, and anything that drops out of the top
 * five is traded in for whatever displaced it. An idle slot wins nothing, so the budget is always
 * spent; it is simply spent on the best five rather than the first five.
 *
 * <p>Two things the crawler learned the hard way, both now encoded in the score rather than in
 * special cases:
 *
 * <ul>
 *   <li>MVGM caps viewing requests per home. Its first pass filled all five slots with the five
 *       cheapest listings, every one of which MVGM had already flagged as having "many viewing
 *       requests" — and all five came back within minutes as "no more viewings possible". A full
 *       home is a long shot, not a home you have applied for.
 *   <li>But a long shot still beats an empty slot: the interest stays on file, and MVGM re-draws if
 *       its chosen candidate walks away. So full homes are held — and are simply the first thing
 *       given up when a home we can actually win appears.
 * </ul>
 *
 * <p>A request MVGM has already drawn is never touched. Winning looks identical to losing from the
 * listing's side — the home turns "rented" either way — so only our own candidacy status may
 * authorise a withdrawal.
 */
@Service
public class AutoApplyService {

    private static final Logger log = LoggerFactory.getLogger(AutoApplyService.class);

    /** How many listings to read property pages for in one pass. Keeps our footprint small. */
    private static final int SHORTLIST = 14;

    /**
     * What changed during one pass. A swap is a single event to a human — "you're in the draw for
     * Testweg, and Develstein made way for it" — but three separate things to the allocator. It
     * used to send a push for each, so one reshuffle arrived as three notifications that had to be
     * reassembled in your head. The pass reports once, at the end, as a sentence.
     */
    private static final class Pass {
        final List<String> applied = new ArrayList<>();
        final List<String> released = new ArrayList<>();
        String reason;

        boolean changedAnything() {
            return !applied.isEmpty() || !released.isEmpty();
        }
    }

    private final ThreadLocal<Pass> pass = new ThreadLocal<>();

    private final PortalSession session;
    private final PortalStatusService portalStatus;
    private final ApplyFormParser formParser;
    private final CompetitionService competitionService;
    private final AnswerService answerService;
    private final FilterService filterService;
    private final ListingRepository listingRepository;
    private final CrawlerConfigRepository configRepository;
    private final NotificationService notificationService;
    private final SlotMoveRepository slotMoveRepository;

    public AutoApplyService(
            PortalSession session,
            PortalStatusService portalStatus,
            ApplyFormParser formParser,
            CompetitionService competitionService,
            AnswerService answerService,
            FilterService filterService,
            ListingRepository listingRepository,
            CrawlerConfigRepository configRepository,
            NotificationService notificationService,
            SlotMoveRepository slotMoveRepository) {
        this.session = session;
        this.portalStatus = portalStatus;
        this.formParser = formParser;
        this.competitionService = competitionService;
        this.answerService = answerService;
        this.filterService = filterService;
        this.listingRepository = listingRepository;
        this.configRepository = configRepository;
        this.notificationService = notificationService;
        this.slotMoveRepository = slotMoveRepository;
    }

    /**
     * One pass: work out the best five, then make reality match.
     *
     * <p>Synchronised because it runs both on a timer and immediately after every crawl — two passes
     * at once would each see a stale picture of which slots are spent and could overspend the budget.
     */
    public synchronized void tick() {
        CrawlerConfig config = configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
        if (!config.isAutoApplyEnabled()) {
            return;
        }
        if (!session.isConfigured()) {
            log.warn("Auto-apply is on but IKWILHUREN_EMAIL / IKWILHUREN_PASSWORD are not set");
            return;
        }

        Pass p = new Pass();
        pass.set(p);
        try {
            runPass(config);
        } finally {
            pass.remove();
        }
        announce(p, config);
    }

    private void runPass(CrawlerConfig config) {
        List<Candidacy> candidacies = portalStatus.candidacies();
        markSaturatedFromPortal(candidacies);
        markDrawnFromPortal(candidacies);
        learnProfileIfUnknown(candidacies);

        int cap = config.getMaxActiveRequests() == null ? 5 : config.getMaxActiveRequests();

        // A candidacy MVGM has drawn holds a slot we cannot spend and must not disturb.
        List<Candidacy> drawn =
                candidacies.stream()
                        .filter(c -> c.status() == Candidacy.Status.ADVANCED)
                        .toList();
        drawn.forEach(
                c -> log.info("Candidacy {} has been drawn — holding its slot, never withdrawing", c.label()));

        List<Candidacy> held = candidacies.stream().filter(Candidacy::isWithdrawable).toList();
        int budget = Math.max(0, cap - drawn.size());

        Set<String> untouchable =
                drawn.stream().map(Candidacy::objectHash).collect(java.util.stream.Collectors.toSet());

        // The best `budget` listings we could be holding, in order.
        List<Listing> target =
                rank(config).stream()
                        .filter(l -> !untouchable.contains(l.objectHash()))
                        .limit(budget)
                        .toList();
        Set<String> targetHashes = new LinkedHashSet<>();
        target.forEach(l -> targetHashes.add(l.objectHash()));

        // Give up anything we hold that no longer makes the cut. A listing drops out because it was
        // let, or vanished, or because a better home turned up and wants its slot.
        // Track how many of the `budget` non-drawn slots are already spent on requests we keep this
        // pass — a held request we may not reclaim (auto-withdraw off) still occupies one, so it has to
        // be counted or the fill loop below would apply on top of it and push the total past the cap.
        Map<String, Listing> byHash = listingsByHash();
        int slotsInUse = 0;
        for (Candidacy c : held) {
            if (targetHashes.contains(c.objectHash())) {
                slotsInUse++; // kept because it is still in the plan
                continue;
            }
            if (!config.isAutoWithdrawEnabled()) {
                slotsInUse++; // kept because we may not reclaim it — but it still costs a slot
                continue;
            }
            releaseSlot(c, byHash.get(c.objectHash()), config);
        }

        // Fill the remaining free slots, never exceeding the budget (cap − drawn − what we keep).
        Set<String> stillHeld = new LinkedHashSet<>();
        held.stream()
                .filter(c -> targetHashes.contains(c.objectHash()))
                .forEach(c -> stillHeld.add(c.objectHash()));

        for (Listing listing : target) {
            if (stillHeld.contains(listing.objectHash())) {
                continue;
            }
            if (slotsInUse >= budget) {
                break; // no free slot left — respect the cap instead of overspending
            }
            // Opening the form registers interest on the account, so a dry run is not free: without
            // this, every pass would re-open the form for every listing in the plan.
            if (config.isAutoApplyDryRun()
                    && ApplicationStatus.DRY_RUN.equals(listing.getApplicationStatus())) {
                continue;
            }
            if (apply(listing, config)) {
                slotsInUse++;
            }
        }
    }

    /**
     * One pass, one notification.
     *
     * <p>A swap is a single event to a human — you're in the draw for a better home, and a weaker
     * request made way for it — but three separate actions to the allocator. Reporting each one
     * pushed three notifications for what was, to the person reading them, one thing that happened.
     * So the pass speaks once, and says what actually changed.
     *
     * <p>Nothing at all changed on most passes, and those say nothing.
     */
    private void announce(Pass p, CrawlerConfig config) {
        if (!p.changedAnything()) {
            return;
        }

        String title;
        StringBuilder body = new StringBuilder();

        if (!p.applied.isEmpty()) {
            title =
                    p.applied.size() == 1
                            ? "You're in the draw"
                            : "You're in " + p.applied.size() + " new draws";
            body.append(String.join("\n", p.applied));
            if (!p.released.isEmpty()) {
                body.append("\n\nTraded out: ").append(String.join(", ", p.released));
                if (p.reason != null) {
                    body.append(" (").append(p.reason).append(")");
                }
            }
        } else {
            // Slots freed but nothing worth taking them — worth knowing, and quieter than it sounds.
            title = p.released.size() == 1 ? "A request was withdrawn" : p.released.size() + " requests withdrawn";
            body.append(String.join(", ", p.released));
            if (p.reason != null) {
                body.append("\n").append(p.reason);
            }
        }

        notify(config, title, body.toString());
    }

    /**
     * The portal tells us, on our own candidacy card, when a home stopped accepting viewings. That
     * is the ground truth about saturation, and it is worth remembering on the listing so the score
     * reflects it even after the request is gone.
     */
    private void markSaturatedFromPortal(List<Candidacy> candidacies) {
        Map<String, Listing> byHash = listingsByHash();
        for (Candidacy c : candidacies) {
            if (!c.saturated()) {
                continue;
            }
            Listing l = byHash.get(c.objectHash());
            if (l != null && !Boolean.TRUE.equals(l.getCrowded())) {
                l.setCrowded(Boolean.TRUE);
                l.setCompetitionCheckedAt(Instant.now());
                listingRepository.save(l);
            }
        }
    }

    /**
     * The portal advances our candidacy past a plain request the moment MVGM draws us for a viewing.
     * Persist that on the listing so the dashboard can flag the invitation between passes — the tick
     * already reads it (to protect the slot), this just remembers it. Mirrors the live page both
     * ways: a home that is drawn is flagged, and one that isn't (or no longer is) is un-flagged, so a
     * stale flag can never outlive the invitation.
     */
    private void markDrawnFromPortal(List<Candidacy> candidacies) {
        Map<String, Listing> byHash = listingsByHash();
        for (Candidacy c : candidacies) {
            Listing l = byHash.get(c.objectHash());
            if (l == null) {
                continue;
            }
            boolean drawn = c.status() == Candidacy.Status.ADVANCED;
            if (l.isInvited() != drawn) {
                l.setInvited(drawn);
                listingRepository.save(l);
            }
        }
    }

    /**
     * Fill in what your ikwilhuren.nu profile says, so the settings page can show it.
     *
     * <p>Those answers only exist on MVGM's request form, and opening that form for a home you are
     * not yet a candidate for registers interest — a side effect we may not cause just to populate a
     * page. But for a home you <em>already</em> hold a request on, the candidacy exists: opening it
     * again registers nothing new. So we read one of those, once, and only when we don't already
     * know.
     *
     * <p>Without this, "keep my profile's answer" would stay a mystery until the next application
     * happened to go out — which, with all five requests held, could be days.
     */
    private void learnProfileIfUnknown(List<Candidacy> candidacies) {
        if (answerService.knowsProfile()) {
            return;
        }
        candidacies.stream()
                .filter(Candidacy::occupiesSlot)
                .findFirst()
                .ifPresent(
                        c -> {
                            String url =
                                    PortalSession.BASE + "/bezichtigingsaanvraag/" + c.objectHash() + "/";
                            try {
                                ApplyForm form = formParser.parse(session.getAuthenticated(url, null));
                                answerService.rememberProfile(form.fields());
                                log.info("Read your ikwilhuren profile from the request for {}", c.label());
                            } catch (Exception e) {
                                log.debug("Could not read the profile from {}: {}", c.label(), e.getMessage());
                            }
                        });
    }

    /** Withdraw one request and put its slot back in the budget. */
    private void releaseSlot(Candidacy c, Listing listing, CrawlerConfig config) {
        String why =
                c.lost()
                        ? "it went to someone else"
                        : listing == null || !"available".equals(listing.getStage())
                                ? "it is no longer on offer"
                                : "a better home needs the slot";
        try {
            withdraw(c, config);
            if (listing != null) {
                listing.setApplied(false);
                listing.setApplicationStatus(ApplicationStatus.WITHDRAWN);
                listing.setApplicationDetail("Withdrawn — " + why);
                listingRepository.save(listing);
            }
            slotMoveRepository.save(
                    SlotMove.withdrawn(
                            c.label(), listing == null ? null : listing.getPrice(), why));
            log.info("Withdrew request for {} — {}", c.label(), why);
            Pass p = pass.get();
            if (p != null) {
                p.released.add(c.label());
                p.reason = why;
            }
        } catch (Exception e) {
            log.error("Could not withdraw request for {}", c.label(), e);
        }
    }

    /**
     * Every listing we could hold, best first. Cheap gates first; the property-page read that tells
     * us whether a home is already full is the expensive part, so it runs only on the shortlist.
     */
    List<Listing> rank(CrawlerConfig config) {
        List<Listing> shortlist =
                listingRepository.findAll().stream()
                        .filter(l -> isEligible(l, config))
                        .sorted(provisionalOrder(config))
                        .limit(SHORTLIST)
                        .toList();

        if (config.isSkipCrowdedListings()) {
            for (Listing l : shortlist) {
                // Persist only when the check actually re-read the page. The verdict is cached for
                // TTL (20 min) but this pass runs every ~2 min, so ~9 of 10 passes hit the cache and
                // mutate nothing — saving there just re-wrote an unchanged row every pass per listing.
                boolean rechecked = competitionService.needsRecheck(l);
                competitionService.isCrowded(l); // refreshes the listing in place when stale, else cached
                if (rechecked) {
                    listingRepository.save(l);
                }
            }
        }

        return shortlist.stream().sorted(byScore(config)).toList();
    }

    /** Before we know how crowded anything is, order by the parts of the score we already have. */
    private Comparator<Listing> provisionalOrder(CrawlerConfig config) {
        return Comparator.comparingInt((Listing l) -> -ListingScore.of(l, config).total())
                .thenComparing(Listing::getId);
    }

    /** Ties break on id so the plan is stable rather than merely correct. */
    private Comparator<Listing> byScore(CrawlerConfig config) {
        return Comparator.comparingInt((Listing l) -> -ListingScore.of(l, config).total())
                .thenComparing(Listing::getId);
    }

    boolean isEligible(Listing l, CrawlerConfig config) {
        if (!"available".equals(l.getStage())) {
            return false;
        }
        // A home we already found has no viewing-request form (new build / care / soon-available). It
        // passes every other filter, so without this it would rank, take a slot and 404 on every pass.
        if (!l.isApplicable()) {
            return false;
        }
        if (!filterService.matches(l, config)) {
            return false;
        }
        // Fail closed. With no ceiling configured we cannot tell a winnable listing from an
        // unwinnable one, and guessing wrong costs a slot — so apply to nothing until income is set.
        Integer ceiling = config.getRentCeiling();
        if (ceiling == null || l.getPrice() == null || l.getPrice() > ceiling) {
            return false;
        }
        return l.objectHash() != null;
    }

    /**
     * Submits one viewing request. Returns true when a slot was actually spent.
     *
     * <p>Fetching the form is itself a write — it registers interest on the account — so this is
     * only ever called for a listing we intend to apply to.
     */
    public boolean apply(Listing listing, CrawlerConfig config) {
        String hash = listing.objectHash();
        String formUrl = PortalSession.BASE + "/bezichtigingsaanvraag/" + hash + "/";
        String objectUrl = "https://ikwilhuren.nu/object/" + listing.getSlug() + "/";

        String html;
        try {
            html = session.getAuthenticated(formUrl, objectUrl);
        } catch (CurlFetchException e) {
            if (!e.isTransient()) {
                // No form at this URL (a 404): a new-build, care or soon-available home that has no
                // viewing-request flow. Bench it so it stops taking a slot and 404-ing on every pass,
                // and stay quiet — this is a routine "can't apply here", not an error worth a push.
                log.info("Not applying for {} — no viewing-request form; benched", listing.getTitle());
                listing.setApplicable(false);
                listingRepository.save(listing);
                return false;
            }
            return failApply(listing, config, e); // a network blip: report it and retry next pass
        }

        try {
            ApplyForm form = formParser.parse(html);

            // The request form is the only place MVGM shows what your profile currently answers, and
            // opening it registers interest — so this is the one moment we may capture it. The
            // settings page needs it to say what "keep my profile's answer" actually means.
            answerService.rememberProfile(form.fields());

            if (!form.isComplete()) {
                String gaps = form.describeGaps();
                log.warn("Not applying for {} — {}", listing.getTitle(), gaps);
                record(listing, ApplicationStatus.SKIPPED, gaps, false);
                notify(
                        config,
                        "Auto-apply blocked",
                        listing.getTitle() + " needs your profile completed: " + gaps);
                return false;
            }

            Map<String, String> payload = new LinkedHashMap<>(form.fields());
            payload.put("postrequest", "processoptieform");
            // curtab is the only thing separating "save and next" from "send form".
            payload.put("curtab", "verklaring");

            // Our answers ride on top of the portal's prefill. Anything left unset here keeps
            // whatever MVGM already had, so a blank setting is safe rather than destructive.
            payload.putAll(answerService.overrides());

            // Income comes from Settings, not from the answers, because the same number decides the
            // rent ceiling. One source, so the ceiling we filter by and the figure MVGM reads can
            // never drift apart.
            if (config.getGrossMonthlyIncome() != null) {
                payload.put("a_maandinkomen", String.valueOf(config.getGrossMonthlyIncome()));
            }

            if (config.isAutoApplyDryRun()) {
                log.info(
                        "[DRY RUN] Would submit viewing request for {} ({} EUR, score {}) with {} fields: {}",
                        listing.getTitle(),
                        listing.getPrice(),
                        ListingScore.of(listing, config).total(),
                        payload.size(),
                        payload);
                record(listing, ApplicationStatus.DRY_RUN, "Dry run — nothing submitted", false);
                return true;
            }

            session.postAuthenticated(formUrl, payload, formUrl);

            // A 2xx is not proof: the portal must actually show the request as received.
            if (!isRequestConfirmed(hash)) {
                log.error("Submitted viewing request for {} but the portal does not show it", listing.getTitle());
                record(listing, ApplicationStatus.FAILED, "Portal did not confirm the request", false);
                notify(
                        config,
                        "Auto-apply failed",
                        "Submitted " + listing.getTitle() + " but the portal did not confirm it.");
                return false;
            }

            record(listing, ApplicationStatus.APPLIED, null, true);
            slotMoveRepository.save(
                    SlotMove.applied(
                            listing.getTitle(),
                            listing.getPrice(),
                            ListingScore.of(listing, config).total(),
                            ListingScore.of(listing, config).explain()));
            log.info(
                    "Viewing request submitted for {} ({} EUR, score {})",
                    listing.getTitle(),
                    listing.getPrice(),
                    ListingScore.of(listing, config).total());
            Pass p = pass.get();
            if (p != null) {
                p.applied.add(listing.getTitle() + " (EUR " + listing.getPrice() + "/mo)");
            } else {
                notify(
                        config,
                        "Viewing requested",
                        listing.getTitle() + " - EUR " + listing.getPrice() + "/mo. You are in the draw.");
            }
            return true;

        } catch (Exception e) {
            return failApply(listing, config, e);
        }
    }

    /** Report a genuine apply failure (not a routine "no form here"): log it, mark it, push once. */
    private boolean failApply(Listing listing, CrawlerConfig config, Exception e) {
        log.error("Auto-apply failed for {}", listing.getTitle(), e);
        record(listing, ApplicationStatus.FAILED, truncate(e.getMessage()), false);
        notify(config, "Auto-apply failed", listing.getTitle() + ": " + truncate(e.getMessage()));
        return false;
    }

    private void withdraw(Candidacy candidacy, CrawlerConfig config) {
        if (config.isAutoApplyDryRun()) {
            log.info("[DRY RUN] Would withdraw candidacy {} ({})", candidacy.uuid(), candidacy.label());
            return;
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("_token", session.csrfToken());
        form.put("postrequest", "verwijderkandidatuur");
        form.put("fromurl", "");
        form.put("uuid", candidacy.uuid());
        session.postAuthenticated(
                PortalStatusService.MIJN_WONING_URL, form, PortalStatusService.MIJN_WONING_URL);
    }

    private boolean isRequestConfirmed(String hash) {
        return portalStatus.candidacies().stream()
                .anyMatch(c -> hash.equals(c.objectHash()) && c.occupiesSlot());
    }

    private Map<String, Listing> listingsByHash() {
        Map<String, Listing> byHash = new LinkedHashMap<>();
        for (Listing l : listingRepository.findAll()) {
            String hash = l.objectHash();
            if (hash != null) {
                byHash.put(hash, l);
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
        notificationService.sendCrawlerStatusNotification(
                "[IkWilHuren] " + title, message, "house", config);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }
}

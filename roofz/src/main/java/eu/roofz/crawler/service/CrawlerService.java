package eu.roofz.crawler.service;

import eu.roofz.crawler.model.CrawlLog;
import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.Listing;
import eu.roofz.crawler.model.api.ApiListing;
import eu.roofz.crawler.repository.CrawlLogRepository;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import eu.roofz.crawler.repository.ListingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CrawlerService {

    private static final Logger log = LoggerFactory.getLogger(CrawlerService.class);

    /**
     * How many consecutive crawls a listing must be absent from the API before we delete it. A single
     * short or partial response would otherwise delete a home that still exists — and since the next
     * pass would re-add it as "new", that means a duplicate auto-apply on a property we had already
     * applied for. At a 10s interval this is ~30s of confirmation, which costs nothing.
     */
    private static final int MISSING_PASSES_BEFORE_PRUNE = 3;

    /** Consecutive passes each known listing has been absent from the API. Not worth persisting: a
     * restart just means a listing has to go missing a few more times before it is believed. */
    private final Map<Long, Integer> missedPasses = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Serialises crawls so the scheduled poll and a manual "Crawl now" can't run one on top of the
     * other. Two concurrent crawls do check-then-act on the same ids (existsById → save) and would race
     * into a primary-key-violation crash or a double notify/apply. Non-reentrant, tryLock: an
     * overlapping trigger skips instead of queuing.
     */
    private final java.util.concurrent.locks.ReentrantLock crawlLock =
            new java.util.concurrent.locks.ReentrantLock();

    private final RoofzApiClient apiClient;
    private final FilterService filterService;
    private final NotificationService notificationService;
    private final AutoApplyService autoApplyService;
    private final ListingRepository listingRepository;
    private final CrawlerConfigRepository configRepository;
    private final CrawlLogRepository crawlLogRepository;
    private final InquiryHistoryService inquiryHistoryService;

    public CrawlerService(
            RoofzApiClient apiClient,
            FilterService filterService,
            NotificationService notificationService,
            AutoApplyService autoApplyService,
            ListingRepository listingRepository,
            CrawlerConfigRepository configRepository,
            CrawlLogRepository crawlLogRepository,
            InquiryHistoryService inquiryHistoryService) {
        this.apiClient = apiClient;
        this.filterService = filterService;
        this.notificationService = notificationService;
        this.autoApplyService = autoApplyService;
        this.listingRepository = listingRepository;
        this.configRepository = configRepository;
        this.crawlLogRepository = crawlLogRepository;
        this.inquiryHistoryService = inquiryHistoryService;
    }

    /**
     * True while a listing we applied to is still inside the window in which {@link InviteWatchdog}
     * may raise "applied but no pre-application invite arrived". Deleting the row before then would
     * silently take away that alarm — the one that tells you an application never landed.
     */
    private boolean awaitingInviteWatchdog(Listing l, CrawlerConfig config, Instant now) {
        if (l.getAppliedAt() == null || l.isInviteWatchdogAlerted()) {
            return false; // never applied, or the watchdog has already had its say
        }
        Integer minutes = config.getInviteWatchdogMinutes();
        long window = minutes == null ? 90 : minutes;
        return l.getAppliedAt().isAfter(now.minus(java.time.Duration.ofMinutes(window)));
    }

    /** Entry point for both the scheduler and the manual "Crawl now"; skips if a crawl is already running. */
    @Transactional
    public CrawlResult crawl() {
        if (!crawlLock.tryLock()) {
            log.info("Crawl already in progress — skipping this overlapping trigger");
            return new CrawlResult(0, 0, 0, 0);
        }
        try {
            return doCrawl();
        } finally {
            crawlLock.unlock();
        }
    }

    private CrawlResult doCrawl() {
        CrawlerConfig config = configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);

        List<ApiListing> allListings = apiClient.fetchAllListings(config.getTargetCity());
        List<ApiListing> matching = filterService.applyFilters(allListings, config);

        // An empty repository means this is the first crawl on a fresh (or wiped) DB, so *every*
        // currently-available home reads as "new". Auto-applying to that whole backlog in one burst
        // is not what a first pass should do: on a first-ever start it floods the portal with a
        // suspicious wave of near-simultaneous applications (anti-bot risk), and after a DB wipe
        // (there are no backups) it re-applies to homes already dealt with. So the first crawl only
        // establishes the baseline — it saves what it sees but does not apply. Every later crawl,
        // where "new" genuinely means new-since-baseline, applies as usual.
        boolean firstRun = listingRepository.count() == 0;

        // Track which listings are new AND matching (these get notifications)
        List<Listing> newMatchingListings = new ArrayList<>();
        List<StatusChange> statusChangedListings = new ArrayList<>();

        for (ApiListing api : matching) {
            if (!listingRepository.existsById(api.id())) {
                Listing entity = mapToEntity(api);
                listingRepository.save(entity);
                newMatchingListings.add(entity);
            }
        }

        // Check for status changes on ALL existing listings
        for (ApiListing api : allListings) {
            listingRepository
                    .findById(api.id())
                    .ifPresent(
                            existing -> {
                                // Capture availability BEFORE this crawl refreshes the row, so we can
                                // tell when a home (re)enters an applyable state.
                                boolean wasAvailable = filterService.isAvailable(existing);
                                String oldStage = existing.getStage();
                                String newStage = api.stage();
                                boolean stageChanged = newStage != null && !newStage.equals(oldStage);
                                if (stageChanged) {
                                    existing.setStage(newStage);
                                    if ("option".equals(newStage) && existing.getOptionAt() == null) {
                                        existing.setOptionAt(Instant.now());
                                    }
                                    if ("occupied".equals(newStage) && existing.getOccupiedAt() == null) {
                                        existing.setOccupiedAt(Instant.now());
                                    }
                                    listingRepository.save(existing);
                                }
                                // Notify on any transition INTO an available+matching state, even when
                                // the coarse stage doesn't move: a home under option can reopen for
                                // backup applications (status.code flips to "available", stage stays
                                // "option"). Auto-apply stays on the new-listing path only.
                                boolean becameAvailable = !wasAvailable && filterService.isAvailable(api);
                                if ((stageChanged || becameAvailable) && filterService.matches(api, config)) {
                                    statusChangedListings.add(new StatusChange(existing, oldStage));
                                }
                            });
        }

        // Persist all remaining listings (non-matching) to avoid re-notification if filters change
        for (ApiListing api : allListings) {
            if (!listingRepository.existsById(api.id())) {
                Listing entity = mapToEntity(api);
                listingRepository.save(entity);
            }
        }

        // Refresh API-derived fields on every listing we know about so the stored snapshot
        // tracks Roofz over time (price changes, availability dates set after first listing,
        // etc.) and so any rows that predate a schema column get backfilled on the next crawl.
        for (ApiListing api : allListings) {
            Listing entity = listingRepository.findById(api.id()).orElse(null);
            if (entity == null) continue;
            if (applyApiFields(entity, api)) {
                listingRepository.save(entity);
            }
        }

        // Record inquiry counts for every listing in this response (compression handled by service).
        for (ApiListing api : allListings) {
            Integer inquiries = api.statistics() == null ? null : api.statistics().inquiries();
            inquiryHistoryService.record(api.id(), inquiries);
        }

        // Notifications and auto-apply are the crawl's irreversible side effects (a phone push, a real
        // application POST). They run at the very END of this method, AFTER prune + crawlLogRepository
        // .save — so that nothing which can throw runs *after* them inside this transaction. Otherwise a
        // later failure (a prune/crawl-log write) would roll back the DB record of an application that
        // was already submitted, and the next crawl would re-apply to the same home — a duplicate real
        // application. See dispatchSideEffects() below.

        // Prune listings that no longer appear in the API response — including ones we applied to.
        // A listing vanishes from Roofz when it is gone; if it ever comes back, it is genuinely open
        // again and applying again is the correct move, not a bug. So a delisted home does not linger
        // on the dashboard pretending to be a live match.
        //
        // Two things still have to hold:
        //   - never prune on an empty response (an API hiccup must not wipe the table); and
        //   - never prune on a single miss. A short/partial response would otherwise delete a home
        //     that still exists, re-add it on the next pass as "new", and fire a DUPLICATE
        //     auto-apply on a property we had already applied for. A listing must be absent for
        //     several consecutive passes before we believe it.
        int pruned = 0;
        if (!allListings.isEmpty()) {
            Set<Long> currentIds = new HashSet<>(allListings.size());
            for (ApiListing api : allListings) currentIds.add(api.id());

            // Anything the API still lists is present: forget any earlier misses.
            missedPasses.keySet().removeIf(currentIds::contains);

            Instant now = Instant.now();
            List<Listing> stale = new ArrayList<>();
            for (Listing l : listingRepository.findAll()) {
                if (currentIds.contains(l.getId())) {
                    continue;
                }
                int misses = missedPasses.merge(l.getId(), 1, Integer::sum);
                if (misses < MISSING_PASSES_BEFORE_PRUNE) {
                    continue;
                }
                // Keep a just-applied home until its invite watchdog has had its chance to fire.
                // Deleting it first would silently drop the "applied but no invite" alarm.
                if (awaitingInviteWatchdog(l, config, now)) {
                    continue;
                }
                stale.add(l);
            }

            if (!stale.isEmpty()) {
                List<Long> staleIds = stale.stream().map(Listing::getId).toList();
                // inquiry_history has no owner once its listing is gone; nothing else deletes it, so
                // without this the orphans grow forever — the same trap crawl_log fell into.
                inquiryHistoryService.deleteForListings(staleIds);
                listingRepository.deleteAll(stale);
                staleIds.forEach(missedPasses::remove);
                pruned = stale.size();
                log.info("Pruned {} delisted listing(s): {}", pruned, staleIds);
            }
        }

        boolean changed =
                newMatchingListings.size() + statusChangedListings.size() + pruned > 0;
        if (changed) {
            log.info(
                    "Crawl: total={} matching={} new={} statusChanged={} pruned={}",
                    allListings.size(),
                    matching.size(),
                    newMatchingListings.size(),
                    statusChangedListings.size(),
                    pruned);
        } else {
            log.debug(
                    "Crawl: total={} matching={} (no changes)",
                    allListings.size(),
                    matching.size());
        }

        CrawlResult result =
                new CrawlResult(
                        allListings.size(),
                        matching.size(),
                        newMatchingListings.size(),
                        statusChangedListings.size());

        crawlLogRepository.save(
                new CrawlLog(result.total(), result.matching(), result.newCount(), result.statusChanged()));

        // --- Side effects last: nothing that can throw runs after these (see the note above). ---

        // Send notifications
        if (config.isNotificationsEnabled()) {
            boolean sent = notificationService.sendNewListingsNotification(newMatchingListings, config);
            if (sent) {
                for (Listing listing : newMatchingListings) {
                    listing.setNotificationSent(true);
                    listingRepository.save(listing);
                }
            }
            for (StatusChange change : statusChangedListings) {
                // isAvailable, not stage=="available": a home reopened under option is applyable too.
                // applyApiFields ran above, so the entity's statusCode reflects this crawl.
                if (filterService.isAvailable(change.listing())) {
                    notificationService.sendStatusChangeNotification(
                            change.listing(), change.oldStage(), config);
                }
            }
        }

        // Auto-apply for new matching listings, and for homes that just (re)entered an applyable
        // state — the same transitions we notify about just above. Without this, a matching home
        // first seen under option/occupied (so never a "new" listing) that later reopens is buzzed
        // to the user but never applied to, on a first-come portal where speed is the whole point.
        // applyFor() is idempotent on appliedAt, and newMatchingListings vs statusChangedListings
        // are disjoint (new id vs existing id), so nothing is applied to twice.
        if (config.isAutoApplyEnabled() && firstRun) {
            log.warn(
                    "First crawl on an empty database ({} matching homes) — establishing baseline, "
                            + "not auto-applying to the pre-existing backlog. Applies resume next crawl.",
                    newMatchingListings.size());
        } else if (config.isAutoApplyEnabled()) {
            for (Listing listing : newMatchingListings) {
                autoApplyService.applyFor(listing, config);
            }
            for (StatusChange change : statusChangedListings) {
                if (filterService.isAvailable(change.listing())) {
                    autoApplyService.applyFor(change.listing(), config);
                }
            }
        }

        return result;
    }

    private Listing mapToEntity(ApiListing api) {
        Listing listing = new Listing();
        listing.setId(api.id());
        listing.setStage(api.stage());
        listing.setFirstSeenAt(Instant.now());
        listing.setNotificationSent(false);
        applyApiFields(listing, api);
        return listing;
    }

    /**
     * Copies API-derived mutable fields from {@code api} onto {@code listing}. Does not touch
     * stage (the stage-change handler owns that, together with optionAt/occupiedAt),
     * firstSeenAt, notificationSent, or application bookkeeping. Returns true if any field
     * value actually changed, so callers can avoid no-op writes.
     */
    boolean applyApiFields(Listing listing, ApiListing api) {
        boolean changed = false;
        changed |= setIfDifferent(listing.getSlug(), api.slug(), listing::setSlug);
        changed |= setIfDifferent(listing.getTitle(), api.title(), listing::setTitle);

        String city = api.address() == null ? null : api.address().location();
        changed |= setIfDifferent(listing.getCity(), city, listing::setCity);

        Integer livingArea = api.characteristic() == null ? null : api.characteristic().livingArea();
        changed |= setIfDifferent(listing.getLivingArea(), livingArea, listing::setLivingArea);

        String subtype = api.characteristic() == null ? null : api.characteristic().subtype();
        changed |= setIfDifferent(listing.getSubtype(), subtype, listing::setSubtype);

        Integer bedrooms =
                api.characteristic() != null && api.characteristic().layout() != null
                        ? api.characteristic().layout().numberOfBedrooms()
                        : null;
        changed |= setIfDifferent(listing.getBedrooms(), bedrooms, listing::setBedrooms);

        Integer price = api.handover() == null ? null : api.handover().price();
        changed |= setIfDifferent(listing.getPrice(), price, listing::setPrice);

        Integer serviceCosts = api.handover() == null ? null : api.handover().serviceCosts();
        changed |= setIfDifferent(listing.getServiceCosts(), serviceCosts, listing::setServiceCosts);

        String imageUrl = api.media() == null ? null : api.media().primaryPhoto();
        changed |= setIfDifferent(listing.getImageUrl(), imageUrl, listing::setImageUrl);

        Instant availableAt = api.availableAt() == null ? null : Instant.ofEpochMilli(api.availableAt());
        changed |= setIfDifferent(listing.getAvailableAt(), availableAt, listing::setAvailableAt);

        Instant publishedAt = api.publishedAt() == null ? null : Instant.ofEpochMilli(api.publishedAt());
        changed |= setIfDifferent(listing.getPublishedAt(), publishedAt, listing::setPublishedAt);

        String statusCode = api.status() == null ? null : api.status().code();
        changed |= setIfDifferent(listing.getStatusCode(), statusCode, listing::setStatusCode);

        changed |=
                setIfDifferent(
                        listing.getExternalSubscriptionUrl(),
                        api.externalSubscriptionUrl(),
                        listing::setExternalSubscriptionUrl);

        Boolean allowSubscription =
                api.project() == null ? null : api.project().allowPropertySubscription();
        changed |=
                setIfDifferent(
                        listing.getAllowPropertySubscription(),
                        allowSubscription,
                        listing::setAllowPropertySubscription);

        return changed;
    }

    private static <T> boolean setIfDifferent(T current, T incoming, java.util.function.Consumer<T> setter) {
        if (java.util.Objects.equals(current, incoming)) return false;
        setter.accept(incoming);
        return true;
    }

    private record StatusChange(Listing listing, String oldStage) {}

    public record CrawlResult(int total, int matching, int newCount, int statusChanged) {}
}

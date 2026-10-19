package eu.ikwilhuren.crawler.service;

import eu.ikwilhuren.crawler.model.CrawlLog;
import eu.ikwilhuren.crawler.model.CrawlerConfig;
import eu.ikwilhuren.crawler.model.Listing;
import eu.ikwilhuren.crawler.repository.CrawlLogRepository;
import eu.ikwilhuren.crawler.repository.CrawlerConfigRepository;
import eu.ikwilhuren.crawler.repository.ListingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CrawlerService {

    private static final Logger log = LoggerFactory.getLogger(CrawlerService.class);

    private final IkwilhurenScraper scraper;
    private final FilterService filterService;
    private final NotificationService notificationService;
    private final ListingRepository listingRepository;
    private final CrawlerConfigRepository configRepository;
    private final CrawlLogRepository crawlLogRepository;

    /**
     * Serialises crawls so the scheduled poll and a manual "Crawl now" can't run one on top of the
     * other — two concurrent crawls do check-then-act on the same ids and would race into a
     * primary-key-violation crash or double work. Non-reentrant, tryLock: an overlap skips.
     */
    private final java.util.concurrent.locks.ReentrantLock crawlLock =
            new java.util.concurrent.locks.ReentrantLock();

    public CrawlerService(
            IkwilhurenScraper scraper,
            FilterService filterService,
            NotificationService notificationService,
            ListingRepository listingRepository,
            CrawlerConfigRepository configRepository,
            CrawlLogRepository crawlLogRepository) {
        this.scraper = scraper;
        this.filterService = filterService;
        this.notificationService = notificationService;
        this.listingRepository = listingRepository;
        this.configRepository = configRepository;
        this.crawlLogRepository = crawlLogRepository;
    }

    @Transactional
    public CrawlResult crawl() {
        return crawl(IkwilhurenScraper.DEFAULT_MAX_PAGES);
    }

    @Transactional
    public CrawlResult crawl(int maxPages) {
        if (!crawlLock.tryLock()) {
            log.info("Crawl already in progress — skipping this overlapping trigger");
            return new CrawlResult(0, 0, 0, 0);
        }
        try {
            return doCrawl(maxPages);
        } finally {
            crawlLock.unlock();
        }
    }

    private CrawlResult doCrawl(int maxPages) {
        CrawlerConfig config = configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);

        IkwilhurenScraper.FetchResult fetchResult = scraper.fetchAllListings(maxPages);
        List<ScrapedListing> allListings = fetchResult.listings();
        List<ScrapedListing> matching = filterService.applyFilters(allListings, config);

        List<Listing> newMatchingListings = new ArrayList<>();
        List<StatusChange> statusChangedListings = new ArrayList<>();

        for (ScrapedListing s : matching) {
            if (!listingRepository.existsById(s.id())) {
                Listing entity = mapToEntity(s);
                listingRepository.save(entity);
                newMatchingListings.add(entity);
            }
        }

        for (ScrapedListing s : allListings) {
            listingRepository
                    .findById(s.id())
                    .ifPresent(
                            existing -> {
                                boolean dirty = false;
                                String oldStage = existing.getStage();
                                String newStage = s.stage();
                                if (newStage != null && !newStage.equals(oldStage)) {
                                    existing.setStage(newStage);
                                    dirty = true;
                                    if (filterService.matches(s, config)) {
                                        statusChangedListings.add(
                                                new StatusChange(existing, oldStage));
                                    }
                                }
                                // Rent and size are not fixed for the life of a listing — the portal
                                // re-prices homes while they sit on the market. Only stage used to be
                                // refreshed here, so a home kept its first-seen price forever, both on
                                // the dashboard and (worse) in the allocator: a stale rent decides
                                // whether the home clears the income ceiling — a price drop leaves an
                                // affordable home permanently filtered out (missed application), a rise
                                // leaves us holding a slot on a home that will reject us on the 4x rule.
                                // vesteda already refreshes these; mirror it here.
                                if (!Objects.equals(existing.getPrice(), s.price())) {
                                    existing.setPrice(s.price());
                                    dirty = true;
                                }
                                if (!Objects.equals(existing.getLivingArea(), s.livingArea())) {
                                    existing.setLivingArea(s.livingArea());
                                    dirty = true;
                                }
                                if (!Objects.equals(existing.getBedrooms(), s.bedrooms())) {
                                    existing.setBedrooms(s.bedrooms());
                                    dirty = true;
                                }
                                if (dirty) {
                                    listingRepository.save(existing);
                                }
                            });
        }

        for (ScrapedListing s : allListings) {
            if (!config.matchesCity(s.city())) {
                continue;
            }
            if (!listingRepository.existsById(s.id())) {
                listingRepository.save(mapToEntity(s));
            }
        }

        int removedCount = 0;
        if (maxPages >= IkwilhurenScraper.DEFAULT_MAX_PAGES && fetchResult.complete()) {
            Set<String> scrapedIds =
                    allListings.stream().map(ScrapedListing::id).collect(Collectors.toSet());
            List<Listing> nowRemoved =
                    listingRepository.findAll().stream()
                            .filter(
                                    l ->
                                            "available".equals(l.getStage())
                                                    && !scrapedIds.contains(l.getId()))
                            .toList();
            for (Listing l : nowRemoved) {
                l.setStage("removed");
                listingRepository.save(l);
            }
            removedCount = nowRemoved.size();

            if (!config.getTargetCities().isEmpty()) {
                // Keep the store to the cities we actually search. Done in code rather than a single
                // "city != ?" query so it honours the full list of target cities — a single-city
                // delete would wipe every Diemen and Amstelveen home the moment we added them.
                List<Listing> offArea =
                        listingRepository.findAll().stream()
                                .filter(l -> !l.isApplied() && !config.matchesCity(l.getCity()))
                                .toList();
                if (!offArea.isEmpty()) {
                    listingRepository.deleteAll(offArea);
                    log.info("Purged {} listings outside {}", offArea.size(), config.getTargetCity());
                }
            }
        }

        log.info(
                "Crawl complete: {} total, {} matching, {} new, {} status changed, {} removed",
                allListings.size(),
                matching.size(),
                newMatchingListings.size(),
                statusChangedListings.size(),
                removedCount);

        CrawlResult result =
                new CrawlResult(
                        allListings.size(),
                        matching.size(),
                        newMatchingListings.size(),
                        statusChangedListings.size());

        crawlLogRepository.save(
                new CrawlLog(
                        result.total(), result.matching(), result.newCount(), result.statusChanged()));

        // Notifications run LAST, after every DB write that can throw (the removals/purge above and the
        // crawl-log save), so a later failure can't roll back the record of a notification we already
        // sent and cause it to re-fire next crawl.
        // "A home appeared" is only news while nobody is acting on it. With auto-apply on, the
        // allocator is about to do something about this very home and will say so — announcing it
        // here as well turns one event into two notifications that say the same thing.
        if (config.isNotificationsEnabled() && !config.isAutoApplyEnabled()) {
            for (Listing listing : newMatchingListings) {
                boolean sent = notificationService.sendNewListingNotification(listing, config);
                if (sent) {
                    listing.setNotificationSent(true);
                    listingRepository.save(listing);
                }
            }
            for (StatusChange change : statusChangedListings) {
                if ("available".equals(change.listing().getStage())) {
                    notificationService.sendStatusChangeNotification(
                            change.listing(), change.oldStage(), config);
                }
            }
        }

        return result;
    }

    private Listing mapToEntity(ScrapedListing s) {
        Listing listing = new Listing();
        listing.setId(s.id());
        listing.setSlug(s.slug());
        listing.setTitle(s.title());
        listing.setCity(s.city());
        listing.setPostalCode(s.postalCode());
        listing.setLivingArea(s.livingArea());
        listing.setBedrooms(s.bedrooms());
        listing.setPrice(s.price());
        listing.setStage(s.stage());
        listing.setImageUrl(s.imageUrl());
        listing.setFirstSeenAt(Instant.now());
        listing.setNotificationSent(false);
        listing.setApplied(false);
        listing.setNieuw(s.nieuw());
        return listing;
    }

    private record StatusChange(Listing listing, String oldStage) {}

    public record CrawlResult(int total, int matching, int newCount, int statusChanged) {}
}

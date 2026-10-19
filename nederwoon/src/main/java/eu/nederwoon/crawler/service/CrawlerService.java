package eu.nederwoon.crawler.service;

import eu.nederwoon.crawler.model.CrawlLog;
import eu.nederwoon.crawler.model.CrawlerConfig;
import eu.nederwoon.crawler.model.Listing;
import eu.nederwoon.crawler.repository.CrawlLogRepository;
import eu.nederwoon.crawler.repository.CrawlerConfigRepository;
import eu.nederwoon.crawler.repository.ListingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CrawlerService {

    private static final Logger log = LoggerFactory.getLogger(CrawlerService.class);

    private final NederwoonClient apiClient;
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
            NederwoonClient apiClient,
            FilterService filterService,
            NotificationService notificationService,
            ListingRepository listingRepository,
            CrawlerConfigRepository configRepository,
            CrawlLogRepository crawlLogRepository) {
        this.apiClient = apiClient;
        this.filterService = filterService;
        this.notificationService = notificationService;
        this.listingRepository = listingRepository;
        this.configRepository = configRepository;
        this.crawlLogRepository = crawlLogRepository;
    }

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
        boolean firstRun = listingRepository.count() == 0;

        List<ScrapedListing> allListings = apiClient.fetchAllListings();
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
            listingRepository.findById(s.id()).ifPresent(existing -> {
                String oldStage = existing.getStage();
                String newStage = s.stage();
                boolean dirty = false;
                if (newStage != null && !newStage.equals(oldStage)) {
                    existing.setStage(newStage);
                    dirty = true;
                    if (filterService.matches(s, config)) {
                        statusChangedListings.add(new StatusChange(existing, oldStage));
                    }
                }
                // Refresh rent/size on re-crawl, not just stage: the portal re-prices homes while
                // they sit on the market, and only the stage used to be updated here — so a home kept
                // its first-seen rent/area/bedrooms forever, feeding a stale figure to the dashboard
                // and to the filter that gates status-change notifications.
                if (!java.util.Objects.equals(existing.getPrice(), s.price())) {
                    existing.setPrice(s.price());
                    dirty = true;
                }
                if (!java.util.Objects.equals(existing.getLivingArea(), s.livingArea())) {
                    existing.setLivingArea(s.livingArea());
                    dirty = true;
                }
                if (!java.util.Objects.equals(existing.getBedrooms(), s.bedrooms())) {
                    existing.setBedrooms(s.bedrooms());
                    dirty = true;
                }
                if (dirty) {
                    listingRepository.save(existing);
                }
            });
        }

        String targetCity = config.getTargetCity();
        for (ScrapedListing s : allListings) {
            if (targetCity != null && !targetCity.isBlank()
                    && (s.city() == null || !targetCity.equalsIgnoreCase(s.city()))) {
                continue;
            }
            if (!listingRepository.existsById(s.id())) {
                listingRepository.save(mapToEntity(s));
            }
        }

        Set<String> scrapedIds = allListings.stream().map(ScrapedListing::id).collect(Collectors.toSet());
        List<Listing> nowRemoved = listingRepository.findAll().stream()
                .filter(l -> "available".equals(l.getStage()) && !scrapedIds.contains(l.getId()))
                .toList();
        for (Listing l : nowRemoved) {
            l.setStage("removed");
            listingRepository.save(l);
        }
        int removedCount = nowRemoved.size();

        log.info("Crawl complete: {} total, {} matching, {} new, {} status changed, {} removed",
                allListings.size(), matching.size(), newMatchingListings.size(),
                statusChangedListings.size(), removedCount);

        CrawlResult result = new CrawlResult(
                allListings.size(), matching.size(), newMatchingListings.size(),
                statusChangedListings.size());

        crawlLogRepository.save(new CrawlLog(
                result.total(), result.matching(), result.newCount(), result.statusChanged()));

        // Notifications run LAST, after every DB write that can throw (the removal loop and the
        // crawl-log save), so a later failure can't roll back the record of a notification we already
        // sent and cause it to re-fire next crawl.
        if (config.isNotificationsEnabled() && !firstRun) {
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
        return listing;
    }

    private record StatusChange(Listing listing, String oldStage) {}

    public record CrawlResult(int total, int matching, int newCount, int statusChanged) {}
}

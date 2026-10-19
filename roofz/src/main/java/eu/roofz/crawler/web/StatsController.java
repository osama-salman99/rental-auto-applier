package eu.roofz.crawler.web;

import eu.roofz.crawler.model.CrawlLog;
import eu.roofz.crawler.model.CrawlLogRollup;
import eu.roofz.crawler.model.InquiryHistory;
import eu.roofz.crawler.model.KnownProject;
import eu.roofz.crawler.model.Listing;
import eu.roofz.crawler.repository.CrawlLogRepository;
import eu.roofz.crawler.repository.CrawlLogRollupRepository;
import eu.roofz.crawler.repository.InquiryHistoryRepository;
import eu.roofz.crawler.repository.KnownProjectRepository;
import eu.roofz.crawler.repository.ListingRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class StatsController {

    private final CrawlLogRepository crawlLogRepository;
    private final CrawlLogRollupRepository crawlLogRollupRepository;
    private final ListingRepository listingRepository;
    private final InquiryHistoryRepository inquiryHistoryRepository;
    private final KnownProjectRepository knownProjectRepository;

    public StatsController(
            CrawlLogRepository crawlLogRepository,
            CrawlLogRollupRepository crawlLogRollupRepository,
            ListingRepository listingRepository,
            InquiryHistoryRepository inquiryHistoryRepository,
            KnownProjectRepository knownProjectRepository) {
        this.crawlLogRepository = crawlLogRepository;
        this.crawlLogRollupRepository = crawlLogRollupRepository;
        this.listingRepository = listingRepository;
        this.inquiryHistoryRepository = inquiryHistoryRepository;
        this.knownProjectRepository = knownProjectRepository;
    }

    private static final Logger log = LoggerFactory.getLogger(StatsController.class);

    /** How long a computed snapshot may be served before a viewer's request recomputes it. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    /**
     * How long after the last view the background job keeps refreshing. Outside this window nobody is
     * looking, so it does nothing: the old job recomputed unconditionally every 45s forever (~1,900
     * full aggregate passes a day for a page nobody had open), which is what kept the heap full.
     */
    private static final Duration WARM_WINDOW = Duration.ofMinutes(10);

    private volatile Map<String, Object> cache;
    private volatile Instant computedAt;
    private volatile Instant lastViewedAt;

    @GetMapping("/stats")
    public String stats(Model model) {
        Instant now = Instant.now();
        lastViewedAt = now;

        Map<String, Object> data = cache;
        if (data == null || computedAt == null || computedAt.isBefore(now.minus(CACHE_TTL))) {
            data = computeStats();
            cache = data;
            computedAt = now;
        }
        model.addAllAttributes(data);
        return "stats";
    }

    /**
     * Keeps the snapshot warm off the request thread <em>while someone is actually watching</em>, so
     * an active viewer's page loads are instant and an idle app does no work at all.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "45000", initialDelay = 3000)
    public void refreshStatsCache() {
        Instant viewed = lastViewedAt;
        if (viewed == null || viewed.isBefore(Instant.now().minus(WARM_WINDOW))) {
            return; // nobody is on /stats — don't burn a full aggregate pass
        }
        try {
            cache = computeStats();
            computedAt = Instant.now();
        } catch (Exception e) {
            log.warn("Stats cache refresh failed: {}", e.getMessage());
        }
    }

    private Map<String, Object> computeStats() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        Instant last24h = Instant.now().minus(24, ChronoUnit.HOURS);

        // Lifetime crawl figures = what retention has already archived + what is still in the table.
        // Without the rollup, pruning crawl_log would make these counters jump backwards.
        CrawlLogRollup rollup =
                crawlLogRollupRepository
                        .findById(CrawlLogRollup.SINGLETON_ID)
                        .orElseGet(CrawlLogRollup::new);

        long totalCrawls = rollup.getArchivedSuccess() + crawlLogRepository.countBySuccessTrue();
        long failedCrawls = rollup.getArchivedFailed() + crawlLogRepository.countBySuccessFalse();
        long crawlsLast24h = crawlLogRepository.countByTimestampAfter(last24h);
        Long totalNewDiscovered = crawlLogRepository.totalNewListingsDiscovered();
        Long totalStatusChanges = crawlLogRepository.totalStatusChanges();
        Long newLast24h = crawlLogRepository.newListingsSince(last24h);
        Long statusLast24h = crawlLogRepository.statusChangesSince(last24h);

        data.put("totalCrawls", totalCrawls);
        data.put("failedCrawls", failedCrawls);
        data.put("crawlsLast24h", crawlsLast24h);
        data.put(
                "totalNewDiscovered",
                rollup.getArchivedNewListings() + (totalNewDiscovered != null ? totalNewDiscovered : 0));
        data.put(
                "totalStatusChanges",
                rollup.getArchivedStatusChanges() + (totalStatusChanges != null ? totalStatusChanges : 0));
        data.put("newLast24h", newLast24h != null ? newLast24h : 0);
        data.put("statusLast24h", statusLast24h != null ? statusLast24h : 0);

        // Listing breakdowns
        var allListings = listingRepository.findAll();
        long totalListings = allListings.size();
        long notificationsSent = allListings.stream().filter(l -> l.isNotificationSent()).count();

        Map<String, Long> byCity =
                allListings.stream()
                        .collect(
                                Collectors.groupingBy(
                                        l -> l.getCity() != null ? l.getCity() : "Unknown",
                                        Collectors.counting()));

        Map<String, Long> byStage =
                allListings.stream()
                        .collect(
                                Collectors.groupingBy(
                                        l -> l.getStage() != null ? l.getStage() : "unknown",
                                        Collectors.counting()));

        var withArea = allListings.stream().filter(l -> l.getLivingArea() != null).toList();
        var withPrice = allListings.stream().filter(l -> l.getPrice() != null).toList();

        data.put("totalListings", totalListings);
        data.put("notificationsSent", notificationsSent);
        data.put("byCity", byCity);
        data.put("byStage", byStage);
        data.put(
                "avgArea",
                withArea.isEmpty()
                        ? 0
                        : withArea.stream().mapToInt(l -> l.getLivingArea()).average().orElse(0));
        data.put(
                "avgPrice",
                withPrice.isEmpty()
                        ? 0
                        : withPrice.stream().mapToInt(l -> l.getPrice()).average().orElse(0));
        data.put(
                "minArea",
                withArea.stream().mapToInt(l -> l.getLivingArea()).min().orElse(0));
        data.put(
                "maxArea",
                withArea.stream().mapToInt(l -> l.getLivingArea()).max().orElse(0));
        data.put(
                "minPrice",
                withPrice.stream().mapToInt(l -> l.getPrice()).min().orElse(0));
        data.put(
                "maxPrice",
                withPrice.stream().mapToInt(l -> l.getPrice()).max().orElse(0));

        // Recent crawl log
        List<CrawlLog> recentCrawls = crawlLogRepository.findTop10ByOrderByTimestampDesc();
        data.put("recentCrawls", recentCrawls);

        // Time-to-option per subtype + overall median.
        List<Listing> withOption =
                allListings.stream()
                        .filter(l -> l.getOptionAt() != null && l.getPublishedAt() != null)
                        .toList();

        Map<String, List<Long>> minutesBySubtype = new java.util.HashMap<>();
        for (Listing l : withOption) {
            long mins = Duration.between(l.getPublishedAt(), l.getOptionAt()).toMinutes();
            if (mins < 0) continue;
            minutesBySubtype
                    .computeIfAbsent(l.getSubtype() == null ? "unknown" : l.getSubtype(), k -> new ArrayList<>())
                    .add(mins);
        }

        Map<String, Long> medianTimeToOptionBySubtype = new java.util.LinkedHashMap<>();
        minutesBySubtype.forEach((k, v) -> medianTimeToOptionBySubtype.put(k, median(v)));
        Long overallMedianTimeToOption =
                median(withOption.stream()
                        .map(l -> Duration.between(l.getPublishedAt(), l.getOptionAt()).toMinutes())
                        .filter(m -> m >= 0)
                        .toList());

        // Inquiries-at-option: for each listing with optionAt, find the most recent inquiry record
        // at or before that timestamp. One batched query + in-memory grouping instead of a query per
        // listing (the per-listing loop was the N+1 that made /stats take ~2.6s).
        List<Integer> inquiriesAtOption = new ArrayList<>();
        if (!withOption.isEmpty()) {
            List<Long> optionIds = withOption.stream().map(Listing::getId).toList();
            Map<Long, List<InquiryHistory>> historyByListing =
                    inquiryHistoryRepository.findByListingIdIn(optionIds).stream()
                            .collect(Collectors.groupingBy(InquiryHistory::getListingId));
            for (Listing l : withOption) {
                historyByListing.getOrDefault(l.getId(), List.of()).stream()
                        .filter(r -> !r.getObservedAt().isAfter(l.getOptionAt()))
                        .max(Comparator.comparing(InquiryHistory::getObservedAt))
                        .map(InquiryHistory::getInquiries)
                        .ifPresent(inquiriesAtOption::add);
            }
        }
        Long medianInquiriesAtOption =
                median(inquiriesAtOption.stream().map(Integer::longValue).toList());

        List<KnownProject> projects = knownProjectRepository.findAll();

        data.put("medianTimeToOptionBySubtype", medianTimeToOptionBySubtype);
        data.put("overallMedianTimeToOption", overallMedianTimeToOption);
        data.put("medianInquiriesAtOption", medianInquiriesAtOption);
        data.put("inquiriesAtOptionSampleSize", inquiriesAtOption.size());
        data.put("knownProjects", projects);

        return data;
    }

    private static Long median(List<Long> values) {
        if (values == null || values.isEmpty()) return null;
        List<Long> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}

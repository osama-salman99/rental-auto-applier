package eu.ikwilhuren.crawler.web;

import eu.ikwilhuren.crawler.model.CrawlLog;
import eu.ikwilhuren.crawler.model.CrawlLogRollup;
import eu.ikwilhuren.crawler.repository.CrawlLogRepository;
import eu.ikwilhuren.crawler.repository.CrawlLogRollupRepository;
import eu.ikwilhuren.crawler.repository.CrawlStats;
import eu.ikwilhuren.crawler.repository.ListingRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class StatsController {

    private final CrawlLogRepository crawlLogRepository;
    private final CrawlLogRollupRepository crawlLogRollupRepository;
    private final ListingRepository listingRepository;

    public StatsController(
            CrawlLogRepository crawlLogRepository,
            CrawlLogRollupRepository crawlLogRollupRepository,
            ListingRepository listingRepository) {
        this.crawlLogRepository = crawlLogRepository;
        this.crawlLogRollupRepository = crawlLogRollupRepository;
        this.listingRepository = listingRepository;
    }

    @GetMapping("/stats")
    public String stats(Model model) {
        Instant last24h = Instant.now().minus(24, ChronoUnit.HOURS);

        CrawlStats crawls = crawlLogRepository.stats(last24h);

        // Lifetime figures = what retention has already archived + what is still in the table.
        // Without the rollup, pruning crawl_log would make these counters jump backwards.
        CrawlLogRollup rollup =
                crawlLogRollupRepository
                        .findById(CrawlLogRollup.SINGLETON_ID)
                        .orElseGet(CrawlLogRollup::new);

        model.addAttribute("totalCrawls", rollup.getArchivedSuccess() + crawls.successful());
        model.addAttribute("failedCrawls", rollup.getArchivedFailed() + crawls.failed());
        model.addAttribute("crawlsLast24h", crawls.crawlsLast24h());
        model.addAttribute(
                "totalNewDiscovered", rollup.getArchivedNewListings() + crawls.newListings());
        model.addAttribute(
                "totalStatusChanges", rollup.getArchivedStatusChanges() + crawls.statusChanges());
        model.addAttribute("newLast24h", crawls.newListingsLast24h());
        model.addAttribute("statusLast24h", crawls.statusChangesLast24h());

        var allListings = listingRepository.findAll();
        long totalListings = allListings.size();
        long notificationsSent = allListings.stream().filter(l -> l.isNotificationSent()).count();
        long appliedCount = allListings.stream().filter(l -> l.isApplied()).count();

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

        model.addAttribute("totalListings", totalListings);
        model.addAttribute("notificationsSent", notificationsSent);
        model.addAttribute("appliedCount", appliedCount);
        model.addAttribute("byCity", byCity);
        model.addAttribute("byStage", byStage);
        model.addAttribute(
                "avgArea",
                withArea.isEmpty()
                        ? 0
                        : withArea.stream().mapToInt(l -> l.getLivingArea()).average().orElse(0));
        model.addAttribute(
                "avgPrice",
                withPrice.isEmpty()
                        ? 0
                        : withPrice.stream().mapToInt(l -> l.getPrice()).average().orElse(0));
        model.addAttribute(
                "minArea", withArea.stream().mapToInt(l -> l.getLivingArea()).min().orElse(0));
        model.addAttribute(
                "maxArea", withArea.stream().mapToInt(l -> l.getLivingArea()).max().orElse(0));
        model.addAttribute(
                "minPrice", withPrice.stream().mapToInt(l -> l.getPrice()).min().orElse(0));
        model.addAttribute(
                "maxPrice", withPrice.stream().mapToInt(l -> l.getPrice()).max().orElse(0));

        List<CrawlLog> recentCrawls = crawlLogRepository.findTop10ByOrderByTimestampDesc();
        model.addAttribute("recentCrawls", recentCrawls);

        return "stats";
    }
}

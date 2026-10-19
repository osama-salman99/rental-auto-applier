package eu.nederwoon.crawler.web;

import eu.nederwoon.crawler.model.CrawlLog;
import eu.nederwoon.crawler.model.CrawlerConfig;
import eu.nederwoon.crawler.model.Listing;
import eu.nederwoon.crawler.repository.CrawlLogRepository;
import eu.nederwoon.crawler.repository.CrawlerConfigRepository;
import eu.nederwoon.crawler.repository.ListingRepository;
import eu.nederwoon.crawler.scheduler.CrawlerScheduler;
import eu.nederwoon.crawler.service.CrawlerService;
import eu.nederwoon.crawler.service.FailureNotifier;
import eu.nederwoon.crawler.service.FilterService;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class DashboardController {

    private final CrawlerConfigRepository configRepository;
    private final ListingRepository listingRepository;
    private final CrawlLogRepository crawlLogRepository;
    private final CrawlerService crawlerService;
    private final FilterService filterService;
    private final FailureNotifier failureNotifier;

    public DashboardController(
            CrawlerConfigRepository configRepository,
            ListingRepository listingRepository,
            CrawlLogRepository crawlLogRepository,
            CrawlerService crawlerService,
            FilterService filterService,
            FailureNotifier failureNotifier) {
        this.configRepository = configRepository;
        this.listingRepository = listingRepository;
        this.crawlLogRepository = crawlLogRepository;
        this.crawlerService = crawlerService;
        this.filterService = filterService;
        this.failureNotifier = failureNotifier;
    }

    @ModelAttribute("config")
    public CrawlerConfig loadConfig() {
        return configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
    }

    @GetMapping("/")
    public String dashboard(
            @ModelAttribute("config") CrawlerConfig config,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            Model model) {
        List<Listing> listings = listingRepository.findAll();

        Set<String> matchingIds = listings.stream()
                .filter(l -> filterService.matches(l, config))
                .map(Listing::getId)
                .collect(Collectors.toSet());

        boolean asc = "asc".equalsIgnoreCase(dir);
        listings.sort(buildComparator(sort, asc, matchingIds));

        model.addAttribute("listings", listings);
        model.addAttribute("matchingIds", matchingIds);
        model.addAttribute("currentSort", sort != null ? sort : "");
        model.addAttribute("currentDir", dir != null ? dir : "");
        return "dashboard";
    }

    private Comparator<Listing> buildComparator(String sort, boolean asc, Set<String> matchingIds) {
        if (sort == null || sort.isEmpty()) {
            Comparator<Listing> byMatch = Comparator.comparingInt(l -> matchingIds.contains(l.getId()) ? 0 : 1);
            Comparator<Listing> byAvailability = Comparator.comparingInt(l -> "available".equals(l.getStage()) ? 0 : 1);
            Comparator<Listing> byArea = Comparator.comparing(Listing::getLivingArea, Comparator.nullsLast(Comparator.reverseOrder()));
            Comparator<Listing> byFirstSeen = Comparator.comparing(Listing::getFirstSeenAt, Comparator.nullsLast(Comparator.reverseOrder()));
            return byMatch.thenComparing(byAvailability).thenComparing(byArea).thenComparing(byFirstSeen);
        }
        Comparator<Listing> c = switch (sort) {
            case "match"    -> Comparator.comparingInt((Listing l) -> matchingIds.contains(l.getId()) ? 0 : 1);
            case "title"    -> Comparator.comparing(Listing::getTitle, Comparator.nullsLast(Comparator.naturalOrder()));
            case "city"     -> Comparator.comparing(Listing::getCity, Comparator.nullsLast(Comparator.naturalOrder()));
            case "area"     -> Comparator.comparing(Listing::getLivingArea, Comparator.nullsLast(Comparator.naturalOrder()));
            case "beds"     -> Comparator.comparing(Listing::getBedrooms, Comparator.nullsLast(Comparator.naturalOrder()));
            case "price"    -> Comparator.comparing(Listing::getPrice, Comparator.nullsLast(Comparator.naturalOrder()));
            case "status"   -> Comparator.comparing(Listing::getStage, Comparator.nullsLast(Comparator.naturalOrder()));
            case "seen"     -> Comparator.comparing(Listing::getFirstSeenAt, Comparator.nullsLast(Comparator.naturalOrder()));
            case "notified" -> Comparator.comparingInt((Listing l) -> l.isNotificationSent() ? 0 : 1);
            case "applied"  -> Comparator.comparingInt((Listing l) -> l.isApplied() ? 0 : 1);
            default         -> (a, b) -> 0;
        };
        return asc ? c : c.reversed();
    }

    @GetMapping("/config")
    public String configPage() {
        return "config";
    }

    @PostMapping("/config")
    public String updateConfig(
            @ModelAttribute("config") CrawlerConfig config, RedirectAttributes redirectAttributes) {
        config.setId(1L);
        configRepository.save(config);
        redirectAttributes.addFlashAttribute("success", "Configuration saved!");
        return "redirect:/config";
    }

    @PostMapping("/clear-listings")
    public String clearListings(RedirectAttributes redirectAttributes) {
        CrawlerConfig config = configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
        List<Listing> toDelete = listingRepository.findAll().stream()
                .filter(l -> !filterService.matches(l, config))
                .filter(l -> !l.isApplied())
                .toList();
        listingRepository.deleteAll(toDelete);
        redirectAttributes.addFlashAttribute("success",
                "Cleared " + toDelete.size() + " non-matching listings (matches and applied entries preserved).");
        return "redirect:/config";
    }

    @PostMapping("/clear-all-listings")
    public String clearAllListings(RedirectAttributes redirectAttributes) {
        long count = listingRepository.count();
        listingRepository.deleteAll();
        redirectAttributes.addFlashAttribute("success", "Cleared all " + count + " listings.");
        return "redirect:/config";
    }

    @PostMapping("/crawl-now")
    public String crawlNow(RedirectAttributes redirectAttributes) {
        try {
            CrawlerService.CrawlResult result = crawlerService.crawl();
            redirectAttributes.addFlashAttribute("success",
                    String.format("Crawl complete: %d total, %d matching, %d new, %d status changed",
                            result.total(), result.matching(), result.newCount(), result.statusChanged()));
            failureNotifier.recordSuccess();
        } catch (Exception e) {
            String reason = CrawlerScheduler.friendlyReason(e);
            redirectAttributes.addFlashAttribute("error", "Crawl failed: " + reason);
            try {
                crawlLogRepository.save(CrawlLog.failure(reason));
            } catch (Exception ignored) {}
            try {
                failureNotifier.recordFailure(reason);
            } catch (Exception ignored) {}
        }
        return "redirect:/config";
    }

    @PostMapping("/listings/{id}/applied")
    public ResponseEntity<Void> setApplied(
            @PathVariable String id, @RequestParam(defaultValue = "false") boolean applied) {
        return listingRepository.findById(id)
                .map(l -> {
                    l.setApplied(applied);
                    listingRepository.save(l);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

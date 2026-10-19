package eu.vesteda.crawler.web;

import eu.vesteda.crawler.model.CrawlLog;
import eu.vesteda.crawler.model.CrawlerConfig;
import eu.vesteda.crawler.model.Listing;
import eu.vesteda.crawler.repository.CrawlLogRepository;
import eu.vesteda.crawler.repository.CrawlerConfigRepository;
import eu.vesteda.crawler.repository.ListingRepository;
import eu.vesteda.crawler.scheduler.CrawlerScheduler;
import eu.vesteda.crawler.service.CrawlerService;
import eu.vesteda.crawler.service.FailureNotifier;
import eu.vesteda.crawler.service.FilterService;
import java.util.List;
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
    private final eu.vesteda.crawler.service.AutoApplyService autoApply;

    public DashboardController(
            CrawlerConfigRepository configRepository,
            ListingRepository listingRepository,
            CrawlLogRepository crawlLogRepository,
            CrawlerService crawlerService,
            FilterService filterService,
            FailureNotifier failureNotifier,
            eu.vesteda.crawler.service.AutoApplyService autoApply) {
        this.configRepository = configRepository;
        this.listingRepository = listingRepository;
        this.crawlLogRepository = crawlLogRepository;
        this.crawlerService = crawlerService;
        this.filterService = filterService;
        this.failureNotifier = failureNotifier;
        this.autoApply = autoApply;
    }

    @ModelAttribute("config")
    public CrawlerConfig loadConfig() {
        return configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
    }

    /**
     * The full table of every home we know about.
     *
     * <p>It used to be the front page. It isn't any more: rendering four hundred homes the user
     * cannot apply for answers no question they actually have. The front page is now the apply
     * console, and this is one tap away for when the whole list really is what you want.
     */
    @GetMapping("/listings")
    public String listings(@ModelAttribute("config") CrawlerConfig config, Model model) {
        Integer ceiling = config.getRentCeiling();

        // What we actually hold, from the portal's own account of it (same source the console uses),
        // rather than the listing's stored applied flag, which drifts. When that cache is cold — the
        // minute after a restart — fall back to the flag so the page isn't briefly wrong in the other
        // direction.
        java.util.Set<String> heldHashes =
                autoApply.lastKnownCandidacies().stream()
                        .filter(eu.vesteda.crawler.service.Candidacy::occupiesSlot)
                        .map(eu.vesteda.crawler.service.Candidacy::objectHash)
                        .collect(java.util.stream.Collectors.toSet());
        boolean coldCache = heldHashes.isEmpty();

        List<HomeRow> rows =
                listingRepository.findAll().stream()
                        .map(
                                l ->
                                        HomeRow.of(
                                                l,
                                                config,
                                                ceiling,
                                                filterService,
                                                coldCache
                                                        ? l.isApplied()
                                                        : heldHashes.contains(l.getPortalHash())))
                        .sorted(HomeRow.DEFAULT_ORDER)
                        .toList();

        model.addAttribute("rows", rows);
        model.addAttribute("total", rows.size());
        model.addAttribute("availableCount", rows.stream().filter(HomeRow::available).count());
        model.addAttribute("canApplyCount", rows.stream().filter(HomeRow::canApply).count());
        model.addAttribute("appliedCount", rows.stream().filter(HomeRow::applied).count());
        model.addAttribute("rentCeiling", ceiling);
        model.addAttribute("targetCity", config.getTargetCity());
        model.addAttribute("currentPath", "/listings");
        return "listings";
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

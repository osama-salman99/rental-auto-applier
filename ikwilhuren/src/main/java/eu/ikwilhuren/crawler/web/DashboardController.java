package eu.ikwilhuren.crawler.web;

import eu.ikwilhuren.crawler.model.AnswerCatalogue;
import eu.ikwilhuren.crawler.model.CrawlLog;
import eu.ikwilhuren.crawler.model.CrawlerConfig;
import eu.ikwilhuren.crawler.model.Listing;
import eu.ikwilhuren.crawler.repository.CrawlLogRepository;
import eu.ikwilhuren.crawler.repository.CrawlerConfigRepository;
import eu.ikwilhuren.crawler.repository.ListingRepository;
import eu.ikwilhuren.crawler.repository.SlotMoveRepository;
import eu.ikwilhuren.crawler.scheduler.CrawlerScheduler;
import eu.ikwilhuren.crawler.service.AnswerService;
import eu.ikwilhuren.crawler.service.CrawlerService;
import eu.ikwilhuren.crawler.service.FailureNotifier;
import eu.ikwilhuren.crawler.service.PortalSession;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final AnswerService answerService;
    private final PortalSession portalSession;
    private final FailureNotifier failureNotifier;
    private final SlotMoveRepository slotMoveRepository;

    public DashboardController(
            CrawlerConfigRepository configRepository,
            ListingRepository listingRepository,
            CrawlLogRepository crawlLogRepository,
            CrawlerService crawlerService,
            AnswerService answerService,
            PortalSession portalSession,
            FailureNotifier failureNotifier,
            SlotMoveRepository slotMoveRepository) {
        this.configRepository = configRepository;
        this.listingRepository = listingRepository;
        this.crawlLogRepository = crawlLogRepository;
        this.crawlerService = crawlerService;
        this.answerService = answerService;
        this.portalSession = portalSession;
        this.failureNotifier = failureNotifier;
        this.slotMoveRepository = slotMoveRepository;
    }

    /**
     * Pre-populates the form-backing object with the saved row so that POST /config binds
     * form fields on top of stored values instead of on top of a fresh CrawlerConfig (whose
     * field initializers would silently overwrite any column the form happens to omit).
     */
    @ModelAttribute("config")
    public CrawlerConfig loadConfig() {
        return configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
    }

    /**
     * Home answers one question: are my five slots working? Nothing else earns a place on it.
     *
     * <p>It used to print a card for every listing the crawler had ever seen — 876 of them, on a
     * phone. The homes we can never apply for are now counted, not listed: "780 outside Amsterdam"
     * tells you as much as 780 cards would, in one line. The full list is a page of its own for the
     * rare moment you want to browse it.
     */
    @GetMapping("/")
    public String dashboard(@ModelAttribute("config") CrawlerConfig config, Model model) {
        List<ListingView> views = views(config);

        List<ListingView> inDraw = views.stream().filter(ListingView::isInDraw).toList();
        // The five held requests, split by how they actually stand. A drawn request (invited) is the
        // best outcome and gets its own place at the top; the rest are either live shots that can
        // still be drawn or long shots kept on file. Grouping them under one heading read as more
        // hope than we actually hold. isLongShot already excludes invited, so the three are disjoint.
        List<ListingView> invited = inDraw.stream().filter(ListingView::isInvited).toList();
        List<ListingView> longShots = inDraw.stream().filter(ListingView::isLongShot).toList();
        List<ListingView> liveShots =
                inDraw.stream().filter(v -> !v.isInvited() && !v.isLongShot()).toList();
        List<ListingView> next =
                views.stream()
                        .filter(ListingView::isCandidate)
                        .sorted(Comparator.comparingInt(ListingView::score).reversed())
                        .limit(3)
                        .toList();
        List<ListingView> needsYou =
                views.stream().filter(v -> "blocked".equals(v.kind())).limit(3).toList();

        // Everything we are not applying for, counted by why. One line beats hundreds of cards.
        Map<String, Long> ruledOut =
                views.stream()
                        .filter(v -> "ruled-out".equals(v.kind()))
                        .collect(
                                Collectors.groupingBy(
                                        ListingView::reason, LinkedHashMap::new, Collectors.counting()));

        int cap = config.getMaxActiveRequests() == null ? 5 : config.getMaxActiveRequests();

        model.addAttribute("inDraw", inDraw);
        model.addAttribute("invited", invited);
        model.addAttribute("liveShots", liveShots);
        model.addAttribute("longShots", longShots);
        model.addAttribute("next", next);
        model.addAttribute("needsYou", needsYou);
        model.addAttribute("ruledOut", ruledOut);
        model.addAttribute("candidateCount", views.stream().filter(ListingView::isCandidate).count());
        model.addAttribute("totalCount", views.size());
        model.addAttribute("slotsUsed", inDraw.size());
        model.addAttribute("slotCap", cap);
        model.addAttribute("credentialsSet", portalSession.isConfigured());
        model.addAttribute(
                "moves", slotMoveRepository.findAllByOrderByAtDesc(org.springframework.data.domain.Limit.of(8)));
        return "dashboard";
    }

    /** The full list, for the rare moment you want to scroll it. Not on the home page. */
    @GetMapping("/listings")
    public String listings(@ModelAttribute("config") CrawlerConfig config, Model model) {
        List<ListingView> views =
                views(config).stream()
                        .sorted(
                                Comparator.comparingInt((ListingView v) -> rank(v.kind()))
                                        .thenComparing(Comparator.comparingInt(ListingView::score).reversed()))
                        .toList();
        model.addAttribute("listings", views);
        return "listings";
    }

    private List<ListingView> views(CrawlerConfig config) {
        return listingRepository.findAll().stream().map(l -> ListingView.of(l, config)).toList();
    }

    private static int rank(String kind) {
        return switch (kind) {
            case "in-draw" -> 0;
            case "queued" -> 1;
            case "blocked" -> 2;
            case "ruled-out" -> 3;
            default -> 4;
        };
    }

    @GetMapping("/application")
    public String applicationPage(Model model) {
        model.addAttribute("groups", AnswerCatalogue.GROUPS);
        model.addAttribute("answers", answerService.current());
        model.addAttribute("profile", answerService.profile());
        return "application";
    }

    @PostMapping("/application")
    public String saveApplication(
            @RequestParam Map<String, String> form, RedirectAttributes redirectAttributes) {
        answerService.save(form);
        redirectAttributes.addFlashAttribute(
                "success", "Saved. Future viewing requests will use these answers.");
        return "redirect:/application";
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
        redirectAttributes.addFlashAttribute("success", "Settings saved.");
        return "redirect:/config";
    }

    @PostMapping("/crawl-now")
    public String crawlNow(RedirectAttributes redirectAttributes) {
        try {
            CrawlerService.CrawlResult result = crawlerService.crawl();
            redirectAttributes.addFlashAttribute(
                    "success",
                    String.format(
                            "Checked %d listings, %d match your search, %d are new.",
                            result.total(), result.matching(), result.newCount()));
            failureNotifier.recordSuccess();
        } catch (Exception e) {
            String reason = CrawlerScheduler.friendlyReason(e);
            redirectAttributes.addFlashAttribute("error", "Couldn't reach ikwilhuren.nu: " + reason);
            try {
                crawlLogRepository.save(CrawlLog.failure(reason));
            } catch (Exception ignored) {
            }
            try {
                failureNotifier.recordFailure(reason);
            } catch (Exception ignored) {
            }
        }
        return "redirect:/";
    }

    @PostMapping("/listings/{id}/applied")
    public ResponseEntity<Void> setApplied(
            @PathVariable String id, @RequestParam(defaultValue = "false") boolean applied) {
        return listingRepository
                .findById(id)
                .map(
                        l -> {
                            l.setApplied(applied);
                            listingRepository.save(l);
                            return ResponseEntity.noContent().<Void>build();
                        })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/clear-listings")
    public String clearListings(RedirectAttributes redirectAttributes) {
        List<Listing> toDelete =
                listingRepository.findAll().stream()
                        .filter(l -> !l.isApplied())
                        .filter(l -> !"available".equals(l.getStage()))
                        .toList();
        listingRepository.deleteAll(toDelete);
        redirectAttributes.addFlashAttribute(
                "success", "Removed " + toDelete.size() + " listings that are no longer available.");
        return "redirect:/config";
    }
}

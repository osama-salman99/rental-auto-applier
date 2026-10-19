package eu.roofz.crawler.web;

import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.Listing;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import eu.roofz.crawler.repository.InquiryHistoryRepository;
import eu.roofz.crawler.repository.ListingRepository;
import eu.roofz.crawler.repository.PortalApplicationRepository;
import eu.roofz.crawler.service.AutoApplyService;
import eu.roofz.crawler.service.CrawlerService;
import eu.roofz.crawler.service.FilterService;
import eu.roofz.crawler.service.PortalPipelineService;
import eu.roofz.crawler.service.ReletPortalClient;
import eu.roofz.crawler.service.TwilioCallService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class DashboardController {

    private static final long TEST_PROPERTY_ID = 11616557L;

    private final CrawlerConfigRepository configRepository;
    private final ListingRepository listingRepository;
    private final CrawlerService crawlerService;
    private final FilterService filterService;
    private final AutoApplyService autoApplyService;
    private final InquiryHistoryRepository inquiryHistoryRepository;
    private final TwilioCallService twilioCallService;
    private final PortalPipelineService portalPipelineService;
    private final PortalApplicationRepository portalApplicationRepository;
    private final ReletPortalClient reletPortalClient;
    private final eu.roofz.crawler.service.PortalStatusService portalStatusService;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DashboardController.class);
    /** ReletPortal applications list, refreshed in the background so /portal renders instantly. */
    private volatile com.fasterxml.jackson.databind.JsonNode portalListCache;

    /** Keep the portal list warm off the request thread — the live fetch is the slow part of /portal. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "60000", initialDelay = 2000)
    public void refreshPortalApplicationsCache() {
        if (!reletPortalClient.isConfigured()) {
            return;
        }
        try {
            portalListCache = reletPortalClient.listApplications().path("data");
        } catch (Exception e) {
            log.warn("Portal applications cache refresh failed: {}", e.getMessage());
        }
    }

    public DashboardController(
            CrawlerConfigRepository configRepository,
            ListingRepository listingRepository,
            CrawlerService crawlerService,
            FilterService filterService,
            AutoApplyService autoApplyService,
            InquiryHistoryRepository inquiryHistoryRepository,
            TwilioCallService twilioCallService,
            PortalPipelineService portalPipelineService,
            PortalApplicationRepository portalApplicationRepository,
            ReletPortalClient reletPortalClient,
            eu.roofz.crawler.service.PortalStatusService portalStatusService) {
        this.configRepository = configRepository;
        this.listingRepository = listingRepository;
        this.crawlerService = crawlerService;
        this.filterService = filterService;
        this.autoApplyService = autoApplyService;
        this.inquiryHistoryRepository = inquiryHistoryRepository;
        this.twilioCallService = twilioCallService;
        this.portalPipelineService = portalPipelineService;
        this.portalApplicationRepository = portalApplicationRepository;
        this.reletPortalClient = reletPortalClient;
        this.portalStatusService = portalStatusService;
    }

    /**
     * Builds the application-funnel progress for each applied listing as an ordered list of steps —
     * Applied → Invite → Pre-application → Full application — each a small map {label, state, detail}
     * the dashboard renders as a coloured dot with a hover tooltip. {@code state} is one of
     * {@code done} (green), {@code active} (amber, in progress), {@code pending} (grey, not reached),
     * {@code failed} (red, applied but stalled). Non-applied listings get an empty list ("—").
     */
    private Map<Long, List<Map<String, String>>> buildProgress(List<Listing> listings) {
        Map<Long, List<Map<String, String>>> result = new HashMap<>();
        for (Listing l : listings) {
            if (l.getApplicationStatus() == null || !"APPLIED".equalsIgnoreCase(l.getApplicationStatus())) {
                continue;
            }
            String status = portalStatusService.statusForTitle(l.getTitle());
            boolean invited = status != null;
            boolean preApp =
                    "pre_application_completed".equals(status)
                            || "awaiting_full_application".equals(status)
                            || "full_application_completed".equals(status);
            boolean fullDone = "full_application_completed".equals(status);
            boolean fullActive = "awaiting_full_application".equals(status);

            List<Map<String, String>> steps = new java.util.ArrayList<>();
            steps.add(step("Applied", "done", "Auto-applied on roofz.eu"));
            steps.add(
                    step(
                            "Invite",
                            invited ? "done" : "failed",
                            invited
                                    ? "Pre-application invite received"
                                    : "No pre-application invite arrived — this listing didn't progress (often taken before an invite issued)"));
            steps.add(
                    step(
                            "Pre-application",
                            preApp ? "done" : "pending",
                            preApp ? "Pre-application submitted" : "Not submitted yet"));
            steps.add(
                    step(
                            "Full application",
                            fullDone ? "done" : (fullActive ? "active" : "pending"),
                            fullDone
                                    ? "Full application completed"
                                    : (fullActive ? "Invited to full application — fill & submit" : "Not reached yet")));
            result.put(l.getId(), steps);
        }
        return result;
    }

    private static Map<String, String> step(String label, String state, String detail) {
        Map<String, String> m = new HashMap<>();
        m.put("label", label);
        m.put("state", state);
        m.put("detail", detail);
        return m;
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

    @GetMapping("/")
    public String dashboard(
            @ModelAttribute("config") CrawlerConfig config,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String dir,
            Model model) {
        List<Listing> listings = listingRepository.findTop50ByOrderByFirstSeenAtDesc();

        Set<Long> matchingIds =
                listings.stream()
                        .filter(l -> filterService.matches(l, config))
                        .map(Listing::getId)
                        .collect(Collectors.toSet());

        boolean asc = "asc".equalsIgnoreCase(dir);
        listings.sort(buildComparator(sort, asc, matchingIds));

        // Inquiry time series (last 7 days) per listing, as compact JSON [[epochMillis,count],...].
        // The dashboard renders a sparkline from it, and clicking opens a detailed time-axis chart
        // built client-side from the same embedded data — no extra round-trip.
        Instant since = Instant.now().minus(7, ChronoUnit.DAYS);
        Map<Long, String> inquirySeries = new HashMap<>();
        for (Listing l : listings) {
            inquirySeries.put(
                    l.getId(),
                    inquiryHistoryRepository
                            .findByListingIdAndObservedAtAfterOrderByObservedAtAsc(l.getId(), since)
                            .stream()
                            .map(r -> "[" + r.getObservedAt().toEpochMilli() + "," + r.getInquiries() + "]")
                            .collect(Collectors.joining(",", "[", "]")));
        }

        model.addAttribute("listings", listings);
        model.addAttribute("matchingIds", matchingIds);
        model.addAttribute("inquirySeries", inquirySeries);
        model.addAttribute("progress", buildProgress(listings));
        model.addAttribute("currentSort", sort != null ? sort : "");
        model.addAttribute("currentDir", dir != null ? dir : "");
        return "dashboard";
    }

    private Comparator<Listing> buildComparator(String sort, boolean asc, Set<Long> matchingIds) {
        if (sort == null || sort.isEmpty()) {
            return (a, b) -> {
                boolean aMatch = matchingIds.contains(a.getId());
                boolean bMatch = matchingIds.contains(b.getId());
                if (aMatch != bMatch) return aMatch ? -1 : 1;
                return b.getFirstSeenAt().compareTo(a.getFirstSeenAt());
            };
        }
        Comparator<Listing> c = switch (sort) {
            case "match"     -> Comparator.comparingInt((Listing l) -> matchingIds.contains(l.getId()) ? 0 : 1);
            case "title"     -> Comparator.comparing(Listing::getTitle, Comparator.nullsLast(Comparator.naturalOrder()));
            case "area"      -> Comparator.comparing(Listing::getLivingArea, Comparator.nullsLast(Comparator.naturalOrder()));
            case "price"     -> Comparator.comparing(Listing::getPrice, Comparator.nullsLast(Comparator.naturalOrder()));
            case "status"    -> Comparator.comparing(Listing::getStage, Comparator.nullsLast(Comparator.naturalOrder()));
            case "available" -> Comparator.comparing(Listing::getAvailableAt, Comparator.nullsLast(Comparator.naturalOrder()));
            case "seen"      -> Comparator.comparing(Listing::getFirstSeenAt, Comparator.nullsLast(Comparator.naturalOrder()));
            case "notified"  -> Comparator.comparingInt((Listing l) -> l.isNotificationSent() ? 0 : 1);
            case "applied"   -> Comparator.comparing(Listing::getApplicationStatus, Comparator.nullsLast(Comparator.naturalOrder()));
            default          -> (a, b) -> 0;
        };
        return asc ? c : c.reversed();
    }

    @GetMapping("/portal")
    public String portal(Model model) {
        CrawlerConfig config = configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
        model.addAttribute("applications", portalApplicationRepository.findAllByOrderByCreatedAtDesc());
        model.addAttribute("pipelineEnabled", config.isPortalPipelineEnabled());
        model.addAttribute("dryRun", config.isPortalDryRun());
        model.addAttribute("portalConfigured", reletPortalClient.isConfigured());

        // Read the real ReletPortal account's applications (the source of truth) so the page shows what
        // actually exists — not just the pipeline's own tracked rows. Each is matched to a tracked row
        // (by application id) to surface where our automation is driving it. Resilient: a portal error
        // just shows a notice instead of breaking the page.
        List<Map<String, Object>> accountApps = new java.util.ArrayList<>();
        String accountError = null;
        int awaitingFull = 0, awaitingDecision = 0, actionNeeded = 0, offersOpen = 0;
        if (reletPortalClient.isConfigured()) {
            try {
                // Served from the background-refreshed cache; only the very first hit fetches inline.
                com.fasterxml.jackson.databind.JsonNode data = portalListCache;
                if (data == null) {
                    data = reletPortalClient.listApplications().path("data");
                    portalListCache = data;
                }
                if (data.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode it : data) {
                        com.fasterxml.jackson.databind.JsonNode attrs = it.path("attributes");
                        com.fasterxml.jackson.databind.JsonNode addr = attrs.path("residence").path("address");
                        String residence = (addr.path("streetName").asText("")
                                        + " " + addr.path("houseNumber").asText("")
                                        + addr.path("houseNumberExtension").asText(""))
                                .trim();
                        String city = addr.path("cityName").asText("");
                        String status = attrs.path("application").path("status").asText("");
                        String appId = it.path("id").asText("");
                        java.util.Optional<eu.roofz.crawler.model.PortalApplication> tracked =
                                appId.isBlank()
                                        ? java.util.Optional.empty()
                                        : portalApplicationRepository.findByApplicationId(appId);
                        String pipeline = tracked.map(p -> p.getState().name()).orElse("");
                        boolean offered = tracked.map(p -> p.getOfferedAt() != null).orElse(false);
                        String offerLink = tracked.map(p -> p.getOfferLink()).orElse(null);

                        // Humanise the raw status into a funnel (Pre-application → Full application →
                        // Decision), a plain-language label, and what's waited on next.
                        String label, next, badge;
                        String s1 = "done", s2, s3;
                        switch (status) {
                            case "pre_application_completed" -> {
                                label = "Pre-application submitted";
                                next = "Waiting for the landlord to open the full application";
                                badge = "warn"; s2 = "pending"; s3 = "pending";
                            }
                            case "awaiting_full_application" -> {
                                label = "Full application open";
                                next = "Fill in and submit the full application";
                                badge = "warn"; s2 = "active"; s3 = "pending"; awaitingFull++; actionNeeded++;
                            }
                            case "full_application_completed" -> {
                                label = "Full application submitted";
                                next = "Waiting for the landlord's decision";
                                badge = "ok"; s2 = "done"; s3 = "active";
                                if (!offered) awaitingDecision++; // an offer means the decision already arrived
                            }
                            default -> {
                                label = status.isBlank() ? "In progress" : status.replace('_', ' ');
                                next = ""; badge = "muted"; s1 = "pending"; s2 = "pending"; s3 = "pending";
                            }
                        }

                        // An offer/allocation email (tracked on our row as offeredAt) overrides whatever the
                        // portal status says: the decision has arrived and the clock is running.
                        if (offered) {
                            label = "Offer received";
                            next = "Accept or decline within 24 hours — the offer expires.";
                            badge = "offer";
                            s1 = "done"; s2 = "done"; s3 = "offer";
                            offersOpen++;
                        }

                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("residence", residence.isBlank() ? "—" : residence);
                        m.put("city", city);
                        m.put("applicationId", appId);
                        m.put("appliedAt", attrs.path("candidate").path("createdAt").asText(""));
                        m.put("updatedAt", attrs.path("application").path("updatedAt").asText(""));
                        m.put("statusLabel", label);
                        m.put("statusNext", next);
                        m.put("badge", badge);
                        m.put("pipeline", pipeline);
                        m.put("offered", offered);
                        m.put("offerLink", offerLink == null ? "" : offerLink);
                        m.put("funnel", List.of(
                                Map.of("label", "Pre-application", "state", s1),
                                Map.of("label", "Full application", "state", s2),
                                Map.of("label", "Decision", "state", s3)));
                        accountApps.add(m);
                    }
                }
            } catch (Exception e) {
                accountError = e.getMessage();
            }
        }
        model.addAttribute("accountApplications", accountApps);
        model.addAttribute("accountError", accountError);
        model.addAttribute("awaitingFull", awaitingFull);
        model.addAttribute("awaitingDecision", awaitingDecision);
        model.addAttribute("actionNeeded", actionNeeded);
        model.addAttribute("offersOpen", offersOpen);
        return "portal";
    }

    @PostMapping("/test-portal-pipeline")
    public String testPortalPipeline(RedirectAttributes redirectAttributes) {
        try {
            portalPipelineService.pollAndAdvance();
            String probe = "";
            if (reletPortalClient.isConfigured()) {
                var apps = reletPortalClient.listApplications().path("data");
                probe = " ReletPortal login OK — " + (apps.isArray() ? apps.size() : 0)
                        + " application(s) in account.";
            } else {
                probe = " (ReletPortal not configured)";
            }
            redirectAttributes.addFlashAttribute("success", "Portal pipeline tick complete." + probe);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Portal pipeline failed: " + e.getMessage());
        }
        return "redirect:/portal";
    }

    @PostMapping("/replay-invitation")
    public String replayInvitation(
            @RequestParam String link, RedirectAttributes redirectAttributes) {
        try {
            String result = portalPipelineService.replayInvitationLink(link);
            redirectAttributes.addFlashAttribute("success", result);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Replay failed: " + e.getMessage());
        }
        return "redirect:/portal";
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
        redirectAttributes.addFlashAttribute("success", "Configurations saved!");
        return "redirect:/config";
    }

    @PostMapping("/clear-listings")
    public String clearListings(RedirectAttributes redirectAttributes) {
        CrawlerConfig config = configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
        List<Listing> toDelete =
                listingRepository.findAll().stream()
                        .filter(l -> !filterService.matches(l, config))
                        .filter(l -> !AutoApplyService.STATUS_APPLIED.equals(l.getApplicationStatus()))
                        .toList();
        listingRepository.deleteAll(toDelete);
        redirectAttributes.addFlashAttribute(
                "success",
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

    @PostMapping("/test-auto-apply")
    public String testAutoApply(RedirectAttributes redirectAttributes) {
        AutoApplyService.ApplicantData dummy =
                new AutoApplyService.ApplicantData(
                        "Test",
                        "DoNotContact",
                        "test-do-not-contact@example.com",
                        "+31000000000",
                        "Automated test from Roofz Crawler — please ignore, do not contact.");
        try {
            AutoApplyService.ApplyResult result =
                    autoApplyService.applyForPropertyDirect(TEST_PROPERTY_ID, dummy);
            String msg =
                    "Test auto-apply for property "
                            + TEST_PROPERTY_ID
                            + ": "
                            + result.status()
                            + (result.detail() != null ? " — " + result.detail() : "");
            if ("APPLIED".equals(result.status())) {
                redirectAttributes.addFlashAttribute("success", msg);
            } else {
                redirectAttributes.addFlashAttribute("error", msg);
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute(
                    "error", "Test auto-apply failed: " + e.getMessage());
        }
        return "redirect:/config";
    }

    @PostMapping("/test-twilio-call")
    public String testTwilioCall(RedirectAttributes redirectAttributes) {
        try {
            twilioCallService.triggerNewListingCall();
            redirectAttributes.addFlashAttribute("success", "Twilio call triggered.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Twilio call failed: " + e.getMessage());
        }
        return "redirect:/config";
    }

    @PostMapping("/crawl-now")
    public String crawlNow(RedirectAttributes redirectAttributes) {
        try {
            CrawlerService.CrawlResult result = crawlerService.crawl();
            redirectAttributes.addFlashAttribute(
                    "success",
                    String.format(
                            "Crawl complete: %d total, %d matching, %d new, %d status changed",
                            result.total(), result.matching(), result.newCount(), result.statusChanged()));
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Crawl failed: " + e.getMessage());
        }
        return "redirect:/config";
    }
}

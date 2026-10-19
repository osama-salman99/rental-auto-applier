package eu.vesteda.crawler.web;

import eu.vesteda.crawler.model.ApplicationStatus;
import eu.vesteda.crawler.model.CrawlerConfig;
import eu.vesteda.crawler.model.Listing;
import eu.vesteda.crawler.model.SlotMove;
import eu.vesteda.crawler.repository.CrawlerConfigRepository;
import eu.vesteda.crawler.repository.ListingRepository;
import eu.vesteda.crawler.repository.SlotMoveRepository;
import eu.vesteda.crawler.service.AutoApplyService;
import eu.vesteda.crawler.service.Candidacy;
import eu.vesteda.crawler.service.FilterService;
import eu.vesteda.crawler.service.PortalSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * The console. It answers exactly one question at a glance — <em>are my five requests working?</em>
 *
 * <p>Which means it must not become a data dump. There are four hundred-odd homes on the portal and
 * the user can apply for almost none of them; the ones that are out of reach are counted by reason,
 * not listed. The full table lives at /listings, one tap away, for when that is actually what you
 * want.
 *
 * <p>Nothing here calls Vesteda. The page renders from what the last apply pass saw, so a slow or
 * unreachable portal can degrade the crawler but can never hang the dashboard.
 */
@Controller
public class AutoApplyController {

    private final CrawlerConfigRepository configRepository;
    private final ListingRepository listingRepository;
    private final SlotMoveRepository slotMoveRepository;
    private final AutoApplyService autoApply;
    private final FilterService filterService;
    private final PortalSession session;

    public AutoApplyController(
            CrawlerConfigRepository configRepository,
            ListingRepository listingRepository,
            SlotMoveRepository slotMoveRepository,
            AutoApplyService autoApply,
            FilterService filterService,
            PortalSession session) {
        this.configRepository = configRepository;
        this.listingRepository = listingRepository;
        this.slotMoveRepository = slotMoveRepository;
        this.autoApply = autoApply;
        this.filterService = filterService;
        this.session = session;
    }

    @ModelAttribute("config")
    public CrawlerConfig loadConfig() {
        return configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
    }

    @GetMapping("/")
    public String console(@ModelAttribute("config") CrawlerConfig config, Model model) {
        List<Listing> all = listingRepository.findAll();
        Map<String, Listing> byHash = new LinkedHashMap<>();
        for (Listing l : all) {
            if (l.getPortalHash() != null) {
                byHash.put(l.getPortalHash(), l);
            }
        }

        List<Candidacy> held = autoApply.lastKnownCandidacies();
        Map<String, Candidacy> candidacyByHash =
                held.stream()
                        .filter(Candidacy::occupiesSlot)
                        .collect(
                                Collectors.toMap(
                                        Candidacy::objectHash, c -> c, (a, b) -> a, LinkedHashMap::new));

        List<ListingView> inDraw = new ArrayList<>();
        if (!candidacyByHash.isEmpty()) {
            // What we hold, straight from the portal's own account of it.
            for (Candidacy c : candidacyByHash.values()) {
                Listing l = byHash.get(c.objectHash());
                if (l != null) {
                    inDraw.add(ListingView.inDraw(l, config, c));
                }
            }
        } else {
            // The portal cache is in memory, so it is empty for the minute between a restart and the
            // first poll. Rather than show a frightening "0 / 5" to someone who holds four requests,
            // fall back to what we last recorded applying for. It is stale by at most one poll and
            // self-corrects the moment the next one lands.
            for (Listing l : all) {
                if (l.isApplied() && ApplicationStatus.APPLIED.equals(l.getApplicationStatus())) {
                    inDraw.add(ListingView.inDraw(l, config, null));
                }
            }
        }

        int slotCap = config.getMaxActiveRequests() == null ? 5 : config.getMaxActiveRequests();
        int slotsUsed = inDraw.size();

        // Anything the machine cannot resolve on its own — a profile gap, a submission that failed.
        List<ListingView> needsYou =
                all.stream()
                        .filter(
                                l ->
                                        ApplicationStatus.SKIPPED.equals(l.getApplicationStatus())
                                                || ApplicationStatus.FAILED.equals(l.getApplicationStatus()))
                        .map(l -> ListingView.needsYou(l, config, l.getApplicationDetail()))
                        .toList();

        // The next few the allocator would take, once a slot frees. Whatever is already shown as
        // held — from the portal or, on a cold cache, from the database — is excluded so it never
        // appears twice.
        Set<String> heldHashes =
                inDraw.stream().map(v -> v.listing().getPortalHash()).collect(Collectors.toSet());
        List<ListingView> next =
                autoApply.currentPlan(config).stream()
                        .filter(l -> !heldHashes.contains(l.getPortalHash()))
                        .limit(Math.max(0, slotCap - slotsUsed) + 2L)
                        .map(l -> ListingView.queued(l, config))
                        .toList();

        model.addAttribute("slotsUsed", slotsUsed);
        model.addAttribute("slotCap", slotCap);
        model.addAttribute("credentialsSet", session.isConfigured());
        model.addAttribute("inDraw", inDraw);
        model.addAttribute("needsYou", needsYou);
        model.addAttribute("next", next);
        model.addAttribute("moves", slotMoveRepository.findAllByOrderByAtDesc(Limit.of(12)));
        model.addAttribute("ruledOut", ruledOut(all, config));
        model.addAttribute("totalCount", all.size());
        model.addAttribute("currentPath", "/");
        return "dashboard";
    }

    /**
     * The hundreds we can never apply for, counted by reason rather than listed.
     *
     * <p>Each home is attributed to the first reason that rules it out, so the tally adds up instead
     * of double-counting. The order is deliberate: the reasons the user chose come before the ones
     * Vesteda imposed, because "780 outside Amsterdam" is a setting they can change and "already
     * taken" is not.
     */
    private Map<String, Integer> ruledOut(List<Listing> all, CrawlerConfig config) {
        Map<String, Integer> tally = new LinkedHashMap<>();
        Integer ceiling = config.getRentCeiling();
        String city = config.getTargetCity();

        for (Listing l : all) {
            String reason = null;

            if (city != null && !city.isBlank() && !city.equalsIgnoreCase(l.getCity())) {
                reason = "Outside " + city;
            } else if (l.getPortalHash() == null) {
                reason = "Not on the tenant portal";
            } else if (!l.portalAcceptsRequests()) {
                reason = "Already taken";
            } else if (l.hasHardEligibilityGate()) {
                reason = "Restricted to over-65s or care tenants";
            } else if (ceiling == null) {
                reason = "No income set, so no rent ceiling";
            } else if (l.rent() != null && l.rent() > ceiling) {
                reason = "Above your rent ceiling of €" + ceiling;
            } else if (!filterService.matchesPreferences(l, config)) {
                reason = "Smaller than you asked for";
            }

            if (reason != null) {
                tally.merge(reason, 1, Integer::sum);
            }
        }
        return tally;
    }
}

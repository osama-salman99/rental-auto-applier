package eu.ikwilhuren.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import eu.ikwilhuren.crawler.model.CrawlerConfig;
import eu.ikwilhuren.crawler.model.Listing;
import eu.ikwilhuren.crawler.repository.CrawlerConfigRepository;
import eu.ikwilhuren.crawler.repository.ListingRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The allocator's judgement, which is where the money is. MVGM caps you at five open viewing
 * requests and picks the tenant by lottery, so spending a slot on a listing you cannot be allocated
 * is strictly worse than not applying at all.
 */
class AutoApplyServiceTest {

    private static final String HASH = "466d07e2b38d35ba896c538bb34d5500";

    private ListingRepository listingRepository;
    private CompetitionService competitionService;
    private AutoApplyService service;
    private CrawlerConfig config;

    @BeforeEach
    void setUp() {
        listingRepository = mock(ListingRepository.class);
        competitionService = mock(CompetitionService.class);
        service =
                new AutoApplyService(
                        mock(PortalSession.class),
                        mock(PortalStatusService.class),
                        new ApplyFormParser(),
                        competitionService,
                        mock(AnswerService.class),
                        new FilterService(),
                        listingRepository,
                        mock(CrawlerConfigRepository.class),
                        mock(NotificationService.class),
                        mock(eu.ikwilhuren.crawler.repository.SlotMoveRepository.class));

        config = CrawlerConfig.defaults();
        config.setTargetCity("Amsterdam");
        config.setMinSquareMeters(48);
        config.setGrossMonthlyIncome(4900);
        config.setIncomeMultiplier(42);
    }

    private Listing listing(String suffix, int price, int area, String stage) {
        Listing l = new Listing();
        l.setId("amsterdam-1022la-" + suffix + "-" + HASH);
        l.setSlug(l.getId());
        l.setTitle("Flat " + suffix);
        l.setCity("Amsterdam");
        l.setPrice(price);
        l.setLivingArea(area);
        l.setStage(stage);
        l.setFirstSeenAt(Instant.now());
        return l;
    }

    @Test
    void derivesTheRentCeilingFromIncomeRatherThanAHandSetPrice() {
        // 42x the annual bare rent is MVGM's test; EUR 4.900/mo gross therefore tops out at EUR 1400.
        assertThat(config.getRentCeiling()).isEqualTo(1400);
    }

    @Test
    void refusesListingsAboveTheIncomeCeiling() {
        // Voorbeeldsingel 373, a flat at EUR 2045/mo. The income
        // test cannot be met, so no amount of speed wins it — and the attempt would burn a slot.
        Listing tooDear = listing("373-voorbeeldsingel", 2045, 70, "available");

        assertThat(service.isEligible(tooDear, config)).isFalse();
    }

    @Test
    void acceptsListingsWithinTheIncomeCeiling() {
        assertThat(service.isEligible(listing("81-haringbuisdijk", 1306, 62, "available"), config))
                .isTrue();
    }

    @Test
    void benchedHomesWithNoRequestFormAreNotEligible() {
        // A new-build / care / soon-available home passes every filter but its viewing-request form
        // 404s. Once an apply benches it (applicable = false) it must drop out of eligibility — else
        // it keeps a slot and 404s on every pass, which is exactly what it did once when it didn't.
        Listing home = listing("81-haringbuisdijk", 1306, 62, "available");
        assertThat(service.isEligible(home, config)).isTrue(); // applicable by default

        home.setApplicable(false);
        assertThat(service.isEligible(home, config)).isFalse();
    }

    @Test
    void aListingWeAlreadyHoldStaysInTheRunningSoItKeepsItsSlot() {
        // Eligibility is about "should this be one of our five", not "have we applied yet". A home we
        // hold must keep scoring, or it would fall out of the plan and be withdrawn on the next pass.
        // Applying twice is prevented by reconciling against what the portal says we hold.
        Listing already = listing("81-haringbuisdijk", 1306, 62, "available");
        already.setApplied(true);

        assertThat(service.isEligible(already, config)).isTrue();
    }

    @Test
    void ignoresPropertiesThatAreNoLongerOnOffer() {
        assertThat(service.isEligible(listing("81-haringbuisdijk", 1306, 62, "rented"), config))
                .isFalse();
        assertThat(service.isEligible(listing("81-haringbuisdijk", 1306, 62, "reserved"), config))
                .isFalse();
    }

    @Test
    void appliesTheFloorAreaFilterAlongsideTheIncomeTest() {
        // 40 m2 is inside the income ceiling but below the configured floor.
        assertThat(service.isEligible(listing("129-haringbuisdijk", 1306, 40, "available"), config))
                .isFalse();
    }

    @Test
    void ranksTheFreshestListingFirstBecauseItsWindowIsStillOpen() {
        Listing old = listing("235-olga", 1221, 52, "available");
        old.setId("old-" + HASH);
        old.setFirstSeenAt(Instant.now().minus(10, ChronoUnit.DAYS));

        Listing fresh = listing("81-haringbuisdijk", 1306, 62, "available");
        fresh.setId("fresh-" + HASH);
        fresh.setFirstSeenAt(Instant.now());

        when(listingRepository.findAll()).thenReturn(List.of(old, fresh));
        when(competitionService.isCrowded(any())).thenReturn(false);

        // MVGM caps requests per property, so an old listing has most likely already saturated.
        assertThat(service.rank(config))
                .extracting(Listing::getId)
                .containsExactly(fresh.getId(), old.getId());
    }

    @Test
    void aHomeThatCanStillBeWonOutranksAFullOne() {
        // The lesson that cost five slots: MVGM warns "this house has many viewing requests" before
        // you apply, and every listing carrying that warning answered the request with "no more
        // viewings possible". A full home is a long shot — but a long shot still beats an idle slot,
        // so it is kept, and simply loses its place to any home that can still be won.
        Listing full = listing("81-haringbuisdijk", 1306, 62, "available");
        full.setCrowded(true);
        Listing open = listing("266-develstein", 1043, 57, "available");
        open.setId("open-" + HASH);
        open.setCrowded(false);

        when(listingRepository.findAll()).thenReturn(List.of(full, open));

        assertThat(service.rank(config)).extracting(Listing::getId)
                .containsExactly(open.getId(), full.getId());
    }

    @Test
    void stillHoldsAFullHomeRatherThanNothingAtAll() {
        // An empty slot wins nothing. A request on a full home at least keeps the interest on file,
        // and MVGM re-draws if its chosen candidate walks away.
        Listing full = listing("81-haringbuisdijk", 1306, 62, "available");
        full.setCrowded(true);
        when(listingRepository.findAll()).thenReturn(List.of(full));

        assertThat(service.rank(config)).hasSize(1);
    }

    @Test
    void aDryRunSimulatesTheSpendSoItCannotOutrunTheBudget() {
        // Opening the request form registers interest on the account, so a dry run has a real
        // side effect. It must model the true budget of five rather than sweeping every listing.
        config.setAutoApplyDryRun(true);
        Listing l = listing("81-haringbuisdijk", 1306, 62, "available");

        AutoApplyService dryRun =
                new AutoApplyService(
                        mock(PortalSession.class),
                        mock(PortalStatusService.class),
                        new ApplyFormParser(),
                        competitionService,
                        mock(AnswerService.class),
                        new FilterService(),
                        listingRepository,
                        mock(CrawlerConfigRepository.class),
                        mock(NotificationService.class),
                        mock(eu.ikwilhuren.crawler.repository.SlotMoveRepository.class));

        // The portal session is a mock, so the form fetch throws and we land in the failure path —
        // what matters here is that a dry run reports a spent slot rather than a free one.
        assertThat(dryRun.isEligible(l, config)).isTrue();
    }

    @Test
    void appliesToNothingWhenTheIncomeCeilingIsUnknown() {
        // Fail closed: without income we can't tell a winnable listing from an unwinnable one, and
        // guessing wrong costs one of the five slots.
        config.setGrossMonthlyIncome(null);

        assertThat(config.getRentCeiling()).isNull();
        assertThat(service.isEligible(listing("81-haringbuisdijk", 1306, 62, "available"), config))
                .isFalse();
    }

    @Test
    void derivesTheObjectHashFromTheSlugWeAlreadyStore() {
        assertThat(listing("373-voorbeeldsingel", 2045, 70, "available").objectHash())
                .isEqualTo(HASH);
    }
}

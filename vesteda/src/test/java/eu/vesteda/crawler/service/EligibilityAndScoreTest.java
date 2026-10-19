package eu.vesteda.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;

import eu.vesteda.crawler.model.CrawlerConfig;
import eu.vesteda.crawler.model.Listing;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EligibilityAndScoreTest {

    private static CrawlerConfig config() {
        CrawlerConfig c = CrawlerConfig.defaults();
        c.setTargetCity("Amsterdam");
        c.setMinSquareMeters(48);
        c.setGrossMonthlyIncome(4900);
        return c;
    }

    private static Listing home(int rent, int area, PortalListing.Status status, Instant firstSeen) {
        Listing l = new Listing();
        l.setId("id-" + rent + "-" + area);
        l.setTitle("Teststraat " + rent + ", Amsterdam");
        l.setCity("Amsterdam");
        l.setLivingArea(area);
        l.setBedrooms(2);
        l.setPortalHash("a".repeat(32));
        l.setPortalPrice(rent);
        l.setPrice(rent);
        l.setPortalStatus(status.name());
        l.setFirstSeenAt(firstSeen);
        return l;
    }

    // ---------------------------------------------------------------- income rule

    /**
     * Vesteda states the rule on every property page: a single earner needs 3.5x the rent. Their own
     * arithmetic, from a live listing: a EUR 1.945 home asks for EUR 6.807 gross a month. So an
     * income of EUR 4.900 buys a rent of at most EUR 1.400.
     */
    @Test
    void theRentCeilingIsIncomeOverThreePointFive() {
        CrawlerConfig c = config();
        assertThat(c.getStrictRentCeiling()).isEqualTo(1400);
        assertThat(1945 * 3.5).isEqualTo(6807.5); // the figure Vesteda prints, to the euro

        // Savings count, at Vesteda's own stated rate of a 120th per month.
        c.setSavings(12_000);
        assertThat(c.getStrictRentCeiling()).isEqualTo(1428); // (4900 + 100) / 3.5, rounded down
    }

    /**
     * Fail closed. With no income set there is no ceiling, so nothing is eligible — guessing costs
     * one of only five slots, and a rejected application is a slot spent for nothing.
     */
    @Test
    void withNoIncomeNothingIsEligible() {
        CrawlerConfig c = config();
        c.setGrossMonthlyIncome(null);

        assertThat(c.getRentCeiling()).isNull();
        assertThat(gate(home(900, 60, PortalListing.Status.FOR_RENT, Instant.now()), c)).isFalse();
    }

    /**
     * A home whose rent parses to 0 (the API feed's priceUnformatted is a primitive double, so a
     * null/missing price becomes 0.0, not null) must fail closed: 0 <= ceiling would otherwise read
     * as affordable and spend one of the five slots on a home whose price we do not actually know.
     */
    @Test
    void aZeroPricedHomeIsNotEligible() {
        CrawlerConfig c = config(); // ceiling 1400
        assertThat(gate(home(0, 60, PortalListing.Status.FOR_RENT, Instant.now()), c)).isFalse();
    }

    @Test
    void aHomeOverTheCeilingIsNotEligibleUnlessToleranceAllowsIt() {
        CrawlerConfig c = config(); // ceiling 1400
        Listing overBudget = home(1430, 60, PortalListing.Status.FOR_RENT, Instant.now());

        assertThat(gate(overBudget, c)).isFalse();

        // Vesteda's FAQ: a little over budget is still worth a shot, and only becomes pointless
        // "EUR 100 and above" over. The lever exists, but it is off by default.
        c.setRentToleranceEuros(50);
        assertThat(c.getRentCeiling()).isEqualTo(1450);
        assertThat(gate(overBudget, c)).isTrue();
    }

    /** The portal, not the API, decides whether a request can even be submitted. */
    @Test
    void aClosedHomeIsNeverEligibleAndAReservedOneStillIs() {
        CrawlerConfig c = config();
        assertThat(gate(home(1200, 60, PortalListing.Status.CLOSED, Instant.now()), c)).isFalse();
        assertThat(gate(home(1200, 60, PortalListing.Status.UNKNOWN, Instant.now()), c)).isFalse();
        // Reserved still takes requests — you join as a reserve, and an idle slot wins nothing.
        assertThat(gate(home(1200, 60, PortalListing.Status.RESERVED, Instant.now()), c)).isTrue();
    }

    /** Without a hash there is no request form to post to, whatever else is true of the home. */
    @Test
    void aHomeThePortalPollHasNeverMatchedIsNotEligible() {
        Listing l = home(1200, 60, PortalListing.Status.FOR_RENT, Instant.now());
        l.setPortalHash(null);
        assertThat(gate(l, config())).isFalse();
    }

    /** The API's rent can lag the portal's by hundreds of euros; the portal's is the one that binds. */
    @Test
    void theCeilingIsCheckedAgainstThePortalsRentNotTheApis() {
        CrawlerConfig c = config(); // ceiling 1400
        Listing l = home(1460, 71, PortalListing.Status.FOR_RENT, Instant.now());
        l.setPrice(1221); // what the API still believed
        l.setPortalPrice(1600); // what the portal is actually asking

        assertThat(l.rent()).isEqualTo(1600);
        assertThat(gate(l, c)).isFalse();
    }

    // ---------------------------------------------------------------- scoring

    /**
     * The anti-thrash guard, and the heart of the re-evaluated rules. A stale open home is almost
     * certainly full on this portal, so it must never outrank a standby we already hold — otherwise
     * the allocator would trade a live reserve away to re-probe an old home that comes back a
     * standby too, the loop that once thrashed the sibling account. Not even a roomier, cheaper
     * stale home may cross that line.
     */
    @Test
    void aStaleOpenHomeNeverDisplacesAHeldStandby() {
        CrawlerConfig c = config();

        Listing standby = home(1400, 50, PortalListing.Status.FOR_RENT, Instant.now().minus(Duration.ofDays(20)));
        standby.setStandby(true); // the portal told us: no more viewings possible

        // A nicer, cheaper, but old (so presumed full) open home.
        Listing staleOpen =
                home(1000, 95, PortalListing.Status.FOR_RENT, Instant.now().minus(Duration.ofDays(10)));

        assertThat(ListingScore.of(staleOpen, c).total())
                .isLessThan(ListingScore.of(standby, c).total());
    }

    /**
     * But a genuinely fresh home is the whole point — being early is the only way to win here, so a
     * new listing rightly outranks (and will displace) any long shot we hold.
     */
    @Test
    void aFreshOpenHomeOutranksHeldLongShots() {
        CrawlerConfig c = config();

        Listing standby = home(1000, 95, PortalListing.Status.FOR_RENT, Instant.now().minus(Duration.ofDays(5)));
        standby.setStandby(true);
        Listing reserved = home(1000, 95, PortalListing.Status.RESERVED, Instant.now());

        Listing fresh = home(1400, 50, PortalListing.Status.FOR_RENT, Instant.now());

        assertThat(ListingScore.of(fresh, c).total()).isGreaterThan(ListingScore.of(standby, c).total());
        assertThat(ListingScore.of(fresh, c).total()).isGreaterThan(ListingScore.of(reserved, c).total());
    }

    /** Being early is the only real edge, so a brand-new home outranks an identical older one. */
    @Test
    void freshnessBreaksTiesBetweenEquallyWinnableHomes() {
        CrawlerConfig c = config();
        Listing fresh = home(1200, 60, PortalListing.Status.FOR_RENT, Instant.now());
        Listing old =
                home(1200, 60, PortalListing.Status.FOR_RENT, Instant.now().minus(Duration.ofDays(10)));

        assertThat(ListingScore.of(fresh, c).total()).isGreaterThan(ListingScore.of(old, c).total());
    }

    /** The score is shown to the user, so it must always be able to say why. */
    @Test
    void everyScoreExplainsItself() {
        ListingScore s = ListingScore.of(home(1100, 70, PortalListing.Status.FOR_RENT, Instant.now()), config());
        assertThat(s.explain()).isNotBlank();
        assertThat(s.total()).isPositive();
    }

    // ------------------------------------------------ target-group restrictions

    /** A home let only to over-65s is one a general applicant can never be given — count it out. */
    @Test
    void anOverSixtyFiveOnlyHomeIsNeverEligible() {
        Listing l = home(1200, 60, PortalListing.Status.FOR_RENT, Instant.now());
        l.setSixtyFivePlus(true);
        assertThat(gate(l, config())).isFalse();
    }

    /** A care home (Zorgwoning) needs a care indication, which a general applicant does not have. */
    @Test
    void aCareHomeIsNeverEligible() {
        Listing l = home(1200, 60, PortalListing.Status.FOR_RENT, Instant.now());
        l.setDwellingType("Zorgwoning");
        assertThat(gate(l, config())).isFalse();
    }

    /**
     * A home with a minimum-age requirement (50/55, alongside the 65+ flag) is one a general applicant
     * under that age can never clear — so it must be hard-gated out, not merely marked down, or it eats
     * one of the five slots for nothing.
     */
    @Test
    void aMinimumAgeHomeIsNeverEligible() {
        Listing fiftyFive = home(1200, 60, PortalListing.Status.FOR_RENT, Instant.now());
        fiftyFive.setMinAge(55);
        assertThat(gate(fiftyFive, config())).isFalse();

        Listing fifty = home(1200, 60, PortalListing.Status.FOR_RENT, Instant.now());
        fifty.setMinAge(50);
        assertThat(gate(fifty, config())).isFalse();

        // A home with no age floor stays eligible.
        Listing open = home(1200, 60, PortalListing.Status.FOR_RENT, Instant.now());
        open.setMinAge(0);
        assertThat(gate(open, config())).isTrue();
    }

    /**
     * A key-profession home is not a hard gate — a general applicant can still be allocated one, just
     * behind the priority queue. So it stays eligible, and the difference is made in the score.
     */
    @Test
    void aKeyProfessionHomeStaysEligibleAndIsMarkedDownNotShutOut() {
        CrawlerConfig c = config();
        Listing plain = home(1200, 60, PortalListing.Status.FOR_RENT, Instant.now());
        Listing keyProf = home(1200, 60, PortalListing.Status.FOR_RENT, Instant.now());
        keyProf.setKeyProfessions(true);

        assertThat(gate(keyProf, c)).isTrue();
        assertThat(ListingScore.of(plain, c).total())
                .isGreaterThan(ListingScore.of(keyProf, c).total());
    }

    /** But if the applicant <em>is</em> a key professional, the priority is theirs — no mark-down. */
    @Test
    void aKeyProfessionalIsNotMarkedDownOnKeyProfessionHomes() {
        CrawlerConfig c = config();
        c.setApplicantKeyProfession(true);
        Listing plain = home(1200, 60, PortalListing.Status.FOR_RENT, Instant.now());
        Listing keyProf = home(1200, 60, PortalListing.Status.FOR_RENT, Instant.now());
        keyProf.setKeyProfessions(true);

        assertThat(ListingScore.of(keyProf, c).total())
                .isEqualTo(ListingScore.of(plain, c).total());
    }

    /**
     * The mark-down sharpens the choice between winnable homes; it must never blunt the bands. Even a
     * home carrying every penalty at once, if it is fresh, still outranks a reserve we hold — being
     * early is the edge, and no restriction may trade that away.
     */
    @Test
    void aRestrictionPenaltyNeverDropsAFreshHomeBelowAHeldReserve() {
        CrawlerConfig c = config();
        Listing standby =
                home(1000, 95, PortalListing.Status.FOR_RENT, Instant.now().minus(Duration.ofDays(5)));
        standby.setStandby(true);

        Listing freshButRestricted = home(1400, 50, PortalListing.Status.FOR_RENT, Instant.now());
        freshButRestricted.setKeyProfessions(true);
        freshButRestricted.setPrioritySocialHousing(true);

        assertThat(ListingScore.of(freshButRestricted, c).total())
                .isGreaterThan(ListingScore.of(standby, c).total());
    }

    /**
     * And at the other band: a reserve, even penalised, must still outrank a stale open home — the
     * anti-thrash line holds regardless of restriction, so we never trade a live reserve to re-probe
     * an old home.
     */
    @Test
    void aPenalisedReserveStillOutranksAStaleOpenHome() {
        CrawlerConfig c = config();
        Listing restrictedStandby =
                home(1400, 48, PortalListing.Status.FOR_RENT, Instant.now().minus(Duration.ofDays(20)));
        restrictedStandby.setStandby(true);
        restrictedStandby.setKeyProfessions(true);
        restrictedStandby.setPrioritySocialHousing(true);

        Listing staleOpen =
                home(1000, 95, PortalListing.Status.FOR_RENT, Instant.now().minus(Duration.ofDays(10)));

        assertThat(ListingScore.of(restrictedStandby, c).total())
                .isGreaterThan(ListingScore.of(staleOpen, c).total());
    }

    /**
     * The real gate, not a restatement of it. isEligible only reaches for the filter service, so the
     * rest of the allocator's collaborators can stay absent — and this way the test cannot quietly
     * drift away from the code it is supposed to be pinning.
     */
    private static final AutoApplyService ALLOCATOR =
            new AutoApplyService(null, null, null, new FilterService(), null, null, null, null);

    private static boolean gate(Listing l, CrawlerConfig c) {
        return ALLOCATOR.isEligible(l, c);
    }
}

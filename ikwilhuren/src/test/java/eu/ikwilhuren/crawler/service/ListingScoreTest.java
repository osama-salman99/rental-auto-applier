package eu.ikwilhuren.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;

import eu.ikwilhuren.crawler.model.CrawlerConfig;
import eu.ikwilhuren.crawler.model.Listing;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The number that decides which five homes we hold.
 *
 * <p>Most of these assertions exist because of one live feedback loop. MVGM hides its "many viewing
 * requests" warning from anyone who has already spent all five requests, so at the cap we simply
 * cannot see how busy a home is. Treating that blindness as good news had the crawler trading away
 * live requests for homes that were just as full, over and over.
 */
class ListingScoreTest {

    private CrawlerConfig config;

    @BeforeEach
    void setUp() {
        config = CrawlerConfig.defaults();
        config.setMinSquareMeters(48);
        config.setGrossMonthlyIncome(4900);
        config.setIncomeMultiplier(42);
    }

    private Listing home(int price, int area, Boolean crowded, long hoursOld) {
        Listing l = new Listing();
        l.setId("amsterdam-1234ab-1-teststraat-466d07e2b38d35ba896c538bb34d5500");
        l.setTitle("Test home");
        l.setPrice(price);
        l.setLivingArea(area);
        l.setCrowded(crowded);
        l.setFirstSeenAt(Instant.now().minus(hoursOld, ChronoUnit.HOURS));
        return l;
    }

    @Test
    void aHomeStillTakingViewingsBeatsOneThatIsFull() {
        int open = ListingScore.of(home(1300, 60, false, 72), config).total();
        int full = ListingScore.of(home(1300, 60, true, 72), config).total();

        assertThat(open).isGreaterThan(full);
    }

    @Test
    void aBrandNewHomeIsWorthASlotEvenBeforeWeCanCheckIt() {
        // At the cap, MVGM shows us nothing about how busy a home is — so applying is the only way to
        // find out. For a home this new, that is exactly the bet worth making: being early is the
        // whole point.
        int brandNew = ListingScore.of(home(1300, 60, null, 1), config).total();
        int heldLongShot = ListingScore.of(home(1300, 60, true, 72), config).total();

        assertThat(brandNew).isGreaterThan(heldLongShot);
    }

    @Test
    void anOldHomeWeCouldNeverCheckMustNotDisplaceARequestWeHold() {
        // The loop, in one assertion. Nearly every old home in this market is full; guessing
        // otherwise had the crawler withdrawing live requests to probe dead ends, one after another.
        int oldUnknown = ListingScore.of(home(1300, 60, null, 200), config).total();
        int heldLongShot = ListingScore.of(home(1300, 60, true, 200), config).total();

        assertThat(oldUnknown).isLessThan(heldLongShot);
    }

    @Test
    void freshnessSeparatesTwoHomesThatAreOtherwiseIdentical() {
        int justListed = ListingScore.of(home(1300, 60, false, 1), config).total();
        int lastWeek = ListingScore.of(home(1300, 60, false, 200), config).total();

        assertThat(justListed).isGreaterThan(lastWeek);
    }

    @Test
    void spaceAndRentHeadroomOnlyBreakTiesBetweenHomesWeCanActuallyGet() {
        // A roomy, cheap, full home still loses to a plain one that is taking viewings. Winnability
        // dominates: no amount of niceness rescues a home you cannot be allocated.
        int roomyButFull = ListingScore.of(home(900, 110, true, 24), config).total();
        int plainButOpen = ListingScore.of(home(1380, 50, false, 24), config).total();

        assertThat(plainButOpen).isGreaterThan(roomyButFull);
    }

    @Test
    void theScoreStaysWithinItsHundred() {
        int best = ListingScore.of(home(700, 120, false, 0), config).total();

        assertThat(best).isBetween(0, 100);
    }

    @Test
    void aHomeInTheFourTimesRiskBandIsHeldBackBehindOneWeAreSafeFor() {
        // MVGM's income rule runs 3.5×–4×. At €4.900/mo income the 4×-safe rent is €1225. A €1350 home
        // clears 3.5× (we apply) but would fail a 4× home, so an equally-fresh home we are clearly
        // safe for takes the slot first — even though €1350 would otherwise score just as well.
        int borderline = ListingScore.of(home(1350, 60, false, 24), config).total(); // > €1225
        int safe = ListingScore.of(home(1200, 60, false, 24), config).total(); // <= €1225

        assertThat(safe).isGreaterThan(borderline);
    }

    @Test
    void theFourTimesCautionHoldsBackButDoesNotDisqualify() {
        // Held back, not thrown away: a borderline-rent home we can still win is worth more than a
        // full home, so it keeps taking a slot when nothing safer is open.
        int borderlineOpen = ListingScore.of(home(1350, 60, false, 24), config).total();
        int fullSafe = ListingScore.of(home(1200, 60, true, 24), config).total();

        assertThat(borderlineOpen).isGreaterThan(fullSafe);
    }

    @Test
    void theFourTimesCautionSparesAFullHomeItCouldNotWinAnyway() {
        // A full home is already lost regardless of income, so the caution must not touch it — were
        // the 30-point penalty applied here it would drag this long shot to a negative score, below
        // homes worth nothing. It stays positive because the penalty is winnable-homes only.
        int fullBorderline = ListingScore.of(home(1350, 60, true, 24), config).total();

        assertThat(fullBorderline).isGreaterThan(0);
    }
}

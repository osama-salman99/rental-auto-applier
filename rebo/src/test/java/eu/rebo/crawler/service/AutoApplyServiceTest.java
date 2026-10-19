package eu.rebo.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import eu.rebo.crawler.model.CrawlerConfig;
import eu.rebo.crawler.model.Listing;
import eu.rebo.crawler.repository.CrawlerConfigRepository;
import eu.rebo.crawler.repository.ListingRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutoApplyServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");
    private static final Instant ARMED = Instant.parse("2026-07-13T10:00:00Z");
    private static final Instant AFTER_ARM = Instant.parse("2026-07-13T11:00:00Z");
    private static final Instant BEFORE_ARM = Instant.parse("2026-07-13T09:00:00Z");

    private ListingRepository listingRepository;
    private CrawlerConfigRepository configRepository;
    private ReboFormClient formClient;
    private NotificationService notificationService;
    private AutoApplyService service;

    @BeforeEach
    void setUp() {
        listingRepository = mock(ListingRepository.class);
        configRepository = mock(CrawlerConfigRepository.class);
        formClient = mock(ReboFormClient.class);
        notificationService = mock(NotificationService.class);
        service = new AutoApplyService(listingRepository, configRepository, formClient,
                notificationService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** income 6000 → ceiling 1500. Fresh, complete, armed, live. */
    private CrawlerConfig armedConfig() {
        CrawlerConfig c = new CrawlerConfig();
        c.setAutoApplyEnabled(true);
        c.setAutoApplyDryRun(false);
        c.setAutoApplyArmedAt(ARMED);
        c.setIncomeMonthlyGross(6000);
        c.setIncomeType("Employer");
        c.setApplicantFirstName("Jan");
        c.setApplicantLastName("Jansen");
        c.setApplicantEmail("a@b.nl");
        c.setApplicantPhone("+31600000000");
        c.setTargetCity("Amsterdam");
        return c;
    }

    private Listing listing(String id, int price, double serviceCosts, Instant seen) {
        Listing l = new Listing();
        l.setId(id);
        l.setTitle("Home " + id);
        l.setCity("Amsterdam");
        l.setPostalCode("1000 AA");
        l.setPrice(price);
        l.setServiceCosts(serviceCosts);
        l.setStage("available");
        l.setFirstSeenAt(seen);
        return l;
    }

    private void withConfig(CrawlerConfig c) {
        when(configRepository.findById(1L)).thenReturn(Optional.of(c));
    }

    private void withListings(Listing... ls) {
        when(listingRepository.findAll()).thenReturn(List.of(ls));
    }

    @Test
    void registersAnAffordableFreshListing() {
        withConfig(armedConfig());
        withListings(listing("a", 1300, 50, AFTER_ARM)); // gross 1350 <= 1500-75
        when(formClient.submit(any())).thenReturn(new ReboFormClient.SubmitResult(true, 200, "ok"));

        AutoApplyService.Summary s = service.runOnce();

        assertThat(s.registered()).isEqualTo(1);
        verify(formClient).submit(any());
        verify(listingRepository).save(any());
    }

    @Test
    void failsClosedWhenNoIncome() {
        CrawlerConfig c = armedConfig();
        c.setIncomeMonthlyGross(null);
        withConfig(c);
        withListings(listing("a", 1000, 0, AFTER_ARM));

        service.runOnce();

        verifyNoInteractions(formClient);
    }

    @Test
    void failsClosedWhenContactIncomplete() {
        CrawlerConfig c = armedConfig();
        c.setApplicantPhone(null);
        withConfig(c);
        withListings(listing("a", 1000, 0, AFTER_ARM));

        service.runOnce();

        verifyNoInteractions(formClient);
    }

    @Test
    void dryRunSelectsButNeverWrites() {
        CrawlerConfig c = armedConfig();
        c.setAutoApplyDryRun(true);
        withConfig(c);
        withListings(listing("a", 1300, 50, AFTER_ARM));

        AutoApplyService.Summary s = service.runOnce();

        assertThat(s.dryRun()).isTrue();
        verifyNoInteractions(formClient);
        verify(listingRepository, never()).save(any());
    }

    @Test
    void skipsListingsFromBeforeArming() {
        withConfig(armedConfig());
        withListings(listing("old", 1000, 0, BEFORE_ARM));

        service.runOnce();

        verifyNoInteractions(formClient);
    }

    /**
     * The empty-DB / fresh-arm burst guard. When armedAt is null (auto-apply enabled on a wiped or
     * rebuilt DB — this VPS has no backups), the first pass must ONLY establish the baseline: it
     * registers nothing, however affordable the pre-existing backlog is, and stamps the arming
     * boundary so later passes act only on listings first seen from then on. Pre-fix, the seeder
     * stamped armedAt at startup before the first crawl, so the whole backlog read as "new since
     * arming" and was blasted with real registrations at once.
     */
    @Test
    void firstArmEstablishesBaselineInsteadOfBlastingTheBacklog() {
        CrawlerConfig c = armedConfig();
        c.setAutoApplyArmedAt(null); // not yet armed: fresh arm / freshly-populated DB
        withConfig(c);
        withListings(
                listing("a", 1200, 0, BEFORE_ARM),   // all affordable, in-scope, unapplied —
                listing("b", 1250, 0, AFTER_ARM),    // exactly what a first crawl on an empty DB
                listing("d", 1300, 50, BEFORE_ARM)); // produces

        AutoApplyService.Summary s = service.runOnce();

        // Baseline only: nothing registered, no form ever submitted on the backlog.
        assertThat(s.registered()).isZero();
        verifyNoInteractions(formClient);
        // The arming boundary is now stamped (to the clock) and persisted.
        assertThat(c.getAutoApplyArmedAt()).isEqualTo(NOW);
        verify(configRepository).save(c);

        // And a second pass — now armed at NOW — still registers nothing: the same backlog is all
        // pre-baseline, so it stays excluded (only genuinely new listings would qualify).
        AutoApplyService.Summary s2 = service.runOnce();
        assertThat(s2.registered()).isZero();
        verifyNoInteractions(formClient);
    }

    @Test
    void skipsAlreadyAppliedListings() {
        withConfig(armedConfig());
        Listing applied = listing("a", 1000, 0, AFTER_ARM);
        applied.setApplied(true);
        withListings(applied);

        service.runOnce();

        verifyNoInteractions(formClient);
    }

    @Test
    void aHomeAboveTheCeilingIsSkipped() {
        withConfig(armedConfig());
        withListings(listing("expensive", 1600, 50, AFTER_ARM)); // gross 1650 > 1500+75

        AutoApplyService.Summary s = service.runOnce();

        assertThat(s.registered()).isZero();
        assertThat(s.boundary()).isZero();
        verifyNoInteractions(formClient);
    }

    @Test
    void aHomeNearTheCeilingIsFlaggedNotRegistered() {
        withConfig(armedConfig());
        withListings(listing("edge", 1480, 0, AFTER_ARM)); // gross 1480, within 75 of 1500

        AutoApplyService.Summary s = service.runOnce();

        assertThat(s.boundary()).isEqualTo(1);
        assertThat(s.registered()).isZero();
        verify(formClient, never()).submit(any());
        verify(notificationService).sendCrawlerStatusNotification(any(), any(), any(), any());
    }

    @Test
    void flagsABoundaryHomeSoItIsNotNotifiedAgain() {
        withConfig(armedConfig());
        Listing edge = listing("edge", 1480, 0, AFTER_ARM);
        withListings(edge);

        service.runOnce(); // first pass: notifies once and latches the flag

        assertThat(edge.isBoundaryNotified()).isTrue();
        verify(notificationService, times(1)).sendCrawlerStatusNotification(any(), any(), any(), any());
    }

    @Test
    void doesNotReNotifyAnAlreadyFlaggedBoundaryHome() {
        withConfig(armedConfig());
        Listing edge = listing("edge", 1480, 0, AFTER_ARM);
        edge.setBoundaryNotified(true);
        withListings(edge);

        AutoApplyService.Summary s = service.runOnce();

        assertThat(s.boundary()).isEqualTo(1); // still counted as in-band
        verifyNoInteractions(notificationService);
    }

    @Test
    void doesNotLatchAppliedWhenSubmitRejected() {
        withConfig(armedConfig());
        withListings(listing("a", 1300, 50, AFTER_ARM));
        when(formClient.submit(any())).thenReturn(new ReboFormClient.SubmitResult(false, 422, "bad"));

        AutoApplyService.Summary s = service.runOnce();

        assertThat(s.registered()).isZero();
        verify(listingRepository, never()).save(any());
    }

    @Test
    void doesNothingWhenDisabled() {
        CrawlerConfig c = armedConfig();
        c.setAutoApplyEnabled(false);
        withConfig(c);

        service.runOnce();

        verifyNoInteractions(formClient);
        verify(listingRepository, never()).findAll();
    }

    @Test
    void failsClosedOnAListingWithNoReadableRent() {
        // Regression guard: a "prijs op aanvraag" home (or any entry the API returned without a
        // rental_price) persists with price=null. getGrossRent() reports that as EUR 0, which used
        // to sail under every affordability ceiling and auto-register a real Interesse form on a
        // home we cannot price. isOpenAndInScope must treat a null price as out of actionable scope
        // (fail closed): not registered, not even counted as a boundary home, form never submitted.
        withConfig(armedConfig());
        Listing noPrice = listing("np", 0, 0, AFTER_ARM);
        noPrice.setPrice(null); // unreadable rent
        withListings(noPrice);

        AutoApplyService.Summary s = service.runOnce();

        assertThat(s.registered()).isZero();
        assertThat(s.boundary()).isZero();
        verifyNoInteractions(formClient);
    }
}

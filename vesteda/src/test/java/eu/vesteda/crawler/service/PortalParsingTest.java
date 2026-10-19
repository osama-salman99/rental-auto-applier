package eu.vesteda.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Pinned against HTML captured from the live portal on 12 July 2026 (personal data scrubbed).
 *
 * <p>Every one of these assertions is a fact we had to learn by driving the real site, and each is
 * one a redesign could quietly break — so they are pinned to the real markup rather than to a
 * hand-written approximation of it.
 */
class PortalParsingTest {

    private static String fixture(String name) throws IOException {
        return new String(
                new ClassPathResource("portal/" + name).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- search page

    @Test
    void readsObjectHashPriceAndStatusFromTheSearchPage() throws IOException {
        List<PortalListing> listings = PortalListingService.parse(fixture("portal-search.html"));

        assertThat(listings).hasSize(5);

        PortalListing rudi =
                listings.stream()
                        .filter(l -> l.objectHash().equals("2b7b833ff5f4330dc739d1d120910640"))
                        .findFirst()
                        .orElseThrow();

        assertThat(rudi.address()).isEqualTo("Proefsingel 23");
        assertThat(rudi.city()).isEqualTo("Amsterdam");
        // "1.460,-" — Dutch thousands separator, and the rent the income rule is computed from.
        assertThat(rudi.price()).isEqualTo(1460);
        assertThat(rudi.bedrooms()).isEqualTo(2);
        assertThat(rudi.livingArea()).isEqualTo(71);
        assertThat(rudi.status()).isEqualTo(PortalListing.Status.FOR_RENT);
        assertThat(rudi.crowded()).isTrue();
    }

    /**
     * The three states the portal actually uses. "Rented with reservation" must not be read as
     * "reserved": the portal offers no request form for it at all, and treating it as open would
     * have us spending slots on homes that cannot accept them.
     */
    @Test
    void distinguishesRentedWithReservationFromReserved() {
        assertThat(PortalListing.Status.fromLabel("For rent")).isEqualTo(PortalListing.Status.FOR_RENT);
        assertThat(PortalListing.Status.fromLabel("Reserved")).isEqualTo(PortalListing.Status.RESERVED);
        assertThat(PortalListing.Status.fromLabel("Rented with reservation"))
                .isEqualTo(PortalListing.Status.CLOSED);
        assertThat(PortalListing.Status.fromLabel("Verhuurd onder voorbehoud"))
                .isEqualTo(PortalListing.Status.CLOSED);
        assertThat(PortalListing.Status.fromLabel("Gereserveerd")).isEqualTo(PortalListing.Status.RESERVED);

        assertThat(PortalListing.Status.FOR_RENT.acceptsRequests()).isTrue();
        assertThat(PortalListing.Status.RESERVED.acceptsRequests()).isTrue();
        assertThat(PortalListing.Status.CLOSED.acceptsRequests()).isFalse();
    }

    /** A label we have never seen must never be mistaken for an open home. */
    @Test
    void anUnknownStatusIsNotApplyable() {
        assertThat(PortalListing.Status.fromLabel("Binnenkort in de verkoop"))
                .isEqualTo(PortalListing.Status.UNKNOWN);
        assertThat(PortalListing.Status.UNKNOWN.acceptsRequests()).isFalse();
    }

    /** The join key that links a portal object to the API row carrying our firstSeenAt. */
    @Test
    void matchesPortalAddressesToApiTitlesDespiteFormatting() {
        // The API writes "Proeflaan 15 F, Amsterdam"; the portal writes "Proeflaan 15F".
        assertThat(PortalListingService.key("Proeflaan 15 F, Amsterdam", "Amsterdam"))
                .isEqualTo(PortalListingService.key("Proeflaan 15F", "Amsterdam"));
        // Jsoup unescapes &#039;s-gravenhage; both sides must land on the same key.
        assertThat(PortalListingService.key("Pisastraat 87, 's-Hertogenbosch", "'s-hertogenbosch"))
                .isEqualTo(PortalListingService.key("Pisastraat 87", "'s-Hertogenbosch"));
    }

    // ---------------------------------------------------------------- my next home

    @Test
    void readsTheStandbyCandidacyFromMyNextHome() throws IOException {
        List<Candidacy> held = PortalStatusService.parse(fixture("portal-mijnwoning.html"));

        assertThat(held).hasSize(1);
        Candidacy c = held.get(0);

        assertThat(c.objectHash()).isEqualTo("2b7b833ff5f4330dc739d1d120910640");
        // The candidacy id is NOT the object hash — withdrawing with the wrong one is a no-op.
        assertThat(c.uuid()).isEqualTo("05173c0fc23c417ec729638894df41cf");
        assertThat(c.status()).isEqualTo(Candidacy.Status.REQUESTED);
        assertThat(c.label()).contains("Proefsingel 23");

        // The home was already full when our request landed: we are a reserve, not in the draw.
        assertThat(c.standby()).isTrue();
        assertThat(c.isLongShot()).isTrue();

        assertThat(c.occupiesSlot()).isTrue();
        assertThat(c.isWithdrawable()).isTrue();
    }

    /**
     * Winning looks exactly like losing from the listing's side, so only the candidacy's own status
     * may authorise a withdrawal — and anything we don't recognise counts as a win in progress.
     */
    @Test
    void aDrawnOrUnfamiliarCandidacyIsNeverWithdrawable() {
        assertThat(Candidacy.Status.fromDot("interesse")).isEqualTo(Candidacy.Status.INTERESTED);
        assertThat(Candidacy.Status.fromDot("aanvraag")).isEqualTo(Candidacy.Status.REQUESTED);

        for (String drawn :
                List.of("uitnodiging", "bezichtiging", "optieaanvraag", "optie", "contract", "iets-nieuws")) {
            Candidacy c = new Candidacy("u", "h", Candidacy.Status.fromDot(drawn), false, "Somewhere 1");
            assertThat(c.status()).as(drawn).isEqualTo(Candidacy.Status.ADVANCED);
            assertThat(c.isWithdrawable()).as(drawn).isFalse();
            assertThat(c.occupiesSlot()).as(drawn).isTrue();
        }
    }

    /** A bare interest is free — it does not spend one of the five requests. */
    @Test
    void aSavedHomeDoesNotSpendASlot() {
        Candidacy saved = new Candidacy("u", "h", Candidacy.Status.INTERESTED, false, "Somewhere 1");
        assertThat(saved.occupiesSlot()).isFalse();
        assertThat(saved.isWithdrawable()).isFalse();
    }

    // ---------------------------------------------------------------- the apply form

    @Test
    void buildsACompleteApplyPayloadFromThePrefilledForm() throws IOException {
        ApplyForm form = new ApplyFormParser().parse(fixture("portal-aanvraag.html"));

        // The portal prefills the whole form from the account, and every document it asks for is
        // already on file — so a submission needs nothing from us but the declaration tick.
        assertThat(form.isComplete()).as(form.describeGaps()).isTrue();
        assertThat(form.missingFields()).isEmpty();
        assertThat(form.missingDocuments()).isEmpty();

        Map<String, String> f = form.fields();
        assertThat(f).containsEntry("a_samenhuren", "Alleen");
        assertThat(f).containsEntry("a_werksituatie", "Loondienst");
        // Vesteda scores you on being ready to move in as soon as the home is free.
        assertThat(f).containsEntry("a_wanneerhuren", "nu");
        assertThat(f.get("a_maandinkomen")).isNotBlank();

        // Documents ride along at account level rather than being re-uploaded per application.
        assertThat(form.uploadedDocuments()).containsKeys("ident_a", "loonst_1_a");
    }

    /**
     * The declaration tick is required and always arrives unchecked — it is the applicant's act, not
     * a stored answer, and we supply it at submit time. If the parser counted it as a gap in the
     * profile, every application would be blocked for ever, because the portal never prefills it.
     */
    @Test
    void theDeclarationTickIsNotAProfileGap() throws IOException {
        ApplyForm form = new ApplyFormParser().parse(fixture("portal-aanvraag.html"));

        assertThat(form.fields().get("akkoord_a")).isBlank();
        assertThat(form.missingFields()).doesNotContain("akkoord_a");
        assertThat(form.isComplete()).isTrue();
    }

    /**
     * Renting alone, so every partner field is inapplicable rather than missing. This is the whole
     * point of reading the form's own aCond map: without it, thirty blank p_* fields would look like
     * an incomplete profile and auto-apply would refuse to submit anything at all.
     */
    @Test
    void partnerFieldsAreInapplicableWhenRentingAlone() throws IOException {
        ApplyForm form = new ApplyFormParser().parse(fixture("portal-aanvraag.html"));

        assertThat(form.fields()).containsEntry("a_samenhuren", "Alleen");
        assertThat(form.fields().get("p_achternaam")).isBlank();
        assertThat(form.missingFields()).doesNotContain("p_achternaam", "p_geboortedatum", "ident_p");
    }
}

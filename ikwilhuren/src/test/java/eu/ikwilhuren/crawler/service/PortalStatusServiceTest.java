package eu.ikwilhuren.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Pinned against a real "my reactions" page. The slot count comes from here, and the whole design
 * rests on it being right: over-count and we stop applying while slots sit idle; under-count and we
 * push past the portal's cap of five.
 */
class PortalStatusServiceTest {

    private String html;

    @BeforeEach
    void setUp() throws IOException {
        html =
                new String(
                        new ClassPathResource("mijnwoning.html").getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
    }

    @Test
    void separatesMereInterestFromAnActualViewingRequest() {
        List<Candidacy> candidacies = PortalStatusService.parse(html);

        // The captured page holds three candidacies: two interests (one of them created just by
        // opening the form) and one genuine viewing request.
        assertThat(candidacies).hasSize(3);
        assertThat(candidacies)
                .filteredOn(c -> c.status() == Candidacy.Status.REQUESTED)
                .hasSize(1);
        assertThat(candidacies)
                .filteredOn(c -> c.status() == Candidacy.Status.INTERESTED)
                .hasSize(2);
    }

    @Test
    void onlyAViewingRequestSpendsASlot() {
        List<Candidacy> candidacies = PortalStatusService.parse(html);

        long spent = candidacies.stream().filter(Candidacy::occupiesSlot).count();

        // Interest is free — it is a wishlist entry, not an entry in the draw.
        assertThat(spent).isEqualTo(1);
    }

    @Test
    void readsMvgmsStepFromTheStatusDotNotTheEnglishLabel() {
        List<Candidacy> candidacies = PortalStatusService.parse(html);

        assertThat(candidacies)
                .filteredOn(c -> c.label().contains("Testweg"))
                .singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo(Candidacy.Status.REQUESTED));
    }

    @Test
    void aCandidacyDrawnForAViewingIsNeverWithdrawable() {
        // Winning looks identical from the listing's side — a property let to us also turns
        // "rented". The only thing that separates a win from a loss is our own step: once MVGM
        // draws us the card advances to uitnodiging, and withdrawing then would cancel the win.
        String drawn = html.replace("bg-status-aanvraag", "bg-status-uitnodiging");

        List<Candidacy> candidacies = PortalStatusService.parse(drawn);

        assertThat(candidacies)
                .filteredOn(c -> c.label().contains("Testweg"))
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(c.status()).isEqualTo(Candidacy.Status.ADVANCED);
                            assertThat(c.isWithdrawable()).isFalse();
                            assertThat(c.occupiesSlot()).isTrue(); // still one of the five
                        });
    }

    @Test
    void treatsAnUnknownStepAsAdvancedSoItCannotBeWithdrawnByMistake() {
        String odd = html.replace("bg-status-aanvraag", "bg-status-somethingnew");

        assertThat(PortalStatusService.parse(odd))
                .filteredOn(c -> c.label().contains("Testweg"))
                .singleElement()
                .satisfies(c -> assertThat(c.isWithdrawable()).isFalse());
    }

    @Test
    void everyLaterStepOfTheSevenIsTreatedAsAWinInProgress() {
        for (String step :
                List.of("uitnodiging", "bezichtiging", "controle", "aanbieding", "contract")) {
            assertThat(Candidacy.Status.fromDot(step))
                    .as("step %s", step)
                    .isEqualTo(Candidacy.Status.ADVANCED);
        }
        assertThat(Candidacy.Status.fromDot("interesse")).isEqualTo(Candidacy.Status.INTERESTED);
        assertThat(Candidacy.Status.fromDot("aanvraag")).isEqualTo(Candidacy.Status.REQUESTED);
    }

    @Test
    void spotsATicketThePortalHasAlreadyKilled() {
        // "Let subject to another candidate" — the draw happened and we lost. The slot is waste.
        List<Candidacy> candidacies = PortalStatusService.parse(html);

        assertThat(candidacies)
                .filteredOn(c -> c.label().contains("Testweg"))
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(c.lost()).isTrue();
                            assertThat(c.isWastedSlot()).isTrue();
                        });
    }

    @Test
    void spotsASaturatedPropertyAndTreatsItAsALongShot() {
        // What five real applications came back with. MVGM caps viewing requests per property, and
        // says so in prose — while omitting the status dot entirely, which is why a missing dot must
        // never be read as "advanced" or the slot would be held for ever.
        String saturated =
                html.replace(
                                "This property has been let subject to another candidate.",
                                "There are currently no more viewings possible. However, your interest"
                                        + " is registered. We recommend that you select another home.")
                        .replace("status-dot bg-status-aanvraag", "status-dot");

        assertThat(PortalStatusService.parse(saturated))
                .filteredOn(c -> c.label().contains("Testweg"))
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(c.status()).isEqualTo(Candidacy.Status.REQUESTED);
                            assertThat(c.saturated()).isTrue();
                            // A long shot, not a dead one: the interest stays on file and MVGM
                            // re-draws if its chosen candidate walks away. Worth keeping until a
                            // home we can actually win needs the slot.
                            assertThat(c.isLongShot()).isTrue();
                            assertThat(c.isWastedSlot()).isFalse();
                        });
    }

    @Test
    void aLiveRequestIsNotMistakenForADeadOne() {
        String live =
                html.replace(
                        "This property has been let subject to another candidate.",
                        "Your viewing request has been received. You are in the draw.");

        assertThat(PortalStatusService.parse(live))
                .filteredOn(c -> c.label().contains("Testweg"))
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(c.lost()).isFalse();
                            assertThat(c.saturated()).isFalse();
                            assertThat(c.isWastedSlot()).isFalse();
                            assertThat(c.occupiesSlot()).isTrue();
                        });
    }

    @Test
    void aDrawnCandidacyIsNeverReclaimedEvenIfTheProseLooksFinal() {
        // Belt and braces: advanced beats dead. If MVGM has drawn us, nothing in the prose may
        // cause a withdrawal.
        String drawn = html.replace("bg-status-aanvraag", "bg-status-uitnodiging");

        assertThat(PortalStatusService.parse(drawn))
                .filteredOn(c -> c.label().contains("Testweg"))
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(c.lost()).isTrue(); // the prose still says so
                            assertThat(c.isWastedSlot()).isFalse(); // but we were drawn: hands off
                            assertThat(c.isLongShot()).isFalse();
                        });
    }

    @Test
    void capturesTheUuidAndObjectHashNeededToActOnACandidacy() {
        List<Candidacy> candidacies = PortalStatusService.parse(html);

        assertThat(candidacies)
                .allSatisfy(
                        c -> {
                            assertThat(c.uuid()).hasSize(32);
                            assertThat(c.objectHash()).matches("[0-9a-f]{32}");
                            assertThat(c.label()).isNotBlank();
                        });
    }

    @Test
    void readsTheAddressForNotifications() {
        List<Candidacy> candidacies = PortalStatusService.parse(html);

        assertThat(candidacies).extracting(Candidacy::label).anyMatch(l -> l.contains("Testweg"));
    }

    @Test
    void reportsNothingRatherThanGuessingOnAnEmptyPage() {
        assertThat(PortalStatusService.parse("<html><body>No reactions yet</body></html>")).isEmpty();
    }
}

package eu.ikwilhuren.crawler.model;

import java.util.List;
import java.util.Map;

/**
 * The answers in MVGM's viewing-request form that are worth controlling from here, described in
 * plain language.
 *
 * <p>The form itself has around sixty fields, nearly all of them prefilled from the account profile
 * and none of our business — name, address, date of birth. What is listed here is what actually
 * decides an application: how you'd rent, what you earn, when you can move, and the note the
 * landlord reads. Everything else is left exactly as the portal prefilled it.
 */
public final class AnswerCatalogue {

    /**
     * @param field the portal's own form field name
     * @param label what a person would call it
     * @param help why it matters, or null
     * @param options value → label for a choice; empty for free text
     * @param numeric render as a number input
     */
    public record Answer(
            String field, String label, String help, Map<String, String> options, boolean numeric) {

        public boolean isChoice() {
            return !options.isEmpty();
        }
    }

    public record Group(String title, String blurb, List<Answer> answers) {}

    private static Answer choice(String field, String label, String help, String... pairs) {
        var map = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return new Answer(field, label, help, map, false);
    }

    private static Answer number(String field, String label, String help) {
        return new Answer(field, label, help, Map.of(), true);
    }

    private static Answer text(String field, String label, String help) {
        return new Answer(field, label, help, Map.of(), false);
    }

    public static final List<Group> GROUPS =
            List.of(
                    new Group(
                            "How you'd rent",
                            "The basics MVGM asks on every request.",
                            List.of(
                                    choice(
                                            "a_samenhuren",
                                            "Renting",
                                            "Renting with someone means MVGM counts both incomes.",
                                            "Alleen", "On my own",
                                            "Samen", "Together with someone"),
                                    choice(
                                            "gez_pers",
                                            "People moving in",
                                            null,
                                            "1", "1", "2", "2", "3", "3", "4", "4", "5", "5"),
                                    choice(
                                            "gez_kind",
                                            "Of whom children",
                                            null,
                                            "-", "None", "1", "1", "2", "2", "3", "3", "4", "4"),
                                    text(
                                            "a_wanneerhuren",
                                            "Can move in",
                                            "\"nu\" means right away — the strongest answer. Or name a"
                                                    + " month, like 2026-09-01."),
                                    choice(
                                            "a_parkeerplaats",
                                            "Parking space",
                                            "The one answer your MVGM profile doesn't already hold.",
                                            "Nee", "No",
                                            "WeetIkNogNiet", "Not sure yet",
                                            "Ja", "Yes"))),
                    new Group(
                            "Your situation",
                            "MVGM weighs these when deciding who is suitable.",
                            List.of(
                                    choice(
                                            "a_woonsituatie",
                                            "Living now",
                                            null,
                                            "Huur", "Renting",
                                            "Koop", "In a home I own",
                                            "Inwonend", "Living with family",
                                            "Verkocht", "Sold my home"),
                                    choice(
                                            "a_werksituatie",
                                            "Work",
                                            null,
                                            "Loondienst", "Employed",
                                            "Zelfstandig", "Self-employed",
                                            "Gepensioneerd", "Retired",
                                            "Uitkeringsgerechtigd", "On benefits",
                                            "Geen", "Not working"),
                                    choice(
                                            "a_werksituatiecontract",
                                            "Contract",
                                            "A permanent contract reads as more secure.",
                                            "Vast", "Permanent",
                                            "Tijdelijk", "Temporary"),
                                    choice(
                                            "a_sleutelberoep",
                                            "Key profession",
                                            "Teachers, nurses, police and similar get priority on some homes.",
                                            "Geen", "None of these",
                                            "Onderwijs", "Teaching",
                                            "Verpleegkundigen", "Nursing",
                                            "Verzorgenden", "Care work",
                                            "Dokters", "Doctor",
                                            "Politieagenten", "Police",
                                            "Brandweermedewerker", "Fire service"))),
                    new Group(
                            "Money",
                            "Your income lives in Settings, because it also decides your rent ceiling —"
                                    + " one number, so the ceiling we work out and the figure MVGM sees"
                                    + " can never disagree.",
                            List.of(
                                    choice(
                                            "a_spaargeld_jn",
                                            "Have savings",
                                            null,
                                            "Nee", "No",
                                            "Ja", "Yes"),
                                    number("a_spaargeld", "Savings (€)", "Only if you answered yes."),
                                    choice(
                                            "a_krediet_jn",
                                            "Have a loan or credit",
                                            null,
                                            "Nee", "No",
                                            "Ja", "Yes"),
                                    number(
                                            "a_krediet_maandlast",
                                            "Monthly repayment (€)",
                                            "Only if you answered yes."))),
                    new Group(
                            "Your message",
                            "The one free-text field the landlord actually reads. Optional.",
                            List.of(
                                    text(
                                            "AlgemeneOpmerking",
                                            "Note to the landlord",
                                            "Keep it short. Max 450 characters."))));

    /** Every field we manage, in order. */
    public static List<Answer> all() {
        return GROUPS.stream().flatMap(g -> g.answers().stream()).toList();
    }

    private AnswerCatalogue() {}
}

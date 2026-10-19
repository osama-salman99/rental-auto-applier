package eu.vesteda.crawler.web;

import eu.vesteda.crawler.model.CrawlerConfig;
import eu.vesteda.crawler.model.Listing;
import eu.vesteda.crawler.service.Candidacy;
import eu.vesteda.crawler.service.ListingScore;
import java.time.Duration;
import java.time.Instant;

/**
 * One home, dressed for the console: the score, and a plain-English verdict saying where it stands.
 *
 * <p>The verdict exists because an unexplained number makes the machine look arbitrary. It is
 * acting on the user's behalf without asking, so every card has to be able to say why it is where it
 * is.
 */
public record ListingView(
        Listing listing, String kind, int score, String why, String verdict, boolean fresh, Integer rent) {

    /** A home we hold a viewing request on. */
    public static ListingView inDraw(Listing l, CrawlerConfig config, Candidacy c) {
        ListingScore s = ListingScore.of(l, config);
        String verdict;
        if (c != null && c.status() == Candidacy.Status.ADVANCED) {
            verdict = "You have been drawn. Vesteda is processing your application — this one is never withdrawn.";
        } else if (Boolean.TRUE.equals(l.getStandby())) {
            verdict =
                    "Requested, but the home was already full — you are a reserve. It is kept because a reserve"
                            + " still wins if their first choice walks away, and it is the first slot given up when"
                            + " a home you could win outright appears.";
        } else {
            verdict = "Viewing requested. You are in the draw.";
        }
        return of(l, "in-draw", s, verdict);
    }

    /** A home the allocator would apply for as soon as a slot frees up. */
    public static ListingView queued(Listing l, CrawlerConfig config) {
        ListingScore s = ListingScore.of(l, config);
        return of(l, "queued", s, "Next in line — it gets a request as soon as a slot frees up.");
    }

    /** A home that needs a human before anything can happen. */
    public static ListingView needsYou(Listing l, CrawlerConfig config, String problem) {
        return of(l, "needs-you", ListingScore.of(l, config), problem);
    }

    private static ListingView of(Listing l, String kind, ListingScore s, String verdict) {
        return new ListingView(l, kind, s.total(), s.explain(), verdict, isFresh(l), l.rent());
    }

    /** Six hours. Long enough to survive a night's sleep, short enough to still mean "hurry". */
    private static boolean isFresh(Listing l) {
        return l.getFirstSeenAt() != null
                && Duration.between(l.getFirstSeenAt(), Instant.now()).toHours() <= 6;
    }

    /** Target-group restrictions on this home — the amber chips that say why it may be hard to win. */
    public java.util.List<String> restrictions() {
        return listing.restrictionLabels();
    }

    // Detail-page facts (null until the enrichment pass has read the home's own page).
    public Integer serviceCosts() {
        return listing.getServiceCosts();
    }

    public String availableFrom() {
        return listing.getAvailableFrom();
    }

    public String availabilityLabel() {
        return listing.availabilityLabel();
    }

    public String energyLabel() {
        return listing.getEnergyLabel();
    }

    public String dwellingType() {
        return listing.getDwellingType();
    }
}

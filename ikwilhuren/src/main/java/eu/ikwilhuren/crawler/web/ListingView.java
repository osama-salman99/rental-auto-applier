package eu.ikwilhuren.crawler.web;

import eu.ikwilhuren.crawler.model.ApplicationStatus;
import eu.ikwilhuren.crawler.model.CrawlerConfig;
import eu.ikwilhuren.crawler.model.Listing;
import eu.ikwilhuren.crawler.service.ListingScore;
import java.time.Duration;
import java.time.Instant;

/**
 * A listing plus the answer to the only question worth asking about it: are we in the draw for this
 * one, and if not, why not?
 *
 * <p>The verdict is deliberately short. There are hundreds of listings and only five can ever be
 * applied for, so the home page summarises the also-rans by reason rather than printing a paragraph
 * about each — {@link #reason()} is the bucket it gets counted in.
 */
public record ListingView(
        Listing listing, String kind, String verdict, String reason, int score, String why) {

    /**
     * Seen in the last 48 hours — the window in which a home is still worth racing for, and in which
     * only Plus accounts may respond. Deliberately not MVGM's "Nieuw" badge: that is on every listing
     * on every page and means nothing.
     */
    public boolean isFresh() {
        return listing.getFirstSeenAt() != null
                && Duration.between(listing.getFirstSeenAt(), Instant.now()).toHours() <= 48;
    }

    public boolean isInDraw() {
        return "in-draw".equals(kind);
    }

    /**
     * MVGM has drawn us: the candidacy advanced past a plain request (to {@code uitnodiging} or
     * beyond). This is the best state a request can be in — a viewing invitation is on its way — so
     * it is neither a live shot nor a long shot, but its own thing, and it is flagged as such.
     */
    public boolean isInvited() {
        return isInDraw() && listing.isInvited();
    }

    /**
     * A slot we hold on a home that is already full. MVGM stopped taking viewings before our request
     * landed, so it only pays off if their first choice walks away — the difference between a request
     * still in the running and one that is merely on file. {@code crowded} is nullable: unknown is
     * treated as a live shot, never a long shot, so a home is only called a long shot once we have
     * actually seen it is full. An invitation trumps all of this: once drawn, a home is never a long
     * shot, even if it was full when we got in.
     */
    public boolean isLongShot() {
        return isInDraw() && !isInvited() && Boolean.TRUE.equals(listing.getCrowded());
    }

    public boolean isCandidate() {
        return "queued".equals(kind);
    }

    /** in-draw · queued · blocked · ruled-out · gone */
    public static ListingView of(Listing l, CrawlerConfig config) {
        ListingScore s = ListingScore.of(l, config);
        return build(l, config, s.total(), s.explain());
    }

    private static ListingView build(Listing l, CrawlerConfig config, int score, String why) {
        String status = l.getApplicationStatus();

        if (ApplicationStatus.APPLIED.equals(status) && l.isApplied()) {
            return v(l, "in-draw", "You're in the draw", "In the draw", score, why);
        }
        if (ApplicationStatus.DRY_RUN.equals(status)) {
            return v(l, "queued", "Dry run — nothing sent", "Dry run", score, why);
        }
        if (ApplicationStatus.SKIPPED.equals(status)) {
            return v(l, "blocked", detail(l, "Can't apply"), "Needs your attention", score, why);
        }
        if (ApplicationStatus.FAILED.equals(status)) {
            return v(l, "blocked", detail(l, "Failed"), "Needs your attention", score, why);
        }
        if (ApplicationStatus.WITHDRAWN.equals(status) && !"available".equals(l.getStage())) {
            return v(l, "gone", "Withdrawn — the home is gone", "Gone", score, why);
        }

        if (!"available".equals(l.getStage())) {
            return v(l, "gone", "Already taken", "Already taken", score, why);
        }

        if (!config.matchesCity(l.getCity())) {
            return v(l, "ruled-out", "Not in your search area", "Outside your search area", score, why);
        }

        Integer ceiling = config.getRentCeiling();
        if (ceiling != null && l.getPrice() != null && l.getPrice() > ceiling) {
            return v(
                    l,
                    "ruled-out",
                    "Over your €" + ceiling + " ceiling",
                    "Too expensive for your income",
                    score,
                    why);
        }
        if (config.getMinSquareMeters() != null
                && (l.getLivingArea() == null || l.getLivingArea() < config.getMinSquareMeters())) {
            return v(
                    l,
                    "ruled-out",
                    "Under " + config.getMinSquareMeters() + " m²",
                    "Too small",
                    score,
                    why);
        }
        if (config.getMinBedrooms() != null
                && (l.getBedrooms() == null || l.getBedrooms() < config.getMinBedrooms())) {
            return v(l, "ruled-out", "Too few bedrooms", "Too few bedrooms", score, why);
        }

        if (Boolean.TRUE.equals(l.getCrowded())) {
            // Not ruled out — just a poor bet. MVGM stops taking viewings once a home is full, but it
            // keeps your interest on file and re-draws if its chosen candidate walks away.
            return v(
                    l,
                    "queued",
                    "Long shot — the home is already full, so it only counts if MVGM's first choice"
                            + " walks away",
                    "Waiting for a slot",
                    score,
                    why);
        }

        return v(l, "queued", "Worth applying for — next in line", "Waiting for a slot", score, why);
    }

    private static ListingView v(
            Listing l, String kind, String verdict, String reason, int score, String why) {
        return new ListingView(l, kind, verdict, reason, score, why);
    }

    private static String detail(Listing l, String prefix) {
        String d = l.getApplicationDetail();
        return d == null ? prefix : prefix + ": " + d;
    }
}

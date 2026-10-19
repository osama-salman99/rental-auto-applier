package eu.vesteda.crawler.service;

import eu.vesteda.crawler.model.CrawlerConfig;
import eu.vesteda.crawler.model.Listing;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * How good a home is to hold a viewing request on, as a single number out of 100.
 *
 * <p>Vesteda lets you hold five requests at once, so the question is never "is this one worth
 * applying to?" but "are these the best five available right now?" Everything eligible is scored,
 * the top five are what we hold, and anything that drops out is traded in. This is the scoring
 * allocator the sibling ikwilhuren crawler proved out — but the winnability signal it runs on had to
 * be rebuilt from scratch, because Vesteda's portal hides the one thing ikwilhuren could see.
 *
 * <p><b>On ikwilhuren, you can read how crowded a home is before applying.</b> The portal shows a
 * "many viewing requests" warning to anyone with a free slot, so winnability is an observable fact:
 * a home still taking viewings scores high, a full one scores low, and the score sorts itself out.
 *
 * <p><b>On Vesteda you cannot.</b> A home reads "For rent" on the search page right up until you
 * submit, at which point it may answer "no more viewings possible — you are a standby". And the
 * "many viewing requests" warning here is worthless as a discriminator: it sits on 94 of the 115
 * available homes, and on <em>every</em> home inside a realistic budget. So crowding cannot rank
 * anything. The only signal left for whether a home can still be won is <b>how new it is</b> — an
 * old Amsterdam home in this price range is, empirically, already full (every one we have applied to
 * came back a standby). So on Vesteda <b>age is winnability</b>, and the score is built around it:
 *
 * <ul>
 *   <li><b>Fresh &amp; open (≤48h)</b> — the only genuinely winnable case. Get in before it fills.
 *       This is where a slot should go, and a fresh home rightly displaces any long-shot we hold.
 *   <li><b>Long shot</b> — a home we already hold that came back a standby, or one the portal marks
 *       reserved. Not worthless (a standby still wins if the first choice walks away), so it is kept
 *       over an empty slot — but it yields to any fresh home.
 *   <li><b>Stale &amp; open (&gt;48h, never tried)</b> — reads "for rent" but is almost certainly
 *       full. Worth a request only to fill a slot that would otherwise sit idle, and — this is the
 *       part that matters — <b>never worth displacing a long shot we already hold</b>. Re-probing
 *       old full homes by trading away live requests is exactly the loop that once thrashed the
 *       sibling account, so the bands below are spaced so a stale home, however nice, can never
 *       outrank a long shot.
 * </ul>
 */
public record ListingScore(int total, List<String> reasons) {

    /** FOR_RENT and new enough that being early can still win it. */
    private static final int FRESH_OPEN = 55;

    /** Held-and-standby, or portal-reserved: a long shot we keep but will trade for anything fresh. */
    private static final int LONG_SHOT = 15;

    /** FOR_RENT but old — almost certainly full. Fills idle slots only; never displaces a long shot. */
    private static final int STALE_OPEN = 6;

    /** A home seen within this window is still worth racing for. Past it, assume it has filled. */
    private static final Duration FRESH_WINDOW = Duration.ofHours(48);

    public static ListingScore of(Listing l, CrawlerConfig config) {
        List<String> why = new ArrayList<>();

        boolean longShot = Boolean.TRUE.equals(l.getStandby()) || "RESERVED".equals(l.getPortalStatus());
        boolean fresh = isFresh(l);

        int score;
        boolean allowQuality;
        if (longShot) {
            score = LONG_SHOT;
            allowQuality = true;
            why.add(
                    Boolean.TRUE.equals(l.getStandby())
                            ? "already full — a reserve we keep until something fresh appears"
                            : "reserved for someone else — a reserve only");
        } else if (fresh) {
            score = FRESH_OPEN;
            allowQuality = true;
            why.add("open and newly listed — the moment it can still be won");
        } else {
            score = STALE_OPEN;
            allowQuality = false; // niceness must not let a stale home jump a long shot we hold
            why.add("open, but up a while — most likely full, worth a slot only if one is spare");
        }

        // Freshness only counts inside the fresh-open tier — it is what orders one new home against
        // another. On a long shot or a stale home it means nothing (a standby's age doesn't change
        // its odds), and letting it accumulate there would blur the bands the tiers deliberately set.
        if (fresh) {
            int freshBonus = freshness(l);
            score += freshBonus;
            if (freshBonus >= 22) {
                why.add("listed in the last few hours");
            }
        }

        if (allowQuality) {
            int space = space(l, config);
            score += space;
            if (l.getLivingArea() != null && space >= 7) {
                why.add(l.getLivingArea() + " m2 is roomy");
            }

            int headroom = headroom(l, config);
            score += headroom;
            if (headroom >= 6) {
                why.add("comfortably inside your budget");
            }

            if (l.getBedrooms() != null && l.getBedrooms() >= 2) {
                score += 2;
                why.add(l.getBedrooms() + " bedrooms");
            }
        } else {
            // A capped nudge so that, among equally-stale homes competing for a spare slot, the
            // roomier and cheaper one is preferred — but never enough (max +5) to reach the long-shot
            // band and unseat a request we already hold.
            score += Math.min(5, space(l, config) / 2 + headroom(l, config) / 2);
        }

        // Winnability: a home Vesteda hands to a priority group first is harder for a general
        // applicant to win — the queue is partly spent before we reach it. Mark it down where it
        // actually decides something (a fresh race, or ordering two reserves), but bounded so it can
        // only ever reorder within a tier, never push a home across one. A fresh home stays ahead of
        // every long shot; a long shot stays ahead of every stale one. So this sharpens the choice
        // between winnable homes without ever reopening the trade-a-reserve-for-a-stale-probe loop.
        int penalty = 0;
        List<String> priorityFor = new ArrayList<>();
        if (Boolean.TRUE.equals(l.getKeyProfessions()) && !config.isApplicantKeyProfession()) {
            penalty += 8;
            priorityFor.add("key professions");
        }
        if (Boolean.TRUE.equals(l.getPrioritySocialHousing())
                && !config.isApplicantSocialHousingPriority()) {
            penalty += 6;
            priorityFor.add("people leaving social housing");
        }
        if (penalty > 0 && (fresh || longShot)) {
            score -= fresh ? penalty : Math.min(3, penalty);
            why.add("prioritised for " + String.join(" and ", priorityFor) + " — harder to win");
        }

        return new ListingScore(score, why);
    }

    private static boolean isFresh(Listing l) {
        return l.getFirstSeenAt() != null
                && Duration.between(l.getFirstSeenAt(), Instant.now()).compareTo(FRESH_WINDOW) <= 0;
    }

    /**
     * 0–25, from when <em>we</em> first saw the home — never Vesteda's own "new" badge, which is a
     * broken global value stamped identically on every listing. Within the fresh band this is what
     * separates a home listed an hour ago from one listed yesterday; on an old home it is ~0, so it
     * cannot lift a stale home into contention.
     */
    private static int freshness(Listing l) {
        if (l.getFirstSeenAt() == null) {
            return 0;
        }
        long hours = Duration.between(l.getFirstSeenAt(), Instant.now()).toHours();
        if (hours <= 1) return 25;
        if (hours <= 6) return 22;
        if (hours <= 24) return 16;
        if (hours <= 48) return 11;
        if (hours <= 24 * 7) return 4;
        return 0;
    }

    /** 0–10, measured against the smallest home you said you would take. */
    private static int space(Listing l, CrawlerConfig config) {
        if (l.getLivingArea() == null) {
            return 0;
        }
        int floor = config.getMinSquareMeters() == null ? 40 : config.getMinSquareMeters();
        return clamp((l.getLivingArea() - floor) / 5, 0, 10);
    }

    /**
     * 0–8. Rent well under the ceiling is a stronger application, not merely a cheaper one: Vesteda
     * scores candidates on the income requirement, and clearing it comfortably earns the top mark
     * that goes into their lottery.
     */
    private static int headroom(Listing l, CrawlerConfig config) {
        Integer ceiling = config.getStrictRentCeiling();
        Integer rent = l.rent();
        if (ceiling == null || rent == null || ceiling <= 0 || rent > ceiling) {
            return 0;
        }
        return clamp((ceiling - rent) * 8 / ceiling, 0, 8);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public String explain() {
        return String.join(" · ", reasons);
    }
}

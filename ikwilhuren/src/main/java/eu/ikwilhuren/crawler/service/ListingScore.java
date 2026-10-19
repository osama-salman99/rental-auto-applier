package eu.ikwilhuren.crawler.service;

import eu.ikwilhuren.crawler.model.CrawlerConfig;
import eu.ikwilhuren.crawler.model.Listing;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * How good a listing is to apply for, as a single number out of 100.
 *
 * <p>We may hold five viewing requests. The job is therefore not "is this one worth applying to?"
 * but "are these the best five available?" — so every eligible listing gets a score, the top five
 * are the ones we hold, and anything that falls out of the top five is traded in. A score, rather
 * than a pile of rules, is what makes that decidable and repeatable.
 *
 * <p>The weights say what we believe:
 *
 * <ul>
 *   <li><b>Can it still be won (0–55)</b> — the largest term by far. MVGM caps viewing requests per
 *       home; once a home is full it stops accepting them and answers with "no more viewings
 *       possible". Such a home is not worthless — the interest stays on file, and MVGM re-draws if
 *       its chosen candidate walks away — but it is a long shot, and it should lose its slot to any
 *       home that can still be won properly.
 *   <li><b>How fresh (0–25)</b> — a home fills up over its first days, and for the first 48 hours
 *       only Plus accounts may respond at all. Early is when the field is thin.
 *   <li><b>How good a home (0–20)</b> — space, then rent headroom, then a second bedroom. This only
 *       breaks ties between homes we can actually get; it never rescues one we can't.
 * </ul>
 */
public record ListingScore(int total, List<String> reasons) {

    private static final int WINNABLE = 55;
    private static final int SATURATED = 5;

    /**
     * Below {@link #SATURATED} on purpose. A home we could never check, and which is no longer new,
     * must not displace a request we already hold — otherwise the crawler spends its afternoon
     * trading live requests for homes that turn out to be just as full.
     */
    private static final int UNKNOWN_AND_STALE = 2;

    /**
     * How much a home in the 4×-risk rent band is held back. MVGM's income rule runs 3.5×–4× the
     * rent; we qualify at 3.5× up to the ceiling, but a home that turns out to want 4× rejects the
     * application outright — a wasted slot on a home that could never be won. Homes whose rent sits
     * above the 4×-safe point (but within the 3.5× ceiling) carry that risk, so a home we are clearly
     * safe for fills a slot first. Sized so any safe winnable home outranks any borderline one, even
     * a fresher borderline one, while a borderline home we can still win stays ahead of a full one —
     * held back, not disqualified.
     */
    private static final int FOUR_X_RISK = 30;

    public static ListingScore of(Listing l, CrawlerConfig config) {
        List<String> why = new ArrayList<>();
        int score = 0;
        boolean winnable = false;

        if (Boolean.TRUE.equals(l.getCrowded())) {
            score += SATURATED;
            why.add("already full — long shot only");
        } else if (Boolean.FALSE.equals(l.getCrowded())) {
            score += WINNABLE;
            winnable = true;
            why.add("still accepting viewings");
        } else if (freshness(l) >= 10) {
            // Never managed to check — but new enough that finding out is worth a slot. MVGM hides
            // the crowding warning from anyone who has spent all five requests, so at the cap the
            // only way to learn about a new home is to apply for it. That is the whole strategy: be
            // early, and a home this new is exactly the kind you can still be early for.
            score += WINNABLE;
            winnable = true;
            why.add("brand new — worth finding out");
        } else {
            // Never managed to check, and no longer new. Nearly everything old in this market is
            // full, so guessing otherwise would have us trading away the requests we hold to probe
            // homes that are almost certainly dead ends. Rank it below the long shots we already
            // have: it can still be applied for out of a genuinely free slot, but it will not
            // displace anything.
            score += UNKNOWN_AND_STALE;
            why.add("not checked — and no longer new");
        }

        // The 4× rejection risk only matters for a home we could otherwise win: a full home is
        // already lost regardless of income, so piling the penalty onto it would be meaningless (and
        // would drag a long shot below homes worth even less).
        if (winnable && exceedsSafeIncomeCeiling(l, config)) {
            score -= FOUR_X_RISK;
            why.add("near your income limit — some homes here want 4× income");
        }

        int fresh = freshness(l);
        score += fresh;
        if (fresh >= 20) {
            why.add("just listed");
        } else if (fresh >= 10) {
            why.add("listed recently");
        } else if (fresh == 0) {
            why.add("been up a while");
        }

        int space = space(l, config);
        score += space;
        if (l.getLivingArea() != null && space >= 7) {
            why.add(l.getLivingArea() + " m² is roomy");
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

        return new ListingScore(score, why);
    }

    /**
     * 0–25. A home fills up as it ages, and the first 48 hours are Plus-only — so the whole strategy
     * turns on spotting a genuinely new listing.
     *
     * <p>Measured from when <em>we</em> first saw it, not from MVGM's "Nieuw" badge. That badge is on
     * every single listing on every page, all carrying the same nonsense title ("Sinds
     * 2.1878240740741 dagen online"), so it is a broken global value rather than a per-home fact.
     * Trusting it would have given every listing full marks and left a genuinely new home no way to
     * stand out — which is the one thing this score exists to notice.
     */
    private static int freshness(Listing l) {
        if (l.getFirstSeenAt() == null) {
            return 0;
        }
        long hours = Duration.between(l.getFirstSeenAt(), Instant.now()).toHours();
        if (hours <= 1) return 25;
        if (hours <= 6) return 22;
        if (hours <= 24) return 16;
        if (hours <= 48) return 10;
        if (hours <= 24 * 7) return 4;
        return 0;
    }

    /** 0–10, measured against the smallest home you said you'd take. */
    private static int space(Listing l, CrawlerConfig config) {
        if (l.getLivingArea() == null) {
            return 0;
        }
        int floor = config.getMinSquareMeters() == null ? 40 : config.getMinSquareMeters();
        return clamp((l.getLivingArea() - floor) / 5, 0, 10);
    }

    /**
     * True when the rent needs more than a 3.5× income to clear the stricter 4× rule some homes use.
     * The ceiling ({@code getRentCeiling}) is the 3.5× point; the 4×-safe point is the rent at which
     * income covers four times the annual rent — {@code grossMonthlyIncome × 12 ÷ 48}. A rent between
     * the two clears 3.5× (so we apply) but would fail a 4× home (so it is held back). If income is
     * unknown, we can't tell, so we don't hold anything back.
     */
    private static boolean exceedsSafeIncomeCeiling(Listing l, CrawlerConfig config) {
        Integer income = config.getGrossMonthlyIncome();
        Integer ceiling = config.getRentCeiling();
        if (income == null || ceiling == null || l.getPrice() == null || income <= 0) {
            return false;
        }
        int safeCeiling = income * 12 / 48;
        return l.getPrice() > safeCeiling && l.getPrice() <= ceiling;
    }

    /** 0–8. Rent well under the ceiling is a stronger application, not just a cheaper one. */
    private static int headroom(Listing l, CrawlerConfig config) {
        Integer ceiling = config.getRentCeiling();
        if (ceiling == null || l.getPrice() == null || ceiling <= 0) {
            return 0;
        }
        return clamp((ceiling - l.getPrice()) * 8 / ceiling, 0, 8);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public String explain() {
        return String.join(" · ", reasons);
    }
}

package eu.ikwilhuren.crawler.service;

import eu.ikwilhuren.crawler.model.Listing;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads MVGM's own warning that a property is oversubscribed.
 *
 * <p>On a property page the portal prints, to logged-in users only: <em>"This house has many
 * viewing requests. Increase your chance by selecting a different property."</em> That warning
 * turns out to predict a dead end. MVGM caps viewing requests per property; every listing we
 * applied to carrying this warning came back, within minutes, as "there are currently no more
 * viewings possible… we recommend that you select another home". Five slots, five dead tickets.
 *
 * <p>So the warning is treated as disqualifying rather than advisory: a saturated property cannot
 * be won, and a request on one holds a slot that a fresh listing could have used. The result is a
 * different strategy from the one this crawler started with — not "hold five tickets at all times"
 * but "hold only tickets that can still win, and keep the rest of the budget ready".
 */
@Service
public class CompetitionService {

    private static final Logger log = LoggerFactory.getLogger(CompetitionService.class);

    /** The warning is only rendered for logged-in users, so this needs the portal session. */
    private static final String[] CROWDED_MARKERS = {
        "many viewing requests", "veel bezichtigingsaanvragen", "veel aanvragen"
    };

    /** Saturation moves slowly; re-reading every property on every pass would be rude and pointless. */
    private static final Duration TTL = Duration.ofMinutes(20);

    private final PortalSession session;

    public CompetitionService(PortalSession session) {
        this.session = session;
    }

    /**
     * True when MVGM is warning that the property already has many viewing requests. Cached, so a
     * listing is re-checked at most every {@link #TTL}.
     */
    public boolean isCrowded(Listing listing) {
        if (listing.getCrowded() != null && isFresh(listing.getCompetitionCheckedAt())) {
            return listing.getCrowded();
        }
        refresh(listing);
        return Boolean.TRUE.equals(listing.getCrowded());
    }

    /**
     * Whether {@link #isCrowded} would re-read the property page (no cached verdict yet, or past
     * {@link #TTL}) rather than answer from cache. Lets a caller persist the listing only when the
     * check actually refreshed it in place, instead of re-saving an unchanged row on every pass.
     */
    public boolean needsRecheck(Listing listing) {
        return listing.getCrowded() == null || !isFresh(listing.getCompetitionCheckedAt());
    }

    /**
     * Re-reads the property page and updates the listing in place (caller persists).
     *
     * <p>Two rules keep this honest, and both were learned from a feedback loop that had the crawler
     * thrashing MVGM's portal:
     *
     * <ul>
     *   <li><b>Saturation only ever goes one way.</b> A home that filled up does not un-fill. Once we
     *       know it is full — from this page, or from MVGM telling us outright that no viewing can
     *       come of our request — that verdict stands.
     *   <li><b>Silence is not a "no".</b> MVGM only renders the crowding warning to someone who could
     *       actually apply; hold all five requests and it vanishes from every page. Reading that
     *       absence as "not full" is what caused the loop: scores leapt, held requests were traded
     *       away for homes that were just as full, those came back "no more viewings possible", and
     *       round it went. So a page that offers no way to request a viewing tells us nothing, and we
     *       keep what we knew.
     * </ul>
     */
    public void refresh(Listing listing) {
        String url = "https://ikwilhuren.nu/object/" + listing.getSlug() + "/";
        try {
            String html = session.getAuthenticated(url, "https://ikwilhuren.nu/aanbod/");

            if (mentionsCrowding(html)) {
                listing.setCrowded(Boolean.TRUE);
                listing.setCompetitionCheckedAt(Instant.now());
                return;
            }
            if (Boolean.TRUE.equals(listing.getCrowded())) {
                listing.setCompetitionCheckedAt(Instant.now()); // still full; just re-checked
                return;
            }
            if (!offersViewingRequest(html)) {
                log.debug("Can't tell how busy {} is — no request link on the page", listing.getTitle());
                return; // unobservable: leave the verdict (possibly null) alone
            }
            listing.setCrowded(Boolean.FALSE);
            listing.setCompetitionCheckedAt(Instant.now());
        } catch (Exception e) {
            // Unknown is not the same as uncrowded. Leave the previous verdict in place.
            log.warn("Could not read competition for {}: {}", listing.getTitle(), e.getMessage());
        }
    }

    /**
     * Does the page offer a way to request a viewing? If not, MVGM is showing us a stripped-down page
     * — typically because all five of our requests are already spent — and its silence about crowding
     * means nothing.
     */
    static boolean offersViewingRequest(String html) {
        return html != null && html.contains("/bezichtigingsaanvraag/");
    }

    static boolean mentionsCrowding(String html) {
        if (html == null) {
            return false;
        }
        String lower = html.toLowerCase();
        for (String marker : CROWDED_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFresh(Instant checkedAt) {
        return checkedAt != null && Duration.between(checkedAt, Instant.now()).compareTo(TTL) < 0;
    }
}

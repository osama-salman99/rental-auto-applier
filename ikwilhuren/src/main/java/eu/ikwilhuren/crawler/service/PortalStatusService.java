package eu.ikwilhuren.crawler.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads the portal's "my reactions" page, which is the only trustworthy answer to two questions:
 * how many of the five viewing-request slots are in use, and did a submission actually land.
 *
 * <p>Deliberately structural rather than text-based: the account renders in English, but the
 * language is a user setting we don't control, so matching on "Request for viewing received" would
 * be a booby trap. Each card instead carries MVGM's step as a CSS class on its status dot —
 * {@code bg-status-interesse}, {@code -aanvraag}, {@code -uitnodiging}, and on through the seven
 * steps to {@code -contract} — which is the same in every language.
 */
@Service
public class PortalStatusService {

    private static final Logger log = LoggerFactory.getLogger(PortalStatusService.class);

    static final String MIJN_WONING_URL = PortalSession.BASE + "/mijnwoning/";
    private static final Pattern HASH = Pattern.compile("/bezichtigingsaanvraag/([0-9a-f]{32})/");
    private static final Pattern STATUS_DOT = Pattern.compile("bg-status-([a-z0-9-]+)");

    private final PortalSession session;

    public PortalStatusService(PortalSession session) {
        this.session = session;
    }

    public List<Candidacy> candidacies() {
        return parse(session.getAuthenticated(MIJN_WONING_URL, PortalSession.BASE + "/"));
    }

    /** How many of the five simultaneous viewing requests are currently spent. */
    public int usedSlots(List<Candidacy> candidacies) {
        return (int) candidacies.stream().filter(Candidacy::occupiesSlot).count();
    }

    static List<Candidacy> parse(String html) {
        Document doc = Jsoup.parse(html);
        Map<String, Candidacy> byHash = new LinkedHashMap<>();

        for (Element card : doc.select("li.list-group-item")) {
            Element remove = card.selectFirst("a[data-postrequest=verwijderkandidatuur]");
            if (remove == null) {
                continue; // not a candidacy card
            }
            String uuid = remove.attr("data-uuid");
            Element formLink = card.selectFirst("a[href*=/bezichtigingsaanvraag/]");
            if (uuid.isEmpty() || formLink == null) {
                continue;
            }
            Matcher m = HASH.matcher(formLink.attr("href"));
            if (!m.find()) {
                continue;
            }

            byHash.put(
                    m.group(1),
                    new Candidacy(
                            uuid,
                            m.group(1),
                            statusOf(card, formLink),
                            isLost(card),
                            isSaturated(card),
                            label(remove)));
        }
        return new ArrayList<>(byHash.values());
    }

    /**
     * The home went to somebody else. The portal says so in prose on the card — "this property has
     * been let subject to another candidate" — and the ticket is then worth exactly nothing.
     */
    static boolean isLost(Element card) {
        String t = statusText(card);
        return t.contains("let subject to another candidate")
                || t.contains("verhuurd onder voorbehoud aan een andere kandidaat");
    }

    /**
     * The home stopped accepting viewings before our request got in: "there are currently no more
     * viewings possible… we recommend that you select another home". Not worthless — MVGM keeps the
     * interest on file and re-draws if its chosen candidate walks away — but a long shot, and the
     * first slot to give up when a home we can actually win turns up.
     */
    static boolean isSaturated(Element card) {
        String t = statusText(card);
        return t.contains("no more viewings possible") || t.contains("geen bezichtigingen meer mogelijk");
    }

    private static String statusText(Element card) {
        Element text = card.selectFirst("p.status-text");
        return text == null ? "" : text.text().toLowerCase();
    }

    /**
     * The card's status dot carries MVGM's step as a CSS class — {@code bg-status-aanvraag} and so
     * on — which reads the same whatever language the account is set to. An unrecognised step is
     * treated as ADVANCED, so a step we have never seen can never be mistaken for a withdrawable
     * pending request.
     *
     * <p>The portal omits the dot entirely on some cards — a saturated property is one — so a
     * missing dot must not be read as "advanced", or those slots would be held for ever. In that
     * case fall back to the markup: a submitted request offers a "change request" link that the
     * portal guards with a confirmation dialog, while a bare interest offers a plain one.
     */
    private static Candidacy.Status statusOf(Element card, Element formLink) {
        Element dot = card.selectFirst("span.status-dot[class*=bg-status-]");
        if (dot != null) {
            Matcher m = STATUS_DOT.matcher(dot.className());
            if (m.find()) {
                return Candidacy.Status.fromDot(m.group(1));
            }
        }
        return formLink.hasAttr("data-confirm")
                ? Candidacy.Status.REQUESTED
                : Candidacy.Status.INTERESTED;
    }

    /** The portal spells the address into the removal prompt, which is the tidiest place to read it. */
    private static String label(Element remove) {
        String msg = remove.attr("data-bericht");
        Matcher m = Pattern.compile("'([^']+)'").matcher(msg);
        return m.find() ? m.group(1).trim() : "";
    }
}

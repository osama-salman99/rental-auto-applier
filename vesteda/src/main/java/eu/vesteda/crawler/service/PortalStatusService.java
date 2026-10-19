package eu.vesteda.crawler.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

/**
 * Reads the portal's "My next home" page. This is the only trustworthy answer to two questions: how
 * many of the five viewing-request slots are spent, and did a submission actually land.
 *
 * <p>Everything structural is read from CSS classes, never from prose, because the account renders
 * in English today and the language is a user setting we don't control. The one exception is the
 * standby notice, which the portal only ever states in words — so that one is matched in both
 * languages.
 */
@Service
public class PortalStatusService {

    static final String MIJN_WONING_URL = PortalSession.BASE + "/mijnwoning/";

    private static final Pattern HASH = Pattern.compile("/(?:object|aanvraag)/([0-9a-f]{32})/");
    private static final Pattern STATUS_DOT = Pattern.compile("hoofdstatus-([a-z0-9-]+)");

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

        for (Element card : doc.select("div.card-woning")) {
            Element remove = card.selectFirst("[data-postrequest=verwijderkandidatuur]");
            if (remove == null) {
                continue; // not a candidacy card
            }
            String uuid = remove.attr("data-uuid");
            String hash = hashOf(card);
            if (uuid.isEmpty() || hash == null) {
                continue;
            }

            byHash.put(
                    hash,
                    new Candidacy(uuid, hash, statusOf(card), isStandby(card), label(card)));
        }
        return new ArrayList<>(byHash.values());
    }

    /** The card links to the object and to its request form; either carries the hash. */
    private static String hashOf(Element card) {
        for (Element a : card.select("a[href]")) {
            Matcher m = HASH.matcher(a.attr("href"));
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    /**
     * The home stopped accepting viewings before our request got in: "there are currently no more
     * viewings possible. However, your interest is registered." The portal says this only in prose,
     * so both languages are matched; a phrasing we don't recognise reads as "not standby", which is
     * the safe direction — it keeps a slot rather than silently downgrading a live request.
     */
    static boolean isStandby(Element card) {
        String t = card.text().toLowerCase();
        return t.contains("no more viewings possible")
                || t.contains("geen bezichtigingen meer mogelijk")
                || t.contains("standby candidate")
                || t.contains("wachtkandidaat");
    }

    /**
     * The status dot carries Vesteda's step as a CSS class — {@code hoofdstatus-aanvraag} and so on
     * — which reads the same whatever language the account is set to. A card with no dot at all is
     * treated as a bare interest rather than as advanced, so a parse miss can't strand a slot for
     * ever.
     */
    private static Candidacy.Status statusOf(Element card) {
        Element dot = card.selectFirst("span.status-indicator[class*=hoofdstatus-]");
        if (dot != null) {
            Matcher m = STATUS_DOT.matcher(dot.className());
            if (m.find()) {
                return Candidacy.Status.fromDot(m.group(1));
            }
        }
        return Candidacy.Status.INTERESTED;
    }

    /** The portal spells the address into the removal prompt, which is the tidiest place to read it. */
    private static String label(Element card) {
        Element title = card.selectFirst("h5.card-title");
        return title == null ? "" : title.text().trim();
    }
}

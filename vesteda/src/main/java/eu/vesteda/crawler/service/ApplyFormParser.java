package eu.vesteda.crawler.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.stereotype.Component;

/**
 * Turns the portal's viewing-request page into the exact payload a browser would submit.
 *
 * <p>The form arrives prefilled from the account profile, so the job is mostly to echo it back
 * faithfully. Two things make that non-trivial:
 *
 * <ul>
 *   <li>File inputs carry no value; a same-named hidden "marker" field says whether a document is
 *       on file ({@code a_uwv_verkl=1}) or not ({@code ""}).
 *   <li>Half the fields are conditional. The page ships a JSON map, {@code aCond}, from field name
 *       to the answers that make it apply — so we can tell a genuinely missing answer from one
 *       that simply doesn't apply (partner details when renting alone, say).
 * </ul>
 */
@Component
public class ApplyFormParser {

    private static final Logger log = LoggerFactory.getLogger(ApplyFormParser.class);

    private static final Pattern A_COND = Pattern.compile("var aCond\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL);
    private static final Pattern A_FILELIST =
            Pattern.compile("var aFilelist\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL);

    /**
     * Fields the submitter supplies, rather than the profile. The declaration tick ("I declare that
     * the information entered in this form is true and correct") is required and always arrives
     * unchecked — it is the applicant's act, not a stored answer — so it is not a gap in the
     * profile, and must not be reported as one. Treating it as missing would block every single
     * application, since the portal never prefills it.
     */
    private static final java.util.Set<String> SUPPLIED_AT_SUBMIT = java.util.Set.of("akkoord_a");

    private final ObjectMapper mapper = new ObjectMapper();

    public ApplyForm parse(String html) {
        Document doc = Jsoup.parse(html);
        Element form = doc.selectFirst("form#frmOptieform");
        if (form == null) {
            throw new PortalException("Viewing-request form not found on the page");
        }

        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, Boolean> required = new LinkedHashMap<>();
        List<String> requiredDocSlots = new ArrayList<>();

        for (Element input : form.select("input")) {
            String name = input.attr("name");
            if (name.isEmpty()) {
                continue;
            }
            String type = input.hasAttr("type") ? input.attr("type").toLowerCase() : "text";
            if ("file".equals(type)) {
                // The value lives on the hidden marker field of the same name, handled below.
                continue;
            }
            if ("radio".equals(type) || "checkbox".equals(type)) {
                if (input.hasAttr("checked")) {
                    fields.put(name, input.attr("value"));
                } else {
                    fields.putIfAbsent(name, "");
                }
            } else {
                fields.put(name, input.attr("value"));
            }
            if (isRequired(input)) {
                required.put(name, true);
            }
            // A document slot the portal insists on: its hidden marker is flagged hidden-require.
            if ("hidden".equals(type) && input.hasClass("hidden-require")) {
                requiredDocSlots.add(name);
            }
        }

        for (Element select : form.select("select")) {
            String name = select.attr("name");
            if (name.isEmpty()) {
                continue;
            }
            Element chosen = select.selectFirst("option[selected]");
            if (chosen == null) {
                chosen = select.selectFirst("option"); // browsers submit the first option by default
            }
            fields.put(name, chosen == null ? "" : chosen.attr("value"));
            if (isRequired(select)) {
                required.put(name, true);
            }
        }

        for (Element area : form.select("textarea")) {
            String name = area.attr("name");
            if (!name.isEmpty()) {
                fields.put(name, area.text().trim());
                if (isRequired(area)) {
                    required.put(name, true);
                }
            }
        }

        JsonNode conditions = extractJson(html, A_COND);
        JsonNode uploaded = extractJson(html, A_FILELIST);

        List<String> missingFields = new ArrayList<>();
        for (String name : required.keySet()) {
            if (SUPPLIED_AT_SUBMIT.contains(name)) {
                continue;
            }
            if (isApplicable(name, conditions, fields) && isBlank(fields.get(name))) {
                missingFields.add(name);
            }
        }

        List<String> missingDocs = new ArrayList<>();
        for (String slot : requiredDocSlots) {
            if (isApplicable(slot, conditions, fields) && isBlank(fields.get(slot))) {
                missingDocs.add(slot);
            }
        }

        Map<String, List<String>> uploadedDocs = new LinkedHashMap<>();
        if (uploaded != null) {
            uploaded.fields()
                    .forEachRemaining(
                            e -> {
                                List<String> names = new ArrayList<>();
                                e.getValue().forEach(n -> names.add(n.asText()));
                                uploadedDocs.put(e.getKey(), names);
                            });
        }

        return new ApplyForm(fields, missingFields, missingDocs, uploadedDocs);
    }

    /**
     * A field applies unless its condition in {@code aCond} is unmet. An empty condition (encoded as
     * {@code []}) means "always applies"; an object means "only when these other answers hold".
     */
    static boolean isApplicable(String name, JsonNode conditions, Map<String, String> answers) {
        if (conditions == null) {
            return true;
        }
        JsonNode cond = conditions.get(name);
        if (cond == null || cond.isArray()) {
            return true;
        }
        var it = cond.fields();
        while (it.hasNext()) {
            var entry = it.next();
            String actual = answers.getOrDefault(entry.getKey(), "");
            JsonNode expected = entry.getValue();
            if (expected.isArray()) {
                boolean any = false;
                for (JsonNode option : expected) {
                    if (option.asText().equals(actual)) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    return false;
                }
            } else if (!expected.asText().equals(actual)) {
                return false;
            }
        }
        return true;
    }

    private JsonNode extractJson(String html, Pattern pattern) {
        Matcher m = pattern.matcher(html);
        if (!m.find()) {
            return null;
        }
        try {
            return mapper.readTree(m.group(1));
        } catch (Exception e) {
            log.warn("Could not parse the form's inline JSON: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isRequired(Element el) {
        return "true".equals(el.attr("data-rule-required"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

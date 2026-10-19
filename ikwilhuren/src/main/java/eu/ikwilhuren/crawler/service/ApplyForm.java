package eu.ikwilhuren.crawler.service;

import java.util.List;
import java.util.Map;

/**
 * A parsed viewing-request form, already prefilled by the portal from the account profile.
 *
 * @param fields every field the browser would submit, name → value
 * @param missingFields required fields that are visible (per the form's own condition map) but
 *     empty — posting with any of these set is an application that cannot enter the draw
 * @param missingDocuments required document slots with no uploaded file
 * @param uploadedDocuments document slots that do have a file, for logging
 */
public record ApplyForm(
        Map<String, String> fields,
        List<String> missingFields,
        List<String> missingDocuments,
        Map<String, List<String>> uploadedDocuments) {

    public boolean isComplete() {
        return missingFields.isEmpty() && missingDocuments.isEmpty();
    }

    public String describeGaps() {
        StringBuilder sb = new StringBuilder();
        if (!missingFields.isEmpty()) {
            sb.append("missing answers: ").append(String.join(", ", missingFields));
        }
        if (!missingDocuments.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("missing documents: ").append(String.join(", ", missingDocuments));
        }
        return sb.toString();
    }
}

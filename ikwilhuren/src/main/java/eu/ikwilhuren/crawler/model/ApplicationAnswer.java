package eu.ikwilhuren.crawler.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One answer we put into a viewing request.
 *
 * <p>MVGM prefills the request form from the account profile, so most of it is already right. These
 * are the answers worth overriding from here — the ones that genuinely vary, or that the profile
 * leaves blank. {@code fieldName} is the portal's own form field, so an answer set here lands
 * exactly where MVGM expects it.
 *
 * <p>Note that submitting a request also writes these back to the account profile: MVGM treats the
 * form as the master copy. Changing an answer here therefore changes it for good, not just for one
 * application.
 */
@Entity
@Table(name = "application_answers")
public class ApplicationAnswer {

    /** The portal's form field, e.g. {@code a_parkeerplaats}. */
    @Id
    @Column(length = 64)
    private String fieldName;

    /** What we send. Empty means "leave whatever the portal prefilled". */
    @Column(name = "answer_value", length = 500) // "value" is reserved in H2
    private String value;

    public ApplicationAnswer() {}

    public ApplicationAnswer(String fieldName, String value) {
        this.fieldName = fieldName;
        this.value = value;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public boolean isSet() {
        return value != null && !value.isBlank();
    }
}

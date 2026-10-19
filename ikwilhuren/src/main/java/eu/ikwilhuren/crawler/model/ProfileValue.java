package eu.ikwilhuren.crawler.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * What MVGM's own form said the last time we opened it — i.e. what your ikwilhuren.nu profile
 * currently answers.
 *
 * <p>Kept so the settings page can tell you what "keep my profile's answer" actually means. Without
 * it, leaving a field blank is a leap of faith: you are agreeing to send an answer you cannot see.
 *
 * <p>Captured as a side effect of applying, because the request form is the only place MVGM shows
 * these values, and opening that form registers interest on the account — so it is not something we
 * may go and fetch just to populate a settings page.
 */
@Entity
@Table(name = "profile_values")
public class ProfileValue {

    /** The portal's form field, e.g. {@code a_werksituatie}. */
    @Id
    @Column(length = 64)
    private String fieldName;

    @Column(name = "profile_value", length = 500)
    private String value;

    private Instant seenAt;

    public ProfileValue() {}

    public ProfileValue(String fieldName, String value) {
        this.fieldName = fieldName;
        this.value = value;
        this.seenAt = Instant.now();
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

    public Instant getSeenAt() {
        return seenAt;
    }

    public void setSeenAt(Instant seenAt) {
        this.seenAt = seenAt;
    }
}

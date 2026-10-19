package eu.roofz.crawler.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Singleton (id = 1) holding the applicant's static full-application questionnaire as raw JSON —
 * the {@code questionnaire} object the ReletPortal full application expects (personalDetails,
 * address, idDocument, workSituation, employment, financialSituation, housing/partner fields).
 *
 * <p>Seeded on first boot from the file named by {@code ROOFZ_APPLICANT_PROFILE}, if one is set,
 * and editable on the Documents page. It changes rarely (ID expiry, employer, salary, IBAN). The
 * pipeline overlays this onto whatever the portal already pre-filled before saving the full
 * application.
 */
@Entity
@Table(name = "applicant_profile")
public class ApplicantProfile {

    @Id private Long id = 1L;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String questionnaireJson;

    private Instant updatedAt = Instant.now();

    public ApplicantProfile() {}

    public ApplicantProfile(String questionnaireJson) {
        this.questionnaireJson = questionnaireJson;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getQuestionnaireJson() {
        return questionnaireJson;
    }

    public void setQuestionnaireJson(String questionnaireJson) {
        this.questionnaireJson = questionnaireJson;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

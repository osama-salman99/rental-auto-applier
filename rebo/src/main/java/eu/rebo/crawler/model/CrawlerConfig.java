package eu.rebo.crawler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "crawler_config")
public class CrawlerConfig {

    @Id private Long id = 1L;

    private Integer minSquareMeters;
    private Integer minBedrooms;
    private Integer maxPrice;
    private String targetCity;
    private boolean onlyAvailable = true;
    // No default: ntfy.sh topics are public, so a name written here would let anyone read the alerts.
    // Set on the settings page; until then no alerts are sent.
    private String ntfyTopic;
    private String ntfyServerUrl = "https://ntfy.sh";
    private boolean notificationsEnabled = true;

    // --- Auto-apply (Interesse registration on the CMS lead form) ---
    // New booleans need @ColumnDefault: Hibernate maps a primitive boolean to NOT NULL, and adding a
    // NOT NULL column to the already-populated config row fails without a DEFAULT in the DDL.
    @ColumnDefault("false")
    private boolean autoApplyEnabled = false;

    /** A dry run scores and logs but performs no portal or DB write. Defaults ON so a fresh deploy is inert. */
    @ColumnDefault("true")
    private boolean autoApplyDryRun = true;

    /** Gross monthly income for the 4×-rent affordability gate. Null → fail closed (register on nothing). */
    private Integer incomeMonthlyGross;

    /** REBO income category submitted on the form: Employer|Entrepreneur|Equity|Pension|SocialBenefit. */
    private String incomeType = "Employer";

    private String applicantFirstName;
    private String applicantLastName;
    private String applicantEmail;
    private String applicantPhone;

    /**
     * When auto-apply first became enabled. Only listings first seen at or after this instant are
     * eligible, so arming never fires on the pre-existing backlog — only on genuinely new listings.
     */
    private java.time.Instant autoApplyArmedAt;

    public CrawlerConfig() {}

    public static CrawlerConfig defaults() {
        return new CrawlerConfig();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getMinSquareMeters() { return minSquareMeters; }
    public void setMinSquareMeters(Integer minSquareMeters) { this.minSquareMeters = minSquareMeters; }

    public Integer getMinBedrooms() { return minBedrooms; }
    public void setMinBedrooms(Integer minBedrooms) { this.minBedrooms = minBedrooms; }

    public Integer getMaxPrice() { return maxPrice; }
    public void setMaxPrice(Integer maxPrice) { this.maxPrice = maxPrice; }

    public String getTargetCity() { return targetCity; }
    public void setTargetCity(String targetCity) { this.targetCity = targetCity; }

    public boolean isOnlyAvailable() { return onlyAvailable; }
    public void setOnlyAvailable(boolean onlyAvailable) { this.onlyAvailable = onlyAvailable; }

    public String getNtfyTopic() { return ntfyTopic; }
    public void setNtfyTopic(String ntfyTopic) { this.ntfyTopic = ntfyTopic; }

    public String getNtfyServerUrl() { return ntfyServerUrl; }
    public void setNtfyServerUrl(String ntfyServerUrl) { this.ntfyServerUrl = ntfyServerUrl; }

    public boolean isNotificationsEnabled() { return notificationsEnabled; }
    public void setNotificationsEnabled(boolean notificationsEnabled) { this.notificationsEnabled = notificationsEnabled; }

    public boolean isAutoApplyEnabled() { return autoApplyEnabled; }
    public void setAutoApplyEnabled(boolean autoApplyEnabled) { this.autoApplyEnabled = autoApplyEnabled; }

    public boolean isAutoApplyDryRun() { return autoApplyDryRun; }
    public void setAutoApplyDryRun(boolean autoApplyDryRun) { this.autoApplyDryRun = autoApplyDryRun; }

    public Integer getIncomeMonthlyGross() { return incomeMonthlyGross; }
    public void setIncomeMonthlyGross(Integer incomeMonthlyGross) { this.incomeMonthlyGross = incomeMonthlyGross; }

    public String getIncomeType() { return incomeType; }
    public void setIncomeType(String incomeType) { this.incomeType = incomeType; }

    public String getApplicantFirstName() { return applicantFirstName; }
    public void setApplicantFirstName(String applicantFirstName) { this.applicantFirstName = applicantFirstName; }

    public String getApplicantLastName() { return applicantLastName; }
    public void setApplicantLastName(String applicantLastName) { this.applicantLastName = applicantLastName; }

    public String getApplicantEmail() { return applicantEmail; }
    public void setApplicantEmail(String applicantEmail) { this.applicantEmail = applicantEmail; }

    public String getApplicantPhone() { return applicantPhone; }
    public void setApplicantPhone(String applicantPhone) { this.applicantPhone = applicantPhone; }

    public java.time.Instant getAutoApplyArmedAt() { return autoApplyArmedAt; }
    public void setAutoApplyArmedAt(java.time.Instant autoApplyArmedAt) { this.autoApplyArmedAt = autoApplyArmedAt; }

    /**
     * The affordability ceiling: the most gross rent (base + service costs) we may register interest
     * on, given REBO's rule that gross monthly income must be at least 4× gross rent (3.5× for
     * pensioners). Absent income → empty → the caller must fail closed.
     */
    public java.util.OptionalDouble maxGrossRent() {
        if (incomeMonthlyGross == null || incomeMonthlyGross <= 0) {
            return java.util.OptionalDouble.empty();
        }
        double multiplier = "Pension".equalsIgnoreCase(incomeType) ? 3.5 : 4.0;
        return java.util.OptionalDouble.of(incomeMonthlyGross / multiplier);
    }
}

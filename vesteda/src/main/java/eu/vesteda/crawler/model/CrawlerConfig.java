package eu.vesteda.crawler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "crawler_config")
public class CrawlerConfig {

    @Id private Long id = 1L;

    private Integer minSquareMeters = 48;
    private Integer minBedrooms;
    private Integer maxPrice;
    private String targetCity = "Amsterdam";
    private boolean onlyAvailable = true;
    // No default: ntfy.sh topics are public, so a name written here would let anyone read the alerts.
    // Set on the settings page; until then no alerts are sent.
    private String ntfyTopic;
    private String ntfyServerUrl = "https://ntfy.sh";
    private boolean notificationsEnabled = true;

    // ---- auto-apply ----
    //
    // The live database already holds a config row, and ddl-auto=update adds new columns to it as
    // NULL. A NULL will not map back into a primitive boolean, so each of these needs a default at
    // the column level as well as in the field initialiser — the field default only ever applies to
    // rows this code creates, and the one row that matters was created long before it.

    /** Ships off. Nothing is submitted to the real portal until this is turned on deliberately. */
    @ColumnDefault("false")
    private boolean autoApplyEnabled = false;

    /** And even then, the first pass only logs the payload it would have posted. */
    @ColumnDefault("true")
    private boolean autoApplyDryRun = true;

    /** Whether a request may be traded in when a better home appears. */
    @ColumnDefault("true")
    private boolean autoWithdrawEnabled = true;

    /** Vesteda: "You may submit a maximum of five simultaneous requests for a viewing." */
    private Integer maxActiveRequests = 5;

    /**
     * Gross monthly income, in euros. No default: with no income set there is no rent ceiling, and
     * the eligibility gate fails closed rather than guess — guessing wrong costs one of five slots.
     */
    private Integer grossMonthlyIncome;

    /** Savings, in euros. Vesteda: "divide this amount by 120 and add it to your gross income." */
    private Integer savings;

    /**
     * How far above the ceiling we will still apply, in euros. Zero by default — the honest reading
     * of the income rule. Vesteda's own FAQ says a rent a little over budget is still worth a shot
     * and only becomes pointless "€100 and above" over, so this is the lever for that, capped below
     * the point where they say not to bother.
     */
    private Integer rentToleranceEuros = 0;

    // ---- who is applying ----
    //
    // Vesteda gives some homes to some people first. A home flagged for key professions goes to
    // teachers/healthcare/police ahead of the general queue; one flagged for social-housing leavers
    // goes to them first. Whether that priority helps us or hurts us depends entirely on who the
    // applicant is — so it is configured here, not assumed. Off means "general applicant", for whom
    // those homes are harder to win, and the score marks them down accordingly.

    /** The applicant works a key profession (Sleutelberoep), so key-profession homes favour them. */
    @ColumnDefault("false")
    private boolean applicantKeyProfession = false;

    /** The applicant is leaving a social rental, so homes with that priority favour them. */
    @ColumnDefault("false")
    private boolean applicantSocialHousingPriority = false;

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

    public boolean isAutoWithdrawEnabled() { return autoWithdrawEnabled; }
    public void setAutoWithdrawEnabled(boolean autoWithdrawEnabled) { this.autoWithdrawEnabled = autoWithdrawEnabled; }

    public Integer getMaxActiveRequests() { return maxActiveRequests; }
    public void setMaxActiveRequests(Integer maxActiveRequests) { this.maxActiveRequests = maxActiveRequests; }

    public Integer getGrossMonthlyIncome() { return grossMonthlyIncome; }
    public void setGrossMonthlyIncome(Integer grossMonthlyIncome) { this.grossMonthlyIncome = grossMonthlyIncome; }

    public Integer getSavings() { return savings; }
    public void setSavings(Integer savings) { this.savings = savings; }

    public Integer getRentToleranceEuros() { return rentToleranceEuros; }
    public void setRentToleranceEuros(Integer rentToleranceEuros) { this.rentToleranceEuros = rentToleranceEuros; }

    public boolean isApplicantKeyProfession() { return applicantKeyProfession; }
    public void setApplicantKeyProfession(boolean applicantKeyProfession) { this.applicantKeyProfession = applicantKeyProfession; }

    public boolean isApplicantSocialHousingPriority() { return applicantSocialHousingPriority; }
    public void setApplicantSocialHousingPriority(boolean applicantSocialHousingPriority) { this.applicantSocialHousingPriority = applicantSocialHousingPriority; }

    /**
     * Vesteda's income rule, stated on every property page: a single earner's gross monthly income
     * must be at least 3.5× the rent — so the most we may pay is income ÷ 3.5. Savings count, at
     * their own stated rate of one 120th per month.
     *
     * <p>Checked against the figures they print themselves: a €1.945 home asks for €6.807 (3.5×),
     * and €7.780 for two earners (4×). We only ever apply as a single earner, so 3.5 is the divisor.
     *
     * <p>Null when income is unset, which the eligibility gate reads as "apply to nothing".
     */
    public Integer getRentCeiling() {
        if (grossMonthlyIncome == null || grossMonthlyIncome <= 0) {
            return null;
        }
        int effective = grossMonthlyIncome + (savings == null ? 0 : savings / 120);
        int tolerance = rentToleranceEuros == null ? 0 : Math.min(99, Math.max(0, rentToleranceEuros));
        return (int) Math.floor(effective / 3.5) + tolerance;
    }

    /** The ceiling before any tolerance — what Vesteda would actually sign off on. */
    public Integer getStrictRentCeiling() {
        if (grossMonthlyIncome == null || grossMonthlyIncome <= 0) {
            return null;
        }
        int effective = grossMonthlyIncome + (savings == null ? 0 : savings / 120);
        return (int) Math.floor(effective / 3.5);
    }
}

package eu.ikwilhuren.crawler.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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

    /** Ships off, and dry-run, so the first live payload can be eyeballed before anything is sent. */
    private Boolean autoApplyEnabled = Boolean.FALSE;

    private Boolean autoApplyDryRun = Boolean.TRUE;

    /**
     * MVGM allocates by lottery and caps you at five open viewing requests. A request on a property
     * that has already been let is a dead slot — nobody tells you that you lost — so we withdraw it
     * and put the slot back to work.
     */
    private Boolean autoWithdrawEnabled = Boolean.TRUE;

    private Integer maxActiveRequests = 5;

    /**
     * MVGM caps viewing requests per property and warns, on the property page, once a home "has
     * many viewing requests". Every listing we applied to carrying that warning came back within
     * minutes as "no more viewings possible" — five slots, five dead tickets. So an oversubscribed
     * property is skipped: it cannot be won, and the slot is better kept ready for a fresh listing.
     */
    private Boolean skipCrowdedListings = Boolean.TRUE;

    /**
     * The income test: gross monthly income must cover {@code incomeMultiplier} times the annual
     * bare rent. Applying above the resulting ceiling cannot succeed at any speed, and burns one of
     * the five slots, so it is a hard gate rather than a preference.
     *
     * <p>Personal, so there is no built-in value: it is set on the settings page, or seeded at
     * startup from {@code IKWILHUREN_SEED_INCOME}. Until then nothing is applied to.
     */
    private Integer grossMonthlyIncome;

    private Integer incomeMultiplier = 42;

    /** The one answer the account profile doesn't already hold: Ja / Nee / WeetIkNogNiet. */
    private String parkingPreference = "WeetIkNogNiet";

    @Column(length = 450)
    private String applicationNote;

    public CrawlerConfig() {}

    /**
     * Highest rent the income test allows, or null when income isn't configured. At 42x, €4.900 a
     * month works out to €1.400.
     */
    public Integer getRentCeiling() {
        if (grossMonthlyIncome == null || incomeMultiplier == null || incomeMultiplier <= 0) {
            return null;
        }
        return grossMonthlyIncome * 12 / incomeMultiplier;
    }

    public static CrawlerConfig defaults() {
        return new CrawlerConfig();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getMinSquareMeters() {
        return minSquareMeters;
    }

    public void setMinSquareMeters(Integer minSquareMeters) {
        this.minSquareMeters = minSquareMeters;
    }

    public Integer getMinBedrooms() {
        return minBedrooms;
    }

    public void setMinBedrooms(Integer minBedrooms) {
        this.minBedrooms = minBedrooms;
    }

    public Integer getMaxPrice() {
        return maxPrice;
    }

    public void setMaxPrice(Integer maxPrice) {
        this.maxPrice = maxPrice;
    }

    public String getTargetCity() {
        return targetCity;
    }

    public void setTargetCity(String targetCity) {
        this.targetCity = targetCity;
    }

    /**
     * The cities we search, parsed from the comma-separated {@link #targetCity} field so one text box
     * can hold several ("Amsterdam, Diemen, Amstelveen"). An empty or blank field means no city
     * restriction at all. City districts (e.g. Amsterdam Nieuw-West) list under their municipality,
     * so "Amsterdam" already covers them.
     */
    public java.util.List<String> getTargetCities() {
        if (targetCity == null || targetCity.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(targetCity.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** True when a listing's city is one we search (case-insensitive). No targets set = match all. */
    public boolean matchesCity(String city) {
        java.util.List<String> cities = getTargetCities();
        if (cities.isEmpty()) {
            return true;
        }
        if (city == null) {
            return false;
        }
        return cities.stream().anyMatch(c -> c.equalsIgnoreCase(city.trim()));
    }

    public boolean isOnlyAvailable() {
        return onlyAvailable;
    }

    public void setOnlyAvailable(boolean onlyAvailable) {
        this.onlyAvailable = onlyAvailable;
    }

    public String getNtfyTopic() {
        return ntfyTopic;
    }

    public void setNtfyTopic(String ntfyTopic) {
        this.ntfyTopic = ntfyTopic;
    }

    public String getNtfyServerUrl() {
        return ntfyServerUrl;
    }

    public void setNtfyServerUrl(String ntfyServerUrl) {
        this.ntfyServerUrl = ntfyServerUrl;
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    public void setNotificationsEnabled(boolean notificationsEnabled) {
        this.notificationsEnabled = notificationsEnabled;
    }

    public Boolean getAutoApplyEnabled() {
        return autoApplyEnabled;
    }

    public void setAutoApplyEnabled(Boolean autoApplyEnabled) {
        this.autoApplyEnabled = autoApplyEnabled;
    }

    public boolean isAutoApplyEnabled() {
        return Boolean.TRUE.equals(autoApplyEnabled);
    }

    public Boolean getAutoApplyDryRun() {
        return autoApplyDryRun;
    }

    public void setAutoApplyDryRun(Boolean autoApplyDryRun) {
        this.autoApplyDryRun = autoApplyDryRun;
    }

    public boolean isAutoApplyDryRun() {
        return !Boolean.FALSE.equals(autoApplyDryRun);
    }

    public Boolean getAutoWithdrawEnabled() {
        return autoWithdrawEnabled;
    }

    public void setAutoWithdrawEnabled(Boolean autoWithdrawEnabled) {
        this.autoWithdrawEnabled = autoWithdrawEnabled;
    }

    public boolean isAutoWithdrawEnabled() {
        return Boolean.TRUE.equals(autoWithdrawEnabled);
    }

    public Integer getMaxActiveRequests() {
        return maxActiveRequests;
    }

    public void setMaxActiveRequests(Integer maxActiveRequests) {
        this.maxActiveRequests = maxActiveRequests;
    }

    public Boolean getSkipCrowdedListings() {
        return skipCrowdedListings;
    }

    public void setSkipCrowdedListings(Boolean skipCrowdedListings) {
        this.skipCrowdedListings = skipCrowdedListings;
    }

    public boolean isSkipCrowdedListings() {
        return !Boolean.FALSE.equals(skipCrowdedListings);
    }

    public Integer getGrossMonthlyIncome() {
        return grossMonthlyIncome;
    }

    public void setGrossMonthlyIncome(Integer grossMonthlyIncome) {
        this.grossMonthlyIncome = grossMonthlyIncome;
    }

    public Integer getIncomeMultiplier() {
        return incomeMultiplier;
    }

    public void setIncomeMultiplier(Integer incomeMultiplier) {
        this.incomeMultiplier = incomeMultiplier;
    }

    public String getParkingPreference() {
        return parkingPreference;
    }

    public void setParkingPreference(String parkingPreference) {
        this.parkingPreference = parkingPreference;
    }

    public String getApplicationNote() {
        return applicationNote;
    }

    public void setApplicationNote(String applicationNote) {
        this.applicationNote = applicationNote;
    }
}

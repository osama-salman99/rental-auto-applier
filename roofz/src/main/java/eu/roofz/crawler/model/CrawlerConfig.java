package eu.roofz.crawler.model;

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
    private String ntfyTopic;
    private String ntfyServerUrl = "https://ntfy.sh";
    private boolean notificationsEnabled = true;

    private Integer pollIntervalSeconds = 10;

    // Off until switched on in the settings: applying sends the applicant's details to roofz.eu.
    private Boolean autoApplyEnabled = Boolean.FALSE;
    // The applicant's details for roofz.eu's apply form. Personal, so none are built in: they are
    // set on the settings page, and nothing is applied to while any of them is missing.
    private String applicantFirstName;
    private String applicantLastName;
    private String applicantEmail;
    private String applicantPhone;
    // roofz's apply form caps the message at 1000 chars; the default String column is VARCHAR(255),
    // which rejects a normal cover message. Match the form's limit. (Existing column widened by ALTER.)
    @Column(length = 1000)
    private String applicantMessage;

    // ReletPortal (roofz.onosre.com) application pipeline.
    private Boolean portalPipelineEnabled = Boolean.TRUE;
    // Dry-run: build & log pre-application / full-application payloads but never POST/PUT them.
    // Defaults to true so the first real invitation is validated before we submit for real.
    private Boolean portalDryRun = Boolean.TRUE;
    // Watchdog: minutes after a roofz.eu auto-apply to still expect a pre-application invite before
    // escalating ("applied but no invite"). The invite email was observed ~40 min after applying, so
    // 90 gives comfortable margin against a premature alarm.
    private Integer inviteWatchdogMinutes = 90;
    // Unified SLA (SECONDS) for the three "should happen within ~1 minute" transitions:
    //   applied → pre-application invite email,
    //   pre-application submitted → pre-application confirmation email,
    //   full application submitted → full application confirmation email.
    // roofz sends each within ~1 min and the email-forward chain is quick, so the default is 60s;
    // can be lowered (e.g. 30) for tighter alarms. Detection resolution is bounded by pollIntervalSeconds.
    // ColumnDefault backfills the existing config row to 60 when the column is added (so the UI shows it).
    @org.hibernate.annotations.ColumnDefault("60")
    private Integer stageSlaSeconds = 60;
    // Month (YYYY-MM) the "upload this month's documents" reminder last fired, so it fires once/month.
    private String lastDocumentReminderMonth;
    // One-time marker: true once the pre-existing applied-listing backlog has been marked as already
    // alerted, so deploying the pipeline doesn't fire a storm of "applied but no invite" alerts.
    private Boolean watchdogBacklogSuppressed = Boolean.FALSE;
    // Full application: after auto-filling the questionnaire, also upload the stored applicant
    // documents. Off by default so the first real full application is validated before going live.
    private Boolean portalAutoUploadDocuments = Boolean.FALSE;
    // Full application: after filling + uploading documents, submit (finalize) the application. This
    // is irreversible, so it is off by default and flipped on only once the flow is validated live.
    private Boolean portalAutoFinalize = Boolean.FALSE;
    // Full application: as soon as the pre-application completes, save the full questionnaire early —
    // before the landlord "opens" the full application. The portal accepts this save (verified live),
    // so only document upload + finalize remain the instant it opens, keeping us first. On by default;
    // ColumnDefault backfills the existing config row to true when the column is added. Document upload
    // and finalize are deliberately NOT attempted early (unproven before the application is opened).
    @org.hibernate.annotations.ColumnDefault("true")
    private boolean portalEagerPrefill = true;
    // How many of the most recent months of dated documents (payslips, bank statements) to attach.
    private Integer documentMonthsWindow = 3;

    public CrawlerConfig() {}

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

    public Integer getPollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    public void setPollIntervalSeconds(Integer pollIntervalSeconds) {
        this.pollIntervalSeconds = pollIntervalSeconds;
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

    public String getApplicantFirstName() {
        return applicantFirstName;
    }

    public void setApplicantFirstName(String applicantFirstName) {
        this.applicantFirstName = applicantFirstName;
    }

    public String getApplicantLastName() {
        return applicantLastName;
    }

    public void setApplicantLastName(String applicantLastName) {
        this.applicantLastName = applicantLastName;
    }

    public String getApplicantEmail() {
        return applicantEmail;
    }

    public void setApplicantEmail(String applicantEmail) {
        this.applicantEmail = applicantEmail;
    }

    public String getApplicantPhone() {
        return applicantPhone;
    }

    public void setApplicantPhone(String applicantPhone) {
        this.applicantPhone = applicantPhone;
    }

    public String getApplicantMessage() {
        return applicantMessage;
    }

    public void setApplicantMessage(String applicantMessage) {
        this.applicantMessage = applicantMessage;
    }

    public Boolean getPortalPipelineEnabled() {
        return portalPipelineEnabled;
    }

    public void setPortalPipelineEnabled(Boolean portalPipelineEnabled) {
        this.portalPipelineEnabled = portalPipelineEnabled;
    }

    public boolean isPortalPipelineEnabled() {
        return Boolean.TRUE.equals(portalPipelineEnabled);
    }

    public Boolean getPortalDryRun() {
        return portalDryRun;
    }

    public void setPortalDryRun(Boolean portalDryRun) {
        this.portalDryRun = portalDryRun;
    }

    public boolean isPortalDryRun() {
        return Boolean.TRUE.equals(portalDryRun);
    }

    public Integer getInviteWatchdogMinutes() {
        return inviteWatchdogMinutes;
    }

    public void setInviteWatchdogMinutes(Integer inviteWatchdogMinutes) {
        this.inviteWatchdogMinutes = inviteWatchdogMinutes;
    }

    public Integer getStageSlaSeconds() {
        return stageSlaSeconds;
    }

    public void setStageSlaSeconds(Integer stageSlaSeconds) {
        this.stageSlaSeconds = stageSlaSeconds;
    }

    public int stageSlaSecondsOrDefault() {
        return stageSlaSeconds == null || stageSlaSeconds < 1 ? 60 : stageSlaSeconds;
    }

    public String getLastDocumentReminderMonth() {
        return lastDocumentReminderMonth;
    }

    public void setLastDocumentReminderMonth(String lastDocumentReminderMonth) {
        this.lastDocumentReminderMonth = lastDocumentReminderMonth;
    }

    public Boolean getWatchdogBacklogSuppressed() {
        return watchdogBacklogSuppressed;
    }

    public void setWatchdogBacklogSuppressed(Boolean watchdogBacklogSuppressed) {
        this.watchdogBacklogSuppressed = watchdogBacklogSuppressed;
    }

    public boolean isWatchdogBacklogSuppressed() {
        return Boolean.TRUE.equals(watchdogBacklogSuppressed);
    }

    public Boolean getPortalAutoUploadDocuments() {
        return portalAutoUploadDocuments;
    }

    public void setPortalAutoUploadDocuments(Boolean portalAutoUploadDocuments) {
        this.portalAutoUploadDocuments = portalAutoUploadDocuments;
    }

    public boolean isPortalAutoUploadDocuments() {
        return Boolean.TRUE.equals(portalAutoUploadDocuments);
    }

    public Boolean getPortalAutoFinalize() {
        return portalAutoFinalize;
    }

    public void setPortalAutoFinalize(Boolean portalAutoFinalize) {
        this.portalAutoFinalize = portalAutoFinalize;
    }

    public boolean isPortalAutoFinalize() {
        return Boolean.TRUE.equals(portalAutoFinalize);
    }

    public boolean isPortalEagerPrefill() {
        return portalEagerPrefill;
    }

    public void setPortalEagerPrefill(boolean portalEagerPrefill) {
        this.portalEagerPrefill = portalEagerPrefill;
    }

    public Integer getDocumentMonthsWindow() {
        return documentMonthsWindow;
    }

    public void setDocumentMonthsWindow(Integer documentMonthsWindow) {
        this.documentMonthsWindow = documentMonthsWindow;
    }

    public int documentMonthsWindowOrDefault() {
        return documentMonthsWindow == null || documentMonthsWindow < 1 ? 3 : documentMonthsWindow;
    }
}

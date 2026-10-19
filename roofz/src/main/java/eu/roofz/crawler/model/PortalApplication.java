package eu.roofz.crawler.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One ReletPortal application tracked through its lifecycle. Rows originate either from a
 * pre-application invitation link scraped out of an email ({@link #invitationId}/{@link #magicToken}
 * populated, {@link #sourceEmailMessageId} set) or — as a fallback — from an application discovered
 * directly in the account's application list ({@link #applicationId} set, no invitation).
 */
@Entity
@Table(name = "portal_applications")
public class PortalApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Invitation id from the email link {@code /invitation/{id}/token/{magicToken}}; unique. */
    @Column(unique = true)
    private String invitationId;

    private String magicToken;

    /** ReletPortal application UUID, known once the pre-application is submitted (or discovered). */
    @Column(unique = true)
    private String applicationId;

    /** Message-id of the email the invitation came from; unique so an email is only processed once. */
    @Column(unique = true)
    private String sourceEmailMessageId;

    /** Free-text residence address for display / listing correlation. */
    private String residenceAddress;

    /** Best-effort link back to the crawler {@link Listing} this application is for. */
    private Long listingId;

    @Enumerated(EnumType.STRING)
    private PortalApplicationState state = PortalApplicationState.DISCOVERED;

    /** Last portal status string observed (e.g. {@code awaiting_full_application}). */
    private String portalStatus;

    @Column(length = 1000)
    private String lastError;

    private int retryCount;

    /** True once we've fired the "upload documents + submit" notification/call, so it fires once. */
    private boolean fullReadyNotified;

    /** True once we've logged/notified the dry-run pre-application payload, so it fires once. */
    private boolean dryRunNotified;

    /**
     * True once we've eagerly saved the full-application questionnaire while the portal still reported
     * {@code pre_application_completed} (before the landlord opened the full application). Keeps the
     * early pre-fill to a single PUT per application.
     */
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean fullPrefilled;

    /**
     * Newline-separated {@code docType fileName} keys we have already successfully uploaded to the
     * portal for this application. This is our own upload ledger: the portal stores an attachment's
     * {@code fileName} as a mangled CDN URL that need not match the name we sent, so a retry after a
     * partial upload can't reliably tell from the portal's list which of our documents already landed.
     * Deduping against this ledger (which we control) makes re-upload idempotent regardless of the
     * portal's naming, preventing duplicate attachments on a real application. Nullable — pre-existing
     * rows read as "nothing recorded yet" and behave exactly as before.
     */
    @Column(length = 2000)
    private String uploadedDocKeys;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
    private Instant preAppSubmittedAt;
    private Instant fullFilledAt;
    private Instant finalizedAt;

    /** When the "Confirmation of your pre-application" email arrived (matched by address). */
    private Instant preAppConfirmedAt;

    /** When the "Confirmation of your (full) application" email arrived (matched by address). */
    private Instant fullAppConfirmedAt;

    /** When the offer/allocation email ("… has been allocated to you") arrived (matched by address). */
    private Instant offeredAt;

    /** Resolved "view & respond to the offer" portal link from the allocation email, if we got one. */
    private String offerLink;

    /** True once the (loud) offer-received ntfy + Twilio call has fired, so it fires once. */
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean offerNotified;

    /** True once the "pre-app submitted but no confirmation email in the SLA" alert has fired. */
    // ColumnDefault lets Hibernate ADD this NOT NULL column to the already-populated table (H2 needs a
    // default to backfill existing rows) — without it the ALTER fails and the column is never created.
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean preAppConfirmWatchdogAlerted;

    /** True once the "full-app submitted but no confirmation email in the SLA" alert has fired. */
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean fullAppConfirmWatchdogAlerted;

    public PortalApplication() {}

    public static PortalApplication fromInvitation(
            String invitationId, String magicToken, String sourceEmailMessageId) {
        PortalApplication p = new PortalApplication();
        p.invitationId = invitationId;
        p.magicToken = magicToken;
        p.sourceEmailMessageId = sourceEmailMessageId;
        p.state = PortalApplicationState.DISCOVERED;
        return p;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getInvitationId() {
        return invitationId;
    }

    public void setInvitationId(String invitationId) {
        this.invitationId = invitationId;
    }

    public String getMagicToken() {
        return magicToken;
    }

    public void setMagicToken(String magicToken) {
        this.magicToken = magicToken;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(String applicationId) {
        this.applicationId = applicationId;
    }

    public String getSourceEmailMessageId() {
        return sourceEmailMessageId;
    }

    public void setSourceEmailMessageId(String sourceEmailMessageId) {
        this.sourceEmailMessageId = sourceEmailMessageId;
    }

    public String getResidenceAddress() {
        return residenceAddress;
    }

    public void setResidenceAddress(String residenceAddress) {
        this.residenceAddress = residenceAddress;
    }

    public Long getListingId() {
        return listingId;
    }

    public void setListingId(Long listingId) {
        this.listingId = listingId;
    }

    public PortalApplicationState getState() {
        return state;
    }

    public void setState(PortalApplicationState state) {
        this.state = state;
    }

    public String getPortalStatus() {
        return portalStatus;
    }

    public void setPortalStatus(String portalStatus) {
        this.portalStatus = portalStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public boolean isFullReadyNotified() {
        return fullReadyNotified;
    }

    public void setFullReadyNotified(boolean fullReadyNotified) {
        this.fullReadyNotified = fullReadyNotified;
    }

    public boolean isDryRunNotified() {
        return dryRunNotified;
    }

    public void setDryRunNotified(boolean dryRunNotified) {
        this.dryRunNotified = dryRunNotified;
    }

    public boolean isFullPrefilled() {
        return fullPrefilled;
    }

    public void setFullPrefilled(boolean fullPrefilled) {
        this.fullPrefilled = fullPrefilled;
    }

    public String getUploadedDocKeys() {
        return uploadedDocKeys;
    }

    public void setUploadedDocKeys(String uploadedDocKeys) {
        this.uploadedDocKeys = uploadedDocKeys;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getPreAppSubmittedAt() {
        return preAppSubmittedAt;
    }

    public void setPreAppSubmittedAt(Instant preAppSubmittedAt) {
        this.preAppSubmittedAt = preAppSubmittedAt;
    }

    public Instant getFullFilledAt() {
        return fullFilledAt;
    }

    public void setFullFilledAt(Instant fullFilledAt) {
        this.fullFilledAt = fullFilledAt;
    }

    public Instant getFinalizedAt() {
        return finalizedAt;
    }

    public void setFinalizedAt(Instant finalizedAt) {
        this.finalizedAt = finalizedAt;
    }

    public Instant getPreAppConfirmedAt() {
        return preAppConfirmedAt;
    }

    public void setPreAppConfirmedAt(Instant preAppConfirmedAt) {
        this.preAppConfirmedAt = preAppConfirmedAt;
    }

    public Instant getFullAppConfirmedAt() {
        return fullAppConfirmedAt;
    }

    public void setFullAppConfirmedAt(Instant fullAppConfirmedAt) {
        this.fullAppConfirmedAt = fullAppConfirmedAt;
    }

    public Instant getOfferedAt() {
        return offeredAt;
    }

    public void setOfferedAt(Instant offeredAt) {
        this.offeredAt = offeredAt;
    }

    public String getOfferLink() {
        return offerLink;
    }

    public void setOfferLink(String offerLink) {
        this.offerLink = offerLink;
    }

    public boolean isOfferNotified() {
        return offerNotified;
    }

    public void setOfferNotified(boolean offerNotified) {
        this.offerNotified = offerNotified;
    }

    public boolean isPreAppConfirmWatchdogAlerted() {
        return preAppConfirmWatchdogAlerted;
    }

    public void setPreAppConfirmWatchdogAlerted(boolean preAppConfirmWatchdogAlerted) {
        this.preAppConfirmWatchdogAlerted = preAppConfirmWatchdogAlerted;
    }

    public boolean isFullAppConfirmWatchdogAlerted() {
        return fullAppConfirmWatchdogAlerted;
    }

    public void setFullAppConfirmWatchdogAlerted(boolean fullAppConfirmWatchdogAlerted) {
        this.fullAppConfirmWatchdogAlerted = fullAppConfirmWatchdogAlerted;
    }
}

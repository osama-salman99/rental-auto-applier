package eu.roofz.crawler.model;

/**
 * Lifecycle of a single ReletPortal (roofz.onosre.com) application, driven by
 * {@code PortalPipelineService}. The happy path runs top to bottom; {@link #ERROR} is a terminal-
 * until-retried state the watchdog escalates on.
 *
 * <pre>
 *   DISCOVERED ──submit pre-application──▶ PRE_APP_SUBMITTED
 *   PRE_APP_SUBMITTED ──portal opens full app──▶ AWAITING_FULL
 *   AWAITING_FULL ──auto-fill + save questionnaire──▶ FULL_FILLED
 *   FULL_FILLED ──auto-upload stored documents (if enabled)──▶ DOCS_UPLOADED
 *   DOCS_UPLOADED ──finalize (if enabled)──▶ FINALIZED
 * </pre>
 *
 * With document auto-upload / auto-finalize disabled the flow stops at FULL_FILLED (or DOCS_UPLOADED)
 * and notifies + calls the user to finish the remaining manual step.
 */
public enum PortalApplicationState {
    /** Invitation link extracted from an email; pre-application not yet submitted. */
    DISCOVERED,
    /** Pre-application POSTed; the portal application record now exists. */
    PRE_APP_SUBMITTED,
    /** Portal reports {@code awaiting_full_application}; ready for us to fill the full form. */
    AWAITING_FULL,
    /** Full questionnaire auto-filled and saved (not finalized). */
    FULL_FILLED,
    /** Stored documents uploaded to the application; ready to finalize. */
    DOCS_UPLOADED,
    /** Portal reports {@code full_application_completed}; the application is finalized. */
    FINALIZED,
    /** A step failed; the watchdog escalates and retries are bounded. */
    ERROR
}

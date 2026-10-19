package eu.vesteda.crawler.model;

/**
 * Outcome of one auto-apply attempt. Stored as a plain string on {@link Listing} rather than a JPA
 * enum, so adding a value later can't break an existing H2 column.
 */
public final class ApplicationStatus {

    /** Viewing request submitted and confirmed on the portal. Occupies one of the five slots. */
    public static final String APPLIED = "APPLIED";

    /** Would have applied, but dry-run is on. The payload was logged instead. */
    public static final String DRY_RUN = "DRY_RUN";

    /** Not attempted: a required answer or document is missing, so it could not enter the draw. */
    public static final String SKIPPED = "SKIPPED";

    /** Attempted and did not land. The watchdog notifies; the slot is not counted as spent. */
    public static final String FAILED = "FAILED";

    /** The property was let to someone else, so the request was withdrawn to recycle the slot. */
    public static final String WITHDRAWN = "WITHDRAWN";

    private ApplicationStatus() {}
}

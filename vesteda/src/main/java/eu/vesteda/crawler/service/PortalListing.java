package eu.vesteda.crawler.service;

/**
 * One home as the tenant portal sees it — which is not quite how the public API sees it.
 *
 * @param objectHash the 32-hex id every portal URL keys off, and the only way to apply
 * @param address street + number, as printed
 * @param city as printed
 * @param price the rent the portal itself advertises. This is the number Vesteda's income rule is
 *     computed from (a home at €1.945 states an income requirement of €6.807 — exactly 3.5×), so it
 *     is the one the rent ceiling must be checked against. The public API can lag it.
 * @param bedrooms may be null
 * @param livingArea m², may be null
 * @param status whether the portal will still take a viewing request for it
 * @param crowded the portal's own "this house has many viewing requests" warning
 */
public record PortalListing(
        String objectHash,
        String address,
        String city,
        Integer price,
        Integer bedrooms,
        Integer livingArea,
        Status status,
        boolean crowded) {

    /**
     * The listing card prints its status as bare text with no CSS class to key off, so this is the
     * one place we must read prose. Both languages are matched, and anything unrecognised becomes
     * {@link #UNKNOWN}, which is treated as "cannot apply" — failing closed, because guessing wrong
     * spends a slot.
     */
    public enum Status {
        /** "For rent" — open, and a viewing request goes into the draw proper. */
        FOR_RENT,
        /**
         * "Reserved" — someone is ahead of us, but the portal still offers the request form and
         * keeps the interest on file as a reserve. A long shot, not a closed door.
         */
        RESERVED,
        /** "Rented with reservation" / "Rented" — the portal offers no request form at all. */
        CLOSED,
        UNKNOWN;

        static Status fromLabel(String label) {
            String t = label == null ? "" : label.trim().toLowerCase();
            // Order matters: "rented with reservation" must not be read as "rented ... reserved".
            if (t.contains("rented with reservation") || t.contains("verhuurd onder voorbehoud")) {
                return CLOSED;
            }
            if (t.contains("rented") || t.contains("verhuurd")) {
                return CLOSED;
            }
            if (t.contains("reserved") || t.contains("gereserveerd")) {
                return RESERVED;
            }
            if (t.contains("for rent") || t.contains("te huur") || t.contains("new") || t.contains("nieuw")) {
                return FOR_RENT;
            }
            return UNKNOWN;
        }

        /** Whether the portal will accept a viewing request for a home in this state. */
        public boolean acceptsRequests() {
            return this == FOR_RENT || this == RESERVED;
        }
    }
}

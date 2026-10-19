package eu.vesteda.crawler.service;

/**
 * One card on the portal's "My next home" page — the only trustworthy answer to what we actually
 * hold.
 *
 * @param uuid the portal's own candidacy id, needed to withdraw. Not the object hash.
 * @param objectHash the 32-hex property id, which is what every other page keys off
 * @param status where this candidacy sits on Vesteda's seven steps
 * @param standby the home had already stopped accepting viewings when our request arrived, so we
 *     are a reserve: "there are currently no more viewings possible, however your interest is
 *     registered". Not worthless — Vesteda re-draws if its chosen candidate walks away — but a long
 *     shot, and the first slot to give up when a home we could actually win appears.
 * @param label the address as the portal prints it, for notifications
 */
public record Candidacy(
        String uuid, String objectHash, Status status, boolean standby, String label) {

    /**
     * Vesteda's seven steps, read from the status dot's {@code hoofdstatus-*} CSS class rather than
     * from the visible label. The class name is the same in every language; the label is not, and
     * the account's language is a user setting we don't control (it currently renders English).
     */
    public enum Status {
        /** interesse — saved to the wishlist. Free: it does not spend one of the five requests. */
        INTERESTED,
        /** aanvraag — a viewing request is in. This is what spends a slot. */
        REQUESTED,
        /**
         * uitnodiging / bezichtiging / optieaanvraag / optie / contract — we have been drawn and are
         * being processed. Still holds a slot, and must never be withdrawn: this is a win in
         * progress.
         */
        ADVANCED;

        /**
         * Anything we don't recognise counts as ADVANCED, so a step Vesteda adds later can never be
         * mistaken for a withdrawable pending request and cancelled out from under the user.
         */
        static Status fromDot(String dot) {
            return switch (dot) {
                case "interesse" -> INTERESTED;
                case "aanvraag" -> REQUESTED;
                default -> ADVANCED;
            };
        }
    }

    /** Both a pending request and a win-in-progress count against Vesteda's cap of five. */
    public boolean occupiesSlot() {
        return status != Status.INTERESTED;
    }

    /**
     * Withdrawable only while the request is still waiting on the draw. Once Vesteda has drawn us,
     * the candidacy advances — and from the listing's side a win looks exactly like a loss (the home
     * turns "rented with reservation" either way), so only the candidacy's own status may ever
     * authorise a withdrawal.
     */
    public boolean isWithdrawable() {
        return status == Status.REQUESTED;
    }

    /** Still a ticket, just a poor one — kept unless a better home needs the slot. */
    public boolean isLongShot() {
        return isWithdrawable() && standby;
    }
}

package eu.ikwilhuren.crawler.service;

/**
 * One row of the portal's "my reactions" page.
 *
 * @param uuid the portal's own id, needed to withdraw
 * @param objectHash the 32-hex property id, which is also the tail of our listing slug
 * @param status where this candidacy sits in MVGM's 7 steps
 * @param lost MVGM has given the home to someone else — this ticket is worth nothing
 * @param saturated the home stopped accepting viewings before our request got in. Not worthless:
 *     MVGM keeps the interest on file and re-draws if its chosen candidate walks away. A long shot,
 *     and the first thing to give up when a home we could actually win appears.
 * @param label the address as the portal prints it, for notifications
 */
public record Candidacy(
        String uuid,
        String objectHash,
        Status status,
        boolean lost,
        boolean saturated,
        String label) {

    /**
     * MVGM's 7 steps, read from the card's {@code bg-status-*} dot. The class name is the same in
     * every language, unlike the visible label — which matters, because the account renders in
     * English and the language is a user setting we don't control.
     */
    public enum Status {
        /** interesse — on the wishlist. Free: it does not occupy one of the five slots. */
        INTERESTED,
        /** aanvraag — a viewing request is in. This is what spends a slot. */
        REQUESTED,
        /**
         * uitnodiging / bezichtiging / controle / aanbieding / contract — we were drawn and are
         * being processed. Still holds a slot, but must never be withdrawn: this is a win in
         * progress.
         */
        ADVANCED;

        static Status fromDot(String dot) {
            return switch (dot) {
                case "interesse" -> INTERESTED;
                case "aanvraag" -> REQUESTED;
                default -> ADVANCED;
            };
        }
    }

    /** Both a pending request and a win-in-progress count against MVGM's cap of five. */
    public boolean occupiesSlot() {
        return status != Status.INTERESTED;
    }

    /**
     * Withdrawable only while the request is still awaiting the draw. Once MVGM has drawn us the
     * candidacy advances, the property is let — to us — and withdrawing would cancel our own win.
     */
    public boolean isWithdrawable() {
        return status == Status.REQUESTED;
    }

    /** A slot spent on a home that is already someone else's. Nothing can come of it. */
    public boolean isWastedSlot() {
        return isWithdrawable() && lost;
    }

    /** Still a ticket, just a poor one — kept unless a better home needs the slot. */
    public boolean isLongShot() {
        return isWithdrawable() && saturated && !lost;
    }
}

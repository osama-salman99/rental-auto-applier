package eu.roofz.crawler.service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a ReletPortal invitation link of the form
 * {@code https://roofz.onosre.com/invitation/{invitationId}[/token/{magicToken}]} (query strings and
 * surrounding text tolerated), as found in the Roofz invitation emails.
 *
 * <p>Two shapes occur in the wild: the <b>pre-application</b> invite carries a {@code /token/…}
 * segment, while the <b>full-application</b> ("Complete application") invite is just
 * {@code /invitation/{id}} with no token. The {@code magicToken} is therefore optional (null when
 * absent) — the pipeline authenticates with its own session and only needs the invitation id.
 *
 * <p>Note: in the real emails these links are wrapped in a SendGrid {@code tracking.osre.nl/ls/click}
 * redirect, so the caller must resolve the redirect to the final URL before parsing (see
 * {@code EmailIngestService}).
 */
public record InvitationLink(String invitationId, String magicToken) {

    private static final Pattern PATTERN =
            Pattern.compile("/invitation/([^/\\s\"'<>?&]+)(?:/token/([^/\\s\"'<>?&]+))?");

    /** Extracts the first invitation link found in the given text (email body or a raw URL). */
    public static Optional<InvitationLink> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher m = PATTERN.matcher(text);
        if (m.find()) {
            // group(2) (magicToken) is absent for full-application invites.
            return Optional.of(new InvitationLink(m.group(1), m.group(2)));
        }
        return Optional.empty();
    }
}

package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class InvitationLinkTest {

    @Test
    void parsesPlainInvitationUrl() {
        Optional<InvitationLink> link =
                InvitationLink.parse(
                        "https://roofz.onosre.com/invitation/abc-123/token/magic-XYZ");
        assertThat(link).isPresent();
        assertThat(link.get().invitationId()).isEqualTo("abc-123");
        assertThat(link.get().magicToken()).isEqualTo("magic-XYZ");
    }

    @Test
    void extractsFromSurroundingHtml() {
        String html =
                "<a href=\"https://roofz.onosre.com/invitation/inv9/token/tok9?utm=x\">Complete</a>";
        Optional<InvitationLink> link = InvitationLink.parse(html);
        assertThat(link).isPresent();
        assertThat(link.get().invitationId()).isEqualTo("inv9");
        assertThat(link.get().magicToken()).isEqualTo("tok9");
    }

    @Test
    void parsesFullApplicationInviteWithoutToken() {
        // The "Complete application" (full-application) invite has no /token/ segment.
        Optional<InvitationLink> link =
                InvitationLink.parse(
                        "https://roofz.onosre.com/invitation/2211fb7a-0c89-43db-be1e-54efe834e64a?utm_source=sendgrid.com");
        assertThat(link).isPresent();
        assertThat(link.get().invitationId()).isEqualTo("2211fb7a-0c89-43db-be1e-54efe834e64a");
        assertThat(link.get().magicToken()).isNull();
    }

    @Test
    void emptyWhenNoLink() {
        assertThat(InvitationLink.parse("no link here")).isEmpty();
        assertThat(InvitationLink.parse(null)).isEmpty();
        assertThat(InvitationLink.parse("")).isEmpty();
    }
}

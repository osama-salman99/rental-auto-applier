package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmailIngestServiceTest {

    private final PortalPipelineService pipeline = mock(PortalPipelineService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final CrawlerConfigRepository configRepo = mock(CrawlerConfigRepository.class);
    private final EmailIngestService service =
            new EmailIngestService(pipeline, notifications, configRepo);

    @Test
    void extractsInvitationFromQuotedPrintableSoftWrappedHtml() {
        // The token is split by a QP soft line break (=\r\n); Jakarta Mail must rejoin it.
        String raw =
                "From: Roofz <no-reply@onosre.com>\r\n"
                        + "Subject: Complete your pre-application\r\n"
                        + "MIME-Version: 1.0\r\n"
                        + "Content-Type: text/html; charset=UTF-8\r\n"
                        + "Content-Transfer-Encoding: quoted-printable\r\n"
                        + "\r\n"
                        + "<p>Hi, please <a href=3D\"https://roofz.onosre.com/invitation/inv-abc/token/tok-=\r\n"
                        + "xyz123\">continue</a>.</p>\r\n";
        when(pipeline.onInvitationDiscovered(eq("inv-abc"), eq("tok-xyz123"), any())).thenReturn(true);

        String result = service.handle(raw.getBytes(StandardCharsets.UTF_8));

        verify(pipeline).onInvitationDiscovered(eq("inv-abc"), eq("tok-xyz123"), any());
        verify(notifications, never()).sendInboundEmailNotice(any(), any(), any());
        assertThat(result).contains("inv-abc");
    }

    @Test
    void resolvesInvitationBehindSendGridClickTracker() {
        // Real Roofz emails wrap the invite URL in a tracking.osre.nl redirect; the resolver follows
        // it to the final roofz.onosre.com URL. Override resolveRedirect so the test never hits network.
        EmailIngestService tracked =
                new EmailIngestService(pipeline, notifications, configRepo) {
                    @Override
                    String resolveRedirect(String url) {
                        return url.contains("tracking.osre.nl")
                                ? "https://roofz.onosre.com/invitation/inv-777/token/tok-777?utm_source=sendgrid.com"
                                : null;
                    }
                };
        String raw =
                "From: Roofz <no-reply@onosre.com>\r\n"
                        + "Subject: Start your pre-application\r\n"
                        + "Content-Type: text/plain; charset=UTF-8\r\n"
                        + "\r\n"
                        + "Start here: http://tracking.osre.nl/ls/click?upn=u001.abcDEF123\r\n";
        when(pipeline.onInvitationDiscovered(eq("inv-777"), eq("tok-777"), any())).thenReturn(true);

        String result = tracked.handle(raw.getBytes(StandardCharsets.UTF_8));

        verify(pipeline).onInvitationDiscovered(eq("inv-777"), eq("tok-777"), any());
        verify(notifications, never()).sendInboundEmailNotice(any(), any(), any());
        assertThat(result).contains("inv-777");
    }

    @Test
    void routesPreApplicationConfirmationToPipeline() {
        when(pipeline.recordConfirmationEmail(eq(false), any())).thenReturn("pre-application confirmed for X");
        String raw =
                "From: Roofz <living@rockfieldrealestate.com>\r\n"
                        + "Subject: Confirmation of your pre-application for Nieuwe Voorbeeldgracht 12-C, Amsterdam\r\n"
                        + "Content-Type: text/plain; charset=UTF-8\r\n"
                        + "\r\n"
                        + "Thank you for completing your pre-application.\r\n";

        String result = service.handle(raw.getBytes(StandardCharsets.UTF_8));

        verify(pipeline).recordConfirmationEmail(eq(false), any());
        verify(pipeline, never()).onInvitationDiscovered(any(), any(), any());
        verify(notifications, never()).sendInboundEmailNotice(any(), any(), any());
        assertThat(result).contains("confirmed");
    }

    @Test
    void routesFullApplicationConfirmationToPipelineAsFull() {
        when(pipeline.recordConfirmationEmail(eq(true), any())).thenReturn("full-application confirmed for X");
        String raw =
                "From: Roofz <living@rockfieldrealestate.com>\r\n"
                        + "Subject: Confirmation of your application for Nieuwe Voorbeeldgracht 14-D, Amsterdam\r\n"
                        + "Content-Type: text/plain; charset=UTF-8\r\n"
                        + "\r\n"
                        + "Thank you for completing your full-application.\r\n";

        String result = service.handle(raw.getBytes(StandardCharsets.UTF_8));

        verify(pipeline).recordConfirmationEmail(eq(true), any());
        assertThat(result).contains("confirmed");
    }

    @Test
    void routesAllocationOfferToPipeline() {
        when(pipeline.recordOfferEmail(any(), any())).thenReturn("offer recorded for X");
        String raw =
                "From: ROOFZ.eu <living@rockfieldrealestate.com>\r\n"
                        + "Subject: Congratulations! Nieuwe Voorbeeldgracht 16-D, Amsterdam has been allocated to you\r\n"
                        + "Content-Type: text/plain; charset=UTF-8\r\n"
                        + "\r\n"
                        + "Congratulations, it has been allocated to you. Respond within 24 hours.\r\n";

        String result = service.handle(raw.getBytes(StandardCharsets.UTF_8));

        verify(pipeline)
                .recordOfferEmail(
                        eq("Congratulations! Nieuwe Voorbeeldgracht 16-D, Amsterdam has been allocated to you"),
                        any());
        verify(pipeline, never()).recordConfirmationEmail(anyBoolean(), any());
        verify(pipeline, never()).onInvitationDiscovered(any(), any(), any());
        verify(notifications, never()).sendInboundEmailNotice(any(), any(), any());
        assertThat(result).contains("offer recorded");
    }

    @Test
    void resolvesOfferRespondLinkBehindTracker() {
        // The offer email's "respond" button is a tracking.osre.nl redirect to the portal; the resolver
        // follows it and passes the final onosre.com URL to the pipeline. resolveRedirect is overridden
        // so the test never hits the network.
        EmailIngestService tracked =
                new EmailIngestService(pipeline, notifications, configRepo) {
                    @Override
                    String resolveRedirect(String url) {
                        return url.contains("tracking.osre.nl")
                                ? "https://roofz.onosre.com/offer/off-42?utm_source=sendgrid.com"
                                : null;
                    }
                };
        String respondUrl = "https://roofz.onosre.com/offer/off-42?utm_source=sendgrid.com";
        when(pipeline.recordOfferEmail(any(), eq(respondUrl))).thenReturn("offer recorded for X");
        String raw =
                "From: ROOFZ.eu <living@rockfieldrealestate.com>\r\n"
                        + "Subject: Congratulations! Brede Hilledijk 154-G, Rotterdam has been allocated to you\r\n"
                        + "Content-Type: text/plain; charset=UTF-8\r\n"
                        + "\r\n"
                        + "View and respond: http://tracking.osre.nl/ls/click?upn=u001.OFFERxyz\r\n";

        tracked.handle(raw.getBytes(StandardCharsets.UTF_8));

        verify(pipeline).recordOfferEmail(any(), eq(respondUrl));
    }

    @Test
    void notifiesForNonInvitationEmail() {
        when(configRepo.findById(1L)).thenReturn(Optional.of(new CrawlerConfig()));
        String raw =
                "From: Gmail <forwarding-noreply@google.com>\r\n"
                        + "Subject: Gmail Forwarding Confirmation\r\n"
                        + "Content-Type: text/plain; charset=UTF-8\r\n"
                        + "\r\n"
                        + "Confirm forwarding at https://mail.google.com/mail/vf-abc123 code 998877\r\n";

        String result = service.handle(raw.getBytes(StandardCharsets.UTF_8));

        verify(pipeline, never()).onInvitationDiscovered(any(), any(), any());
        verify(notifications)
                .sendInboundEmailNotice(
                        eq("Gmail Forwarding Confirmation"), any(), any(CrawlerConfig.class));
        assertThat(result).contains("notified");
    }

    @Test
    void stripsCrlfFromSubjectToPreventLogForging() {
        when(configRepo.findById(1L)).thenReturn(Optional.of(new CrawlerConfig()));
        // A MIME encoded-word Subject that decodes to bytes containing CR/LF is the
        // log-forging vector: getSubject() returns the decoded string *with* the
        // embedded newline, so safe() must strip it before it reaches the log / notice.
        String decoded = "Alert\r\nJul 25 00:00 forged-log-line evil=true";
        String encodedWord =
                "=?utf-8?B?"
                        + Base64.getEncoder().encodeToString(decoded.getBytes(StandardCharsets.UTF_8))
                        + "?=";
        String raw =
                "From: attacker <x@example.com>\r\n"
                        + "Subject: " + encodedWord + "\r\n"
                        + "Content-Type: text/plain; charset=UTF-8\r\n"
                        + "\r\n"
                        + "nothing actionable here\r\n";

        service.handle(raw.getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(notifications)
                .sendInboundEmailNotice(subject.capture(), any(), any(CrawlerConfig.class));
        assertThat(subject.getValue()).doesNotContain("\r").doesNotContain("\n");
        assertThat(subject.getValue()).contains("Alert").contains("forged-log-line");
    }
}

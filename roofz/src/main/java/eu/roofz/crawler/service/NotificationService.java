package eu.roofz.crawler.service;

import eu.osmosis.crawler.core.notify.NtfyClient;
import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.Listing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final NtfyClient ntfy;
    private final TwilioCallService twilioCallService;
    private final String publicUrl;

    public NotificationService(
            NtfyClient ntfy,
            TwilioCallService twilioCallService,
            @Value("${roofz.public-url:}") String publicUrl) {
        this.ntfy = ntfy;
        this.twilioCallService = twilioCallService;
        // Kept without a trailing slash, so a path can be appended.
        this.publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
    }

    public boolean sendNewListingNotification(Listing listing, CrawlerConfig config) {
        boolean sent = sendNotification(
                listing,
                config,
                "[Roofz] New: " + listing.getTitle(),
                formatDetails(listing));
        if (sent) {
            twilioCallService.triggerNewListingCall();
        }
        return sent;
    }

    /**
     * One crawl, one notification.
     *
     * <p>A sweep that turns up three homes is one thing happening, not three — and three separate
     * buzzes have to be reassembled in the reader's head before they mean anything. So the crawl
     * speaks once and lists what it found.
     */
    public boolean sendNewListingsNotification(java.util.List<Listing> listings, CrawlerConfig config) {
        if (listings.isEmpty()) {
            return false;
        }
        if (listings.size() == 1) {
            return sendNewListingNotification(listings.get(0), config);
        }
        StringBuilder body = new StringBuilder();
        for (Listing l : listings) {
            body.append(l.getTitle());
            if (l.getPrice() != null) {
                body.append(" — €").append(l.getPrice());
            }
            if (l.getLivingArea() != null) {
                body.append(" · ").append(l.getLivingArea()).append(" m²");
            }
            body.append('\n');
        }
        sendStandaloneNotification(
                config,
                "[Roofz] " + listings.size() + " new homes",
                body.toString().trim(),
                "house",
                null);
        return true;
    }

    public void sendStatusChangeNotification(
            Listing listing, String oldStage, CrawlerConfig config) {
        sendNotification(
                listing,
                config,
                "[Roofz] " + listing.getTitle() + ": " + oldStage + " \u2192 " + listing.getStage(),
                formatDetails(listing));
    }

    public void sendNewProjectNotification(
            String projectSlug, String projectTitle, CrawlerConfig config) {
        sendStandaloneNotification(
                config,
                "[Roofz] New project: " + projectTitle,
                "Slug: " + projectSlug,
                "building_construction",
                "https://roofz.eu/huur/projects/" + projectSlug);
    }

    public void sendProjectFutureOfferNotification(
            String projectSlug, String projectTitle, CrawlerConfig config) {
        sendStandaloneNotification(
                config,
                "[Roofz] Future offer interest: " + projectTitle,
                "Slug: " + projectSlug,
                "building_construction",
                "https://roofz.eu/huur/projects/" + projectSlug);
    }

    public void sendCrawlFailureNotification(
            int consecutiveFailures, String errorMessage, CrawlerConfig config) {
        String suffix = consecutiveFailures == 1
                ? ""
                : " (\u00d7" + consecutiveFailures + ")";
        sendStandaloneNotification(
                config,
                "[Roofz] Crawl failing" + suffix,
                "Error: " + errorMessage,
                "warning",
                null);
    }

    public void sendCrawlRecoveredNotification(int recoveredFromFailureCount, CrawlerConfig config) {
        String plural = recoveredFromFailureCount == 1 ? "" : "s";
        sendStandaloneNotification(
                config,
                "[Roofz] Crawl recovered",
                "Resumed after " + recoveredFromFailureCount + " consecutive failure" + plural,
                "white_check_mark",
                null);
    }

    // --- ReletPortal application pipeline notifications ---------------------------------------

    public void sendPreApplicationSubmitted(String address, CrawlerConfig config) {
        sendStandaloneNotification(
                config,
                "[Roofz] Pre-application submitted",
                address == null ? "" : address,
                "white_check_mark",
                null);
    }

    /**
     * Fired once the full application has been auto-filled and saved. High-priority + click-through
     * to the manual form so the user can upload documents and press submit. The Twilio call is
     * triggered separately by the pipeline.
     */
    public void sendFullApplicationReady(String address, String applicationId, CrawlerConfig config) {
        String url = "https://roofz.onosre.com/application/" + applicationId + "/manual";
        String body =
                (address == null ? "" : address + "\n")
                        + "Everything is filled & saved. Upload your bank statements + payslips and press submit.";
        sendStandaloneNotification(
                config, "[Roofz] Full application ready - upload docs & submit", body, "page_facing_up", url);
    }

    /** Fired once the full application has been auto-filled, documents uploaded, and finalized. */
    public void sendApplicationFinalized(String address, CrawlerConfig config) {
        sendStandaloneNotification(
                config,
                "[Roofz] Application submitted",
                (address == null ? "" : address + "\n")
                        + "Full application filled, documents attached, and finalized automatically.",
                "tada",
                null);
    }

    /**
     * Fired when an offer/allocation email ("… has been allocated to you") arrives. Time-critical:
     * the offer must be accepted or declined within ~24 hours or the home can be lost. Click-through
     * goes to the portal "respond" page when we could resolve it. The Twilio call is triggered
     * separately by the pipeline so a missed push still reaches the user by phone.
     */
    public void sendOfferReceived(String address, String offerLink, CrawlerConfig config) {
        String body =
                (address == null ? "" : address + "\n")
                        + "ALLOCATED TO YOU. Accept or decline within 24 hours — tap to respond.";
        sendStandaloneNotification(
                config, "[Roofz] OFFER received - respond within 24h", body, "tada,rotating_light", offerLink);
    }

    /** Fired when documents were uploaded automatically but finalize is left to the user. */
    public void sendDocumentsUploaded(String address, String applicationId, CrawlerConfig config) {
        String url = "https://roofz.onosre.com/application/" + applicationId + "/manual";
        sendStandaloneNotification(
                config,
                "[Roofz] Documents uploaded - review & submit",
                (address == null ? "" : address + "\n")
                        + "Filled & documents attached automatically. Review and press submit.",
                "page_facing_up",
                url);
    }

    public void sendPortalDryRunReady(String step, String address, CrawlerConfig config) {
        sendStandaloneNotification(
                config,
                "[Roofz] Portal dry-run ready: " + step,
                "Review the logged payload, then disable dry-run to go live.\n"
                        + (address == null ? "" : address),
                "test_tube",
                null);
    }

    public void sendAppliedNoInvite(String listingTitle, CrawlerConfig config) {
        sendStandaloneNotification(
                config,
                "[Roofz] Applied but no pre-application invite",
                (listingTitle == null ? "" : listingTitle + "\n")
                        + "No invite arrived in the expected window — check the portal/email; may need manual action.",
                "warning",
                null);
    }

    /** W2: we submitted the pre-application but no confirmation email arrived within the SLA. */
    public void sendPreAppConfirmationMissing(String address, CrawlerConfig config) {
        sendStandaloneNotification(
                config,
                "[Roofz] Pre-application NOT confirmed",
                (address == null ? "" : address + "\n")
                        + "We submitted the pre-application but no confirmation email arrived in the expected window — it may not have registered. Check the portal.",
                "warning",
                null);
    }

    /** W3: we finalized the full application but no confirmation email arrived within the SLA. */
    public void sendFullAppConfirmationMissing(String address, CrawlerConfig config) {
        sendStandaloneNotification(
                config,
                "[Roofz] Full application NOT confirmed",
                (address == null ? "" : address + "\n")
                        + "We finalized the full application but no confirmation email arrived in the expected window — it may not have registered. Check the portal.",
                "warning",
                null);
    }

    /** Start-of-month nudge to upload the new month's payslip + bank statement. */
    public void sendMonthlyDocumentReminder(String monthLabel, String missingDetail, CrawlerConfig config) {
        sendStandaloneNotification(
                config,
                "[Roofz] New month - upload documents (" + monthLabel + ")",
                "Add this month's documents so applications attach the latest 3 months.\n" + missingDetail,
                "calendar",
                publicUrl.isBlank() ? null : publicUrl + "/documents");
    }

    public void sendPipelineError(String context, String message, CrawlerConfig config) {
        sendStandaloneNotification(
                config, "[Roofz] Portal pipeline error: " + context, message, "warning", null);
    }

    /**
     * An inbound email arrived at the ingest address that wasn't a ReletPortal invitation — most
     * likely the Gmail forwarding-verification email. Surface its subject + any links so the user can
     * complete the one-time forwarding setup.
     */
    public void sendInboundEmailNotice(String subject, String detail, CrawlerConfig config) {
        sendStandaloneNotification(
                config, "[Roofz] Inbound email: " + subject, detail, "email", null);
    }

    private void sendStandaloneNotification(
            CrawlerConfig config, String title, String message, String tags, String clickUrl) {
        // The Title is ASCII-folded and the body is sent as utf-8 by NtfyClient — roofz's old
        // private asciiHeader (em/en-dash to '-', emoji removed, whitespace collapsed) has been
        // folded into NtfyClient.asciiHeader as the one canonical sanitizer, so the raw title goes
        // straight through and this send path can't drift on charset or header encoding.
        ntfy.send(config.getNtfyServerUrl(), config.getNtfyTopic(), title, message, clickUrl, tags);
    }

    private String formatDetails(Listing l) {
        StringBuilder sb = new StringBuilder();
        if (l.getCity() != null) sb.append(l.getCity());
        if (l.getLivingArea() != null) sb.append(" \u2022 ").append(l.getLivingArea()).append(" m\u00B2");
        if (l.getBedrooms() != null) sb.append(" \u2022 ").append(l.getBedrooms()).append(" bed");
        if (l.getPrice() != null) {
            sb.append(" \u2022 \u20AC").append(l.getPrice()).append("/mo");
            if (l.getServiceCosts() != null) {
                sb.append(" + \u20AC").append(l.getServiceCosts()).append(" service");
            }
        }
        sb.append("\nStatus: ").append(l.getStage());
        return sb.toString();
    }

    private boolean sendNotification(
            Listing listing, CrawlerConfig config, String title, String message) {
        String listingUrl = "https://roofz.eu/huur/woningen/" + listing.getSlug();
        return ntfy.send(
                config.getNtfyServerUrl(),
                config.getNtfyTopic(),
                title,
                message,
                listingUrl,
                "house");
    }
}

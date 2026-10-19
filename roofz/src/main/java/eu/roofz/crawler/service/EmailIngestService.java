package eu.roofz.crawler.service;

import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Parses a raw RFC-822 email (sent to the crawler's inbound address and forwarded here, e.g. by a
 * Cloudflare Email Worker) and either registers the ReletPortal invitation it contains or —
 * for anything else, e.g. the Gmail forwarding-verification email — surfaces it via notification so
 * the user can act on it. Jakarta Mail decodes quoted-printable / base64 bodies, so the invitation
 * link survives transfer encoding.
 */
@Service
public class EmailIngestService {

    private static final Logger log = LoggerFactory.getLogger(EmailIngestService.class);

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>()]+");

    /**
     * SendGrid click-tracker wrapper Roofz routes every email link through. The real
     * {@code roofz.onosre.com/invitation/…} URL is only revealed by following the redirect, so we
     * resolve these to their final destination before parsing.
     */
    private static final Pattern TRACKER_PATTERN =
            Pattern.compile("https?://tracking\\.osre\\.nl/ls/click\\?[^\\s\"'<>()]+");

    private static final DateTimeFormatter ARCHIVE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    /** Follows redirects (SendGrid does an http→https→destination hop chain) to the final URL. */
    private static final HttpClient HTTP =
            HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(8))
                    .build();

    /** Cap on tracker links resolved per email — invites carry ~5–12; this bounds the outbound GETs. */
    private static final int MAX_TRACKERS_RESOLVED = 15;

    private final PortalPipelineService pipelineService;
    private final NotificationService notificationService;
    private final CrawlerConfigRepository configRepository;

    /** Where raw inbound emails are archived so they can be re-inspected (and the parser fixed). */
    @Value("${ingest.email-archive-dir:./data/emails}")
    private String archiveDir;

    public EmailIngestService(
            PortalPipelineService pipelineService,
            NotificationService notificationService,
            CrawlerConfigRepository configRepository) {
        this.pipelineService = pipelineService;
        this.notificationService = notificationService;
        this.configRepository = configRepository;
    }

    /** Handles one inbound raw email. Returns a short human-readable outcome for the caller/log. */
    public String handle(byte[] raw) {
        String subject = "(unknown)";
        String messageId = null;
        String body;
        try {
            MimeMessage msg = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw));
            subject = safe(msg.getSubject());
            messageId = msg.getMessageID();
            body = extractText(msg);
        } catch (Exception e) {
            // Fall back to treating the raw bytes as text so a malformed MIME still gets scanned.
            log.warn("Failed to parse inbound email as MIME ({}), scanning raw", e.getMessage());
            body = new String(raw, StandardCharsets.UTF_8);
        }

        archive(raw, subject);

        String dedupeId = messageId != null ? messageId : "sha:" + Integer.toHexString(body.hashCode());

        // A "Confirmation of your (pre-)application for {address}" email is the receipt that proves our
        // submission actually registered. Route it to the pipeline (matched by address) so the SLA
        // watchdog is satisfied — it carries no /invitation/ link, so it must be classified by subject.
        String subjLower = subject == null ? "" : subject.toLowerCase();

        // An "…has been allocated to you" email is the landlord's offer — the most time-critical signal
        // in the funnel (accept/decline within ~24h). Route it to the pipeline so the user is alerted
        // loudly (ntfy + phone call). It carries no /invitation/ link; classify it by subject.
        if (subjLower.contains("allocated to you")) {
            String offerLink = resolveTrackedPortalLink(body).orElse(null);
            String outcome = pipelineService.recordOfferEmail(subject, offerLink);
            log.info("Ingested offer/allocation email '{}' -> {} (respond link: {})", subject, outcome, offerLink);
            return outcome;
        }

        if (subjLower.contains("confirmation of your")
                && (subjLower.contains("pre-application") || subjLower.contains("application"))) {
            boolean fullApplication = !subjLower.contains("pre-application");
            String outcome = pipelineService.recordConfirmationEmail(fullApplication, subject);
            log.info("Ingested confirmation email '{}' -> {}", subject, outcome);
            return outcome;
        }

        Optional<InvitationLink> link = InvitationLink.parse(body);
        if (link.isEmpty()) {
            link = InvitationLink.parse(urlDecode(body));
        }
        if (link.isEmpty()) {
            // Real Roofz emails hide the invitation URL behind a SendGrid click-tracker; resolve them.
            link = resolveTrackedInvitation(body);
        }
        if (link.isPresent()) {
            InvitationLink il = link.get();
            boolean added =
                    pipelineService.onInvitationDiscovered(il.invitationId(), il.magicToken(), dedupeId);
            log.info("Ingested invitation {} from email '{}' (new={})", il.invitationId(), subject, added);
            return added ? "registered invitation " + il.invitationId() : "invitation already known";
        }

        // Not an invitation — most likely the Gmail forwarding-verification email. Surface it. Log
        // every URL (not just the count) so a missed invite link can be diagnosed from the logs.
        List<String> urls = extractUrls(body);
        log.info("Inbound email '{}' has no invitation link ({} url(s)): {} — notifying", subject, urls.size(), urls);
        CrawlerConfig config = configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
        if (config.isNotificationsEnabled()) {
            String detail = urls.isEmpty() ? "(no links found)" : String.join("\n", urls.subList(0, Math.min(8, urls.size())));
            notificationService.sendInboundEmailNotice(subject, detail, config);
        }
        return "no invitation link; notified";
    }

    /**
     * Extracts every SendGrid tracker link from the body, follows each redirect chain to its final
     * destination, and returns the first that resolves to a ReletPortal invitation URL. Only
     * {@code tracking.osre.nl} links are followed, so non-Roofz mail never triggers outbound requests.
     */
    Optional<InvitationLink> resolveTrackedInvitation(String body) {
        if (body == null) {
            return Optional.empty();
        }
        List<String> trackers = new ArrayList<>();
        Matcher m = TRACKER_PATTERN.matcher(body);
        while (m.find() && trackers.size() < MAX_TRACKERS_RESOLVED) {
            String u = m.group();
            if (!trackers.contains(u)) {
                trackers.add(u);
            }
        }
        for (String tracker : trackers) {
            String finalUrl = resolveRedirect(tracker);
            if (finalUrl == null) {
                continue;
            }
            Optional<InvitationLink> il = InvitationLink.parse(finalUrl);
            if (il.isPresent()) {
                log.info("Resolved tracked invitation link -> {}", finalUrl);
                return il;
            }
        }
        return Optional.empty();
    }

    /**
     * Follows the SendGrid tracker links and returns the first that lands on the ReletPortal
     * ({@code onosre.com}) — the "view & respond to the offer" page from an allocation email. Same
     * tracker-only redirect policy as {@link #resolveTrackedInvitation}, so non-Roofz mail never
     * triggers outbound requests. Returns empty if none resolve (the offer alert still fires without a link).
     */
    Optional<String> resolveTrackedPortalLink(String body) {
        if (body == null) {
            return Optional.empty();
        }
        List<String> trackers = new ArrayList<>();
        Matcher m = TRACKER_PATTERN.matcher(body);
        while (m.find() && trackers.size() < MAX_TRACKERS_RESOLVED) {
            String u = m.group();
            if (!trackers.contains(u)) {
                trackers.add(u);
            }
        }
        for (String tracker : trackers) {
            String finalUrl = resolveRedirect(tracker);
            if (finalUrl != null && finalUrl.contains("onosre.com")) {
                log.info("Resolved offer respond link -> {}", finalUrl);
                return Optional.of(finalUrl);
            }
        }
        return Optional.empty();
    }

    /** Follows a URL's redirect chain and returns the final URL, or null on error. Overridable in tests. */
    String resolveRedirect(String url) {
        try {
            HttpRequest req =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(15))
                            .header("User-Agent", "RoofzCrawler/1.0")
                            .GET()
                            .build();
            HttpResponse<Void> resp = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.uri().toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted resolving tracker redirect {}", url);
            return null;
        } catch (Exception e) {
            log.warn("Failed to resolve tracker redirect {}: {}", url, e.getMessage());
            return null;
        }
    }

    /** Writes the raw email to the archive dir so it can be re-inspected later (parser fixes, audit). */
    private void archive(byte[] raw, String subject) {
        if (archiveDir == null || archiveDir.isBlank()) {
            return;
        }
        try {
            Path dir = Path.of(archiveDir);
            Files.createDirectories(dir);
            String safe = (subject == null ? "email" : subject).replaceAll("[^a-zA-Z0-9.-]+", "_");
            if (safe.length() > 60) {
                safe = safe.substring(0, 60);
            }
            Path file = dir.resolve(ARCHIVE_TS.format(Instant.now()) + "-" + safe + ".eml");
            Files.write(file, raw);
            log.info("Archived inbound email ({} bytes) to {}", raw.length, file.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Failed to archive inbound email '{}': {}", subject, e.getMessage());
        }
    }

    /** Recursively collects decoded text from all text/* parts of the message. */
    private String extractText(Part part) {
        try {
            Object content = part.getContent();
            if (content instanceof String s) {
                return s;
            }
            if (content instanceof Multipart mp) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < mp.getCount(); i++) {
                    sb.append(extractText(mp.getBodyPart(i))).append('\n');
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.debug("Could not extract part content: {}", e.getMessage());
        }
        return "";
    }

    private static List<String> extractUrls(String text) {
        List<String> urls = new ArrayList<>();
        if (text == null) {
            return urls;
        }
        Matcher m = URL_PATTERN.matcher(text);
        while (m.find() && urls.size() < 10) {
            String u = m.group();
            if (!urls.contains(u)) {
                urls.add(u);
            }
        }
        return urls;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static String safe(String s) {
        // The ingest address is publicly emailable and this value gets logged; a MIME
        // encoded-word Subject can decode to bytes containing CR/LF, so strip control
        // chars to stop forged lines being injected into the log.
        return s == null ? "(no subject)" : s.replaceAll("\\p{Cntrl}+", " ");
    }
}

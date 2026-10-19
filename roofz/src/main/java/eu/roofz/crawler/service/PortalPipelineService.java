package eu.roofz.crawler.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.roofz.crawler.model.ApplicantDocument;
import eu.roofz.crawler.model.ApplicantProfile;
import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.PortalApplication;
import eu.roofz.crawler.model.PortalApplicationState;
import eu.roofz.crawler.repository.ApplicantProfileRepository;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import eu.roofz.crawler.repository.PortalApplicationRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drives each {@link PortalApplication} through its lifecycle on every scheduler tick:
 *
 * <ol>
 *   <li><b>DISCOVERED</b> (invitation from an email) → fetch the invitation + stub application, build
 *       the pre-application payload, and submit it (or log it under dry-run).
 *   <li><b>sync</b> the account's application list to advance states from the portal's own status
 *       ({@code awaiting_full_application}, {@code full_application_completed}).
 *   <li><b>AWAITING_FULL</b> → overlay the applicant profile onto the portal's questionnaire and save
 *       the full application (never finalize), then notify + phone-call the user to upload documents.
 * </ol>
 *
 * Document upload and the final submit are always left to the user (per product decision). Dry-run
 * mode (default on) performs every read and builds every payload but never POSTs/PUTs, so the first
 * real invitation can be validated before going live.
 */
@Service
public class PortalPipelineService {

    private static final Logger log = LoggerFactory.getLogger(PortalPipelineService.class);

    static final int MAX_RETRIES = 5;
    private static final int MAX_ERROR_LEN = 900;

    static final String STATUS_AWAITING_FULL = "awaiting_full_application";
    static final String STATUS_COMPLETED = "full_application_completed";
    static final String STATUS_PRE_APP_COMPLETED = "pre_application_completed";

    private final ReletPortalClient portal;
    private final PortalApplicationRepository repo;
    private final ApplicantProfileRepository profileRepo;
    private final CrawlerConfigRepository configRepo;
    private final NotificationService notificationService;
    private final TwilioCallService twilioCallService;
    private final ApplicantDocumentService documentService;
    private final ObjectMapper objectMapper;

    public PortalPipelineService(
            ReletPortalClient portal,
            PortalApplicationRepository repo,
            ApplicantProfileRepository profileRepo,
            CrawlerConfigRepository configRepo,
            NotificationService notificationService,
            TwilioCallService twilioCallService,
            ApplicantDocumentService documentService,
            ObjectMapper objectMapper) {
        this.portal = portal;
        this.repo = repo;
        this.profileRepo = profileRepo;
        this.configRepo = configRepo;
        this.notificationService = notificationService;
        this.twilioCallService = twilioCallService;
        this.documentService = documentService;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------------------------------------
    // Ingestion
    // ---------------------------------------------------------------------------------------------

    /**
     * Registers a newly discovered invitation (from the Gmail watcher or a manual replay). Deduped by
     * both the source email id and the invitation id so it is safe to call repeatedly.
     */
    @Transactional
    public boolean onInvitationDiscovered(String invitationId, String magicToken, String emailMessageId) {
        if (invitationId == null || invitationId.isBlank()) {
            return false;
        }
        if (emailMessageId != null && repo.existsBySourceEmailMessageId(emailMessageId)) {
            return false;
        }
        if (repo.findByInvitationId(invitationId).isPresent()) {
            return false;
        }
        repo.save(PortalApplication.fromInvitation(invitationId, magicToken, emailMessageId));
        log.info("Discovered ReletPortal invitation {} (email {})", invitationId, emailMessageId);
        return true;
    }

    /** Parses an invitation link and registers it. Returns a human-readable outcome for the UI. */
    @Transactional
    public String replayInvitationLink(String link) {
        Optional<InvitationLink> parsed = InvitationLink.parse(link);
        if (parsed.isEmpty()) {
            return "No invitation link found in input";
        }
        InvitationLink il = parsed.get();
        boolean added = onInvitationDiscovered(il.invitationId(), il.magicToken(), null);
        return added
                ? "Registered invitation " + il.invitationId()
                : "Invitation " + il.invitationId() + " already known";
    }

    /**
     * Records that a confirmation email arrived for the matching application, closing the "did our
     * submission actually register?" watchdog. The email subject carries the address (e.g.
     * "Confirmation of your pre-application for Nieuwe Voorbeeldgracht 12-C, Amsterdam"), matched
     * against each application's stored {@code residenceAddress} on a normalised (alphanumeric) basis.
     *
     * @param fullApplication true for the full-application confirmation, false for the pre-application
     * @param emailSubject the raw email subject (used for address matching)
     * @return a short human-readable outcome for the caller/log
     */
    @Transactional
    public String recordConfirmationEmail(boolean fullApplication, String emailSubject) {
        String subjectNorm = normalizeAddress(emailSubject);
        if (subjectNorm.isBlank()) {
            return "confirmation email had no matchable subject";
        }
        PortalApplication p = findByAddressInSubject(subjectNorm).orElse(null);
        if (p != null) {
            if (fullApplication) {
                if (p.getFullAppConfirmedAt() == null) {
                    p.setFullAppConfirmedAt(Instant.now());
                    save(p);
                }
                log.info("Full-application confirmation email matched application {} ({})",
                        p.getApplicationId(), p.getResidenceAddress());
                return "full-application confirmed for " + p.getResidenceAddress();
            }
            if (p.getPreAppConfirmedAt() == null) {
                p.setPreAppConfirmedAt(Instant.now());
                save(p);
            }
            log.info("Pre-application confirmation email matched application {} ({})",
                    p.getApplicationId(), p.getResidenceAddress());
            return "pre-application confirmed for " + p.getResidenceAddress();
        }
        log.info("Confirmation email (full={}) matched no tracked application: {}", fullApplication, emailSubject);
        return "confirmation email matched no tracked application";
    }

    /**
     * Records that an offer/allocation email ("… has been allocated to you") arrived and alerts the
     * user. This is the most time-critical signal in the whole funnel — the offer must be accepted or
     * declined within ~24 hours or the home is lost — so it fires a high-priority ntfy <b>and</b> a
     * Twilio call. Matched to the application by address like {@link #recordConfirmationEmail}; the
     * alert fires once per application (guarded by {@code offerNotified}). If it matches no tracked
     * application it is still surfaced — an offer is far too important to swallow.
     *
     * @param emailSubject the raw email subject (carries the address)
     * @param offerLink the resolved portal "respond" link, or null if it could not be resolved
     */
    @Transactional
    public String recordOfferEmail(String emailSubject, String offerLink) {
        String subjectNorm = normalizeAddress(emailSubject);
        CrawlerConfig config = configRepo.findById(1L).orElseGet(CrawlerConfig::defaults);
        PortalApplication p = subjectNorm.isBlank() ? null : findByAddressInSubject(subjectNorm).orElse(null);
        if (p != null) {
            if (p.getOfferedAt() == null) {
                p.setOfferedAt(Instant.now());
            }
            if (offerLink != null && !offerLink.isBlank()) {
                p.setOfferLink(offerLink);
            }
            if (!p.isOfferNotified()) {
                if (config.isNotificationsEnabled()) {
                    notificationService.sendOfferReceived(p.getResidenceAddress(), offerLink, config);
                    twilioCallService.triggerNewListingCall();
                }
                p.setOfferNotified(true);
            }
            save(p);
            log.info("Offer/allocation email matched application {} ({}) — user alerted",
                    p.getApplicationId(), p.getResidenceAddress());
            return "offer recorded for " + p.getResidenceAddress();
        }
        // No tracked application matched — still alert loudly; an offer must never go unannounced.
        if (config.isNotificationsEnabled()) {
            notificationService.sendOfferReceived(emailSubject, offerLink, config);
            twilioCallService.triggerNewListingCall();
        }
        log.warn("Offer/allocation email matched no tracked application — alerted anyway: {}", emailSubject);
        return "offer email matched no tracked application — alerted anyway";
    }

    /** Lowercases and strips to alphanumerics so "422-C, Amsterdam" and "422C Amsterdam" compare equal. */
    static String normalizeAddress(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /**
     * Finds the tracked application whose normalized address is the <b>longest</b> one contained in
     * {@code subjectNorm}. Longest-match is what fixes the street-number nesting collision: a subject
     * for "Kerkstraat 12" normalizes to "…kerkstraat12…", which contains <i>both</i> "kerkstraat1" and
     * "kerkstraat12", and a plain first-substring-wins match could attach the (time-critical) offer or
     * confirmation to "Kerkstraat 1" — the wrong home. Requiring the longest containment picks the more
     * specific address; ties (identical addresses) keep the most recent, since the repository returns
     * newest-first and the comparison is strict. A minimum length of 6 guards against spurious matches.
     */
    private Optional<PortalApplication> findByAddressInSubject(String subjectNorm) {
        if (subjectNorm == null || subjectNorm.isBlank()) {
            return Optional.empty();
        }
        PortalApplication best = null;
        int bestLen = 0;
        for (PortalApplication p : repo.findAllByOrderByCreatedAtDesc()) {
            String addr = normalizeAddress(p.getResidenceAddress());
            if (addr.length() < 6 || !subjectNorm.contains(addr)) {
                continue;
            }
            if (addr.length() > bestLen) {
                best = p;
                bestLen = addr.length();
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Looks up an already-created application for {@code p} on the portal account by <b>exact</b>
     * normalized residence address, so a prospect-application retry can adopt an application a prior
     * attempt created rather than creating a duplicate. Exact-equality only — never a substring/prefix
     * match — because this runs on the real submission path; a wrong adoption would drive the wrong
     * application. Returns null if nothing matches (then the caller safely creates a new one) or on any
     * lookup error.
     */
    private String resolveExistingApplicationId(PortalApplication p) {
        String want = normalizeAddress(p.getResidenceAddress());
        if (want.length() < 6) {
            return null;
        }
        try {
            JsonNode data = portal.listApplications().path("data");
            if (data.isArray()) {
                for (JsonNode item : data) {
                    String addr = normalizeAddress(extractAddress(item.path("attributes").path("residence")));
                    if (want.equals(addr)) {
                        String id = item.path("attributes").path("application").path("id").asText(null);
                        if (id == null || id.isBlank()) {
                            id = item.path("id").asText(null);
                        }
                        if (id != null && !id.isBlank()) {
                            return id;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("resolveExistingApplicationId failed for {}: {}", p.getInvitationId(), messageOf(e));
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------------------------------

    @Transactional
    public void pollAndAdvance() {
        CrawlerConfig config = configRepo.findById(1L).orElseGet(CrawlerConfig::defaults);
        if (!config.isPortalPipelineEnabled()) {
            return;
        }
        if (!portal.isConfigured()) {
            log.debug("ReletPortal not configured — skipping pipeline");
            return;
        }

        for (PortalApplication p : repo.findByStateIn(List.of(PortalApplicationState.DISCOVERED))) {
            advanceDiscovered(p, config);
        }
        syncStatuses(config);
        if (config.isPortalEagerPrefill() && !config.isPortalDryRun()) {
            for (PortalApplication p : repo.findByStateIn(List.of(PortalApplicationState.PRE_APP_SUBMITTED))) {
                if (!p.isFullPrefilled() && STATUS_PRE_APP_COMPLETED.equals(p.getPortalStatus())) {
                    prefillFullApplication(p);
                }
            }
        }
        for (PortalApplication p : repo.findByStateIn(List.of(PortalApplicationState.AWAITING_FULL))) {
            advanceAwaitingFull(p, config);
        }
        for (PortalApplication p : repo.findByStateIn(List.of(PortalApplicationState.FULL_FILLED))) {
            advanceFullFilled(p, config);
        }
        for (PortalApplication p : repo.findByStateIn(List.of(PortalApplicationState.DOCS_UPLOADED))) {
            advanceDocsUploaded(p, config);
        }
    }

    /**
     * Runs the real pre-application flow proven against the live API, which is <b>two</b> POSTs:
     *
     * <ol>
     *   <li>{@code prospect-application} — creates the application from the invitation + the applicant's
     *       work situation (salary is 0 here; it is collected later at the full-application stage) and
     *       returns its {@code applicationId}. Persisted immediately so a later failure resumes here.
     *   <li>{@code pre-application} — echoes the scaffold {@code person} the prospect step created
     *       (fixing the two fields the backend validates: the placeholder {@code dateOfBirth} and the
     *       phone format) and sets the housing/family/marital answers.
     * </ol>
     *
     * A "Complete application" email is instead a <b>full-application</b> invitation
     * ({@code isFullApplicationInvitation=true}); it already carries an {@code applicationId}, so it is
     * handed straight to the AWAITING_FULL step rather than submitting a pre-application.
     */
    private void advanceDiscovered(PortalApplication p, CrawlerConfig config) {
        try {
            JsonNode invAttrs = portal.getInvitation(p.getInvitationId()).path("data").path("attributes");
            String address = extractAddress(invAttrs);
            if (address != null) {
                p.setResidenceAddress(address);
            }

            if (invAttrs.path("isFullApplicationInvitation").asBoolean(false)) {
                String appId = invAttrs.path("applicationId").asText(null);
                if (appId == null || appId.isBlank()) {
                    throw new IllegalStateException(
                            "full-application invitation " + p.getInvitationId() + " has no applicationId");
                }
                // The pre-application row already tracks this application (and syncStatuses advances it
                // to AWAITING_FULL on its own), so a full-application invite email for it is redundant.
                Optional<PortalApplication> existing = repo.findByApplicationId(appId);
                if (existing.isPresent() && !existing.get().getId().equals(p.getId())) {
                    log.info("Full-application invite {} duplicates tracked application {} — dropping",
                            p.getInvitationId(), appId);
                    repo.delete(p);
                    return;
                }
                p.setApplicationId(appId);
                p.setState(PortalApplicationState.AWAITING_FULL);
                p.setRetryCount(0);
                p.setLastError(null);
                save(p);
                log.info("Full-application invitation {} -> application {} (awaiting full)",
                        p.getInvitationId(), appId);
                return;
            }

            JsonNode profile = objectMapper.readTree(profileJson());
            if (profile.path("partnerSituation").asBoolean(false)) {
                throw new IllegalStateException(
                        "partner pre-application requires manual handling (partnerSituation=true)");
            }

            if (config.isPortalDryRun()) {
                log.info("[DRY-RUN] prospect-application payload for invitation {}:\n{}",
                        p.getInvitationId(), pretty(buildProspectPayload(p.getInvitationId(), profile)));
                if (!p.isDryRunNotified()) {
                    notificationService.sendPortalDryRunReady("pre-application", p.getResidenceAddress(), config);
                    p.setDryRunNotified(true);
                }
                p.setLastError(null);
                save(p);
                return;
            }

            // Step 1: prospect-application creates the application. Persist the id right away so a
            // failure in step 2 resumes at step 2 rather than creating a duplicate application.
            String applicationId = p.getApplicationId();
            if ((applicationId == null || applicationId.isBlank()) && p.getRetryCount() > 0) {
                // A prior attempt may have created the application server-side but died before we could
                // read/persist the returned id (a response-read timeout, or an unexpected body). Blindly
                // re-POSTing prospect-application would then create a DUPLICATE real application. On a
                // retry, first try to recover the id from the account's application list by *exact*
                // normalized address; only create a new one if none exists.
                applicationId = resolveExistingApplicationId(p);
                if (applicationId != null) {
                    p.setApplicationId(applicationId);
                    save(p);
                    log.warn("Recovered existing application {} for invitation {} — not re-submitting prospect",
                            applicationId, p.getInvitationId());
                }
            }
            if (applicationId == null || applicationId.isBlank()) {
                JsonNode resp = portal.submitProspectApplication(buildProspectPayload(p.getInvitationId(), profile));
                applicationId = resp.path("data").path("id").asText(null);
                if (applicationId == null || applicationId.isBlank()) {
                    throw new IllegalStateException("prospect-application returned no application id");
                }
                p.setApplicationId(applicationId);
                save(p);
                log.info("Created application {} via prospect-application for invitation {}",
                        applicationId, p.getInvitationId());
            }

            // Step 2: pre-application. Echo the person scaffold the prospect step created and set the
            // housing/family/marital answers from the profile.
            JsonNode scaffoldPerson =
                    portal.getApplication(applicationId).path("data").path("attributes").path("questionnaire").path("person");
            ObjectNode payload = buildPreApplicationPayload(p.getInvitationId(), scaffoldPerson, profile);

            portal.submitPreApplication(payload);
            p.setState(PortalApplicationState.PRE_APP_SUBMITTED);
            p.setPreAppSubmittedAt(Instant.now());
            p.setRetryCount(0);
            p.setLastError(null);
            save(p);
            log.info("Submitted pre-application for invitation {} -> application {}",
                    p.getInvitationId(), applicationId);
            notificationService.sendPreApplicationSubmitted(p.getResidenceAddress(), config);
        } catch (Exception e) {
            handleStepError(p, "pre-application", e, config);
        }
    }

    /**
     * Fetches the application's current questionnaire and overlays the applicant profile onto it
     * (profile wins on conflicts). Shared by the eager pre-fill and the AWAITING_FULL save so both
     * produce the identical, proven payload.
     */
    private JsonNode buildFullQuestionnaire(PortalApplication p)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode existing =
                portal.getApplication(p.getApplicationId())
                        .path("data").path("attributes").path("questionnaire");
        JsonNode profile = objectMapper.readTree(profileJson());
        return deepMerge(existing.deepCopy(), profile);
    }

    /**
     * Eagerly fills + saves the full-application questionnaire while the portal still reports
     * {@code pre_application_completed} — i.e. before the landlord has "opened" the full application.
     * The portal accepts this save (verified live), so pre-filling leaves only document upload and the
     * finalize for the instant the application opens, keeping us first under first-come-first-serve.
     *
     * <p>Best-effort by design: if a listing does not accept an early save, that is <b>not</b> an
     * error — it is logged and left for {@link #advanceAwaitingFull} to fill once the portal opens the
     * application. No retry count is incremented, nothing escalates, and the state stays
     * PRE_APP_SUBMITTED. Document upload and finalize are never attempted early.
     */
    private void prefillFullApplication(PortalApplication p) {
        try {
            portal.updateFullApplication(p.getApplicationId(), buildFullQuestionnaire(p));
            p.setFullPrefilled(true);
            save(p);
            log.info("Eagerly pre-filled full application {} before it opened (portal status {})",
                    p.getApplicationId(), p.getPortalStatus());
        } catch (Exception e) {
            log.info("Early pre-fill of {} not accepted yet ({}) — will fill it when the portal opens",
                    refOf(p), messageOf(e));
        }
    }

    private void advanceAwaitingFull(PortalApplication p, CrawlerConfig config) {
        try {
            JsonNode merged = buildFullQuestionnaire(p);

            if (config.isPortalDryRun()) {
                log.info("[DRY-RUN] full-application questionnaire for application {}:\n{}",
                        p.getApplicationId(), pretty(merged));
                if (!p.isDryRunNotified()) {
                    notificationService.sendPortalDryRunReady("full-application", p.getResidenceAddress(), config);
                    p.setDryRunNotified(true);
                }
                save(p);
                return;
            }

            portal.updateFullApplication(p.getApplicationId(), merged);
            p.setState(PortalApplicationState.FULL_FILLED);
            p.setFullFilledAt(Instant.now());
            p.setRetryCount(0);
            p.setLastError(null);
            save(p);
            log.info("Filled & saved full application {}", p.getApplicationId());
        } catch (Exception e) {
            handleStepError(p, "full-application", e, config);
        }
    }

    /**
     * FULL_FILLED → upload the stored applicant documents (if enabled), then DOCS_UPLOADED. Uploads
     * are idempotent: an attachment already present on the application (same type + file name) is
     * skipped, so a retry after a partial upload does not create duplicates. With auto-upload
     * disabled the application stays FULL_FILLED and the user is notified + called to finish manually.
     */
    private void advanceFullFilled(PortalApplication p, CrawlerConfig config) {
        if (!config.isPortalAutoUploadDocuments()) {
            notifyFullReady(p, config);
            return;
        }
        try {
            JsonNode app = portal.getApplication(p.getApplicationId()).path("data").path("attributes");
            String personId = app.path("personId").asText(null);
            if (personId == null || personId.isBlank()) {
                throw new IllegalStateException("application " + p.getApplicationId() + " has no personId");
            }
            java.util.Set<String> present = existingAttachmentKeys(app.path("attachments"));
            // Also dedup against our own upload ledger. The portal rewrites an attachment's fileName to a
            // mangled CDN URL, so the portal-side check can miss our documents on a retry; the ledger is
            // keyed on what we actually sent, making re-upload idempotent regardless of the portal's naming.
            present.addAll(uploadedDocKeys(p));
            List<ApplicantDocument> docs = documentService.selectForApplication(config.documentMonthsWindowOrDefault());
            if (docs.isEmpty()) {
                throw new IllegalStateException("no applicant documents uploaded — add them in the dashboard");
            }
            int uploaded = 0;
            for (ApplicantDocument doc : docs) {
                String key = attachmentKey(doc.getDocType(), doc.getFileName());
                if (present.contains(key)) {
                    continue;
                }
                portal.uploadApplicationFile(
                        p.getApplicationId(),
                        personId,
                        doc.getDocType(),
                        doc.getFileName(),
                        doc.getContentType(),
                        doc.getContent());
                // Persist the ledger entry right away so if a later doc in this batch fails, the retry
                // skips the ones that already landed instead of uploading them a second time.
                recordUploadedDocKey(p, key);
                uploaded++;
            }
            p.setState(PortalApplicationState.DOCS_UPLOADED);
            p.setRetryCount(0);
            p.setLastError(null);
            save(p);
            log.info("Uploaded {} document(s) to application {} ({} already present)",
                    uploaded, p.getApplicationId(), docs.size() - uploaded);
        } catch (Exception e) {
            handleStepError(p, "document-upload", e, config);
        }
    }

    /**
     * DOCS_UPLOADED → finalize the application (if enabled), then FINALIZED. Finalize is irreversible,
     * so with auto-finalize disabled the application stays DOCS_UPLOADED and the user is notified to
     * review and submit. The finalize body shape is validated live on the first real full application.
     */
    private void advanceDocsUploaded(PortalApplication p, CrawlerConfig config) {
        if (!config.isPortalAutoFinalize()) {
            if (!p.isFullReadyNotified() && config.isNotificationsEnabled()) {
                notificationService.sendDocumentsUploaded(p.getResidenceAddress(), p.getApplicationId(), config);
                twilioCallService.triggerNewListingCall();
                p.setFullReadyNotified(true);
                save(p);
            }
            return;
        }
        try {
            JsonNode app = portal.getApplication(p.getApplicationId()).path("data").path("attributes");
            com.fasterxml.jackson.databind.node.ObjectNode finalizeBody = objectMapper.createObjectNode();
            finalizeBody.set("application", app.path("questionnaire"));
            // The portal now rejects finalize with 400 "Parameter confirm_finalization is required"
            // unless this explicit top-level confirmation flag is present (matches the SPA's submit).
            finalizeBody.put("confirm_finalization", true);
            portal.finalizeApplication(p.getApplicationId(), finalizeBody);
            p.setState(PortalApplicationState.FINALIZED);
            p.setFinalizedAt(Instant.now());
            p.setRetryCount(0);
            p.setLastError(null);
            save(p);
            log.info("Finalized application {}", p.getApplicationId());
            if (config.isNotificationsEnabled()) {
                notificationService.sendApplicationFinalized(p.getResidenceAddress(), config);
            }
        } catch (Exception e) {
            handleStepError(p, "finalize", e, config);
        }
    }

    private java.util.Set<String> existingAttachmentKeys(JsonNode attachments) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        if (attachments != null && attachments.isArray()) {
            for (JsonNode a : attachments) {
                String type = a.path("type").asText(null);
                String name = a.path("fileName").asText(null);
                // Portal stores fileName as a CDN URL; match on its basename against our file name.
                if (name != null) {
                    int slash = name.lastIndexOf('/');
                    if (slash >= 0) {
                        name = name.substring(slash + 1);
                    }
                }
                if (type != null && name != null) {
                    keys.add(attachmentKey(type, name));
                }
            }
        }
        return keys;
    }

    /** Our own record of the {@code type fileName} keys already uploaded for this application. */
    private static java.util.Set<String> uploadedDocKeys(PortalApplication p) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        String stored = p.getUploadedDocKeys();
        if (stored != null && !stored.isBlank()) {
            for (String k : stored.split("\n")) {
                if (!k.isBlank()) {
                    keys.add(k);
                }
            }
        }
        return keys;
    }

    /** Appends a freshly-uploaded key to the ledger and persists it immediately. */
    private void recordUploadedDocKey(PortalApplication p, String key) {
        java.util.Set<String> keys = uploadedDocKeys(p);
        if (keys.add(key)) {
            p.setUploadedDocKeys(String.join("\n", keys));
            save(p);
        }
    }

    private static String attachmentKey(String type, String fileName) {
        return type + "\0" + fileName;
    }

    /**
     * Advances states from the portal's authoritative status on the account's application list.
     *
     * <p>FINALIZED is included deliberately. It is <b>not</b> a terminal state: the portal can move an
     * application back to {@code awaiting_full_application} after it once reported
     * {@code full_application_completed}. That happened on 2026-07-14 — the portal wanted the full
     * application filled in, we had the row as FINALIZED and had stopped looking, and the invite email
     * that would have woken us was discarded as a duplicate of the tracked application. The result was
     * an application waiting on us that nobody was driving and nobody was told about. The portal is the
     * source of truth; our state reconciles toward it, never the other way round.
     */
    private void syncStatuses(CrawlerConfig config) {
        List<PortalApplication> tracked =
                repo.findByStateIn(
                        List.of(
                                PortalApplicationState.PRE_APP_SUBMITTED,
                                PortalApplicationState.AWAITING_FULL,
                                PortalApplicationState.FULL_FILLED,
                                PortalApplicationState.DOCS_UPLOADED,
                                PortalApplicationState.FINALIZED));
        if (tracked.isEmpty()) {
            return;
        }
        JsonNode list;
        try {
            list = portal.listApplications().path("data");
        } catch (Exception e) {
            log.warn("ReletPortal listApplications failed: {}", messageOf(e));
            return;
        }
        Map<String, JsonNode> byId = new HashMap<>();
        if (list.isArray()) {
            for (JsonNode item : list) {
                String id = item.path("attributes").path("application").path("id").asText(null);
                if (id == null) {
                    id = item.path("id").asText(null);
                }
                if (id != null) {
                    byId.put(id, item);
                }
            }
        }
        for (PortalApplication p : tracked) {
            JsonNode item = byId.get(p.getApplicationId());
            if (item == null) {
                continue;
            }
            String status =
                    item.path("attributes").path("application").path("status").asText(null);
            if (status == null) {
                continue;
            }
            p.setPortalStatus(status);
            if (p.getResidenceAddress() == null) {
                String addr = extractAddress(item.path("attributes").path("residence"));
                if (addr != null) {
                    p.setResidenceAddress(addr);
                }
            }
            if (STATUS_COMPLETED.equals(status)) {
                if (p.getState() != PortalApplicationState.FINALIZED) {
                    p.setState(PortalApplicationState.FINALIZED);
                    p.setFinalizedAt(Instant.now());
                    log.info("Application {} finalized by user", p.getApplicationId());
                }
            } else if (STATUS_AWAITING_FULL.equals(status)) {
                if (p.getState() == PortalApplicationState.PRE_APP_SUBMITTED) {
                    p.setState(PortalApplicationState.AWAITING_FULL);
                    log.info("Application {} now awaiting full application", p.getApplicationId());
                } else if (p.getState() == PortalApplicationState.FINALIZED) {
                    // The portal re-opened an application we thought was done. Drive it again, and
                    // tell the user — they are the ones who have to upload and submit, and they have
                    // no reason to suspect a finished application needs them a second time.
                    p.setState(PortalApplicationState.AWAITING_FULL);
                    p.setFinalizedAt(null);
                    p.setFullFilledAt(null);
                    p.setFullReadyNotified(false);
                    log.warn(
                            "Application {} was FINALIZED but the portal says {} — re-opening and driving it again",
                            p.getApplicationId(),
                            status);
                }
            }
            save(p);
        }
    }

    private void notifyFullReady(PortalApplication p, CrawlerConfig config) {
        if (p.isFullReadyNotified()) {
            return;
        }
        if (config.isNotificationsEnabled()) {
            notificationService.sendFullApplicationReady(
                    p.getResidenceAddress(), p.getApplicationId(), config);
            twilioCallService.triggerNewListingCall();
        }
        p.setFullReadyNotified(true);
        save(p);
    }

    // ---------------------------------------------------------------------------------------------
    // Payload construction
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds the {@code prospect-application} POST body — the SPA's {@code sendProspectApplication}
     * shape: {@code {invitationId, partnerSituation:false, partner:null, workSituation:{workSituation,
     * workMonthlySalary:0}}}. The monthly salary is deliberately 0 at this stage (the real figure is
     * collected in the full application). {@code workSituation} comes from the applicant profile.
     */
    ObjectNode buildProspectPayload(String invitationId, JsonNode profile) {
        String workSituation =
                profile.path("person").path("workSituation").path("workSituation").asText(null);
        ObjectNode ws = objectMapper.createObjectNode();
        ws.put("workSituation", workSituation);
        ws.put("workMonthlySalary", 0);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("invitationId", invitationId);
        payload.put("partnerSituation", false);
        payload.set("partner", objectMapper.nullNode());
        payload.set("workSituation", ws);
        return payload;
    }

    /**
     * Builds the {@code pre-application} POST body, mirroring the SPA's {@code sendApplication(o)}
     * shape: {@code {invitationId, application:{person, partnerSituation, partner,
     * currentHousingSituation, familyComposition, maritalState}}}. The {@code person} is the scaffold
     * the prospect step created, echoed back with the two fields the backend validates repaired: the
     * placeholder {@code dateOfBirth} nulled (DOB is captured at the full-application stage) and the
     * phone number normalized to E.164. The housing/family/marital answers come from {@code answers}
     * (the applicant profile). Only the single-applicant (no-partner) case is auto-filled.
     */
    ObjectNode buildPreApplicationPayload(String invitationId, JsonNode scaffoldPerson, JsonNode answers) {
        if (answers.path("partnerSituation").asBoolean(false)) {
            throw new IllegalStateException(
                    "partner pre-application requires manual handling (partnerSituation=true)");
        }
        ObjectNode person =
                (scaffoldPerson != null && scaffoldPerson.isObject())
                        ? scaffoldPerson.deepCopy()
                        : objectMapper.createObjectNode();
        ObjectNode pd =
                person.path("personalDetails").isObject()
                        ? (ObjectNode) person.get("personalDetails")
                        : person.putObject("personalDetails");
        pd.set("dateOfBirth", objectMapper.nullNode());
        String phone = normalizePhoneE164(pd.path("phoneNumber").asText(null));
        if (phone != null) {
            pd.put("phoneNumber", phone);
        }

        ObjectNode application = objectMapper.createObjectNode();
        application.set("person", person);
        application.put("partnerSituation", false);
        application.set("partner", objectMapper.nullNode());
        application.set("currentHousingSituation", nodeOrNull(answers, "currentHousingSituation"));
        application.set("familyComposition", nodeOrNull(answers, "familyComposition"));
        application.set("maritalState", nodeOrNull(answers, "maritalState"));

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("invitationId", invitationId);
        payload.set("application", application);
        return payload;
    }

    /**
     * Normalizes a Dutch number to E.164 ({@code +31…}), which the ReletPortal pre-application
     * endpoint requires — the opposite of roofz.eu's apply form, which wants the local {@code 06…} form.
     */
    static String normalizePhoneE164(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        String p = phone.trim().replaceAll("[\\s()-]", "");
        if (p.startsWith("+")) {
            return p;
        }
        if (p.startsWith("0031")) {
            return "+" + p.substring(2);
        }
        if (p.startsWith("0")) {
            return "+31" + p.substring(1);
        }
        return p;
    }

    private JsonNode nodeOrNull(JsonNode parent, String field) {
        JsonNode n = parent.get(field);
        return (n == null || n.isMissingNode()) ? objectMapper.nullNode() : n;
    }

    /** Recursively overlays {@code source} onto {@code target}; source wins on conflicts. */
    static JsonNode deepMerge(JsonNode target, JsonNode source) {
        if (!(target instanceof ObjectNode t) || !(source instanceof ObjectNode)) {
            return source;
        }
        source.fields()
                .forEachRemaining(
                        e -> {
                            JsonNode tv = t.get(e.getKey());
                            JsonNode sv = e.getValue();
                            if (tv != null && tv.isObject() && sv.isObject()) {
                                t.set(e.getKey(), deepMerge(tv, sv));
                            } else {
                                t.set(e.getKey(), sv);
                            }
                        });
        return t;
    }

    private String profileJson() {
        ApplicantProfile profile = profileRepo.findById(1L).orElse(null);
        if (profile == null) {
            throw new IllegalStateException(
                    "No applicant profile yet: fill it in on the Documents page");
        }
        if (profile.getQuestionnaireJson() == null || profile.getQuestionnaireJson().isBlank()) {
            throw new IllegalStateException("ApplicantProfile questionnaire is empty");
        }
        return profile.getQuestionnaireJson();
    }

    private static String extractAddress(JsonNode holder) {
        JsonNode a = holder.path("residence").path("address");
        if (a.isMissingNode() || a.isNull()) {
            a = holder.path("address");
        }
        if (a.isMissingNode() || a.isNull() || a.isEmpty()) {
            return null;
        }
        String street = firstNonBlank(a.path("streetName").asText(null), a.path("street").asText(null));
        String number = a.path("houseNumber").asText("");
        String ext = firstNonBlank(a.path("houseNumberExtension").asText(null), "");
        String city = firstNonBlank(a.path("cityName").asText(null), a.path("city").asText(null));
        String line = (street == null ? "" : street) + " " + number + (ext == null ? "" : ext);
        if (city != null) {
            line += ", " + city;
        }
        line = line.trim();
        return line.isBlank() ? null : line;
    }

    // ---------------------------------------------------------------------------------------------
    // Error handling & helpers
    // ---------------------------------------------------------------------------------------------

    private void handleStepError(PortalApplication p, String step, Exception e, CrawlerConfig config) {
        p.setRetryCount(p.getRetryCount() + 1);
        p.setLastError(truncate(step + ": " + messageOf(e)));
        log.error("Portal pipeline {} failed for {} (attempt {}/{})",
                step, refOf(p), p.getRetryCount(), MAX_RETRIES, e);
        if (p.getRetryCount() >= MAX_RETRIES && p.getState() != PortalApplicationState.ERROR) {
            p.setState(PortalApplicationState.ERROR);
            if (config.isNotificationsEnabled()) {
                notificationService.sendPipelineError(step + " (" + refOf(p) + ")", messageOf(e), config);
            }
        }
        save(p);
    }

    private void save(PortalApplication p) {
        p.touch();
        repo.save(p);
    }

    private static String refOf(PortalApplication p) {
        return p.getApplicationId() != null ? p.getApplicationId() : p.getInvitationId();
    }

    private String pretty(JsonNode node) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return String.valueOf(node);
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static String messageOf(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > MAX_ERROR_LEN ? s.substring(0, MAX_ERROR_LEN) + "…" : s;
    }
}

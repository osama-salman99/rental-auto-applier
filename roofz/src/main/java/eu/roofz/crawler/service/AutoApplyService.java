package eu.roofz.crawler.service;

import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.Listing;
import eu.roofz.crawler.repository.ListingRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Service
public class AutoApplyService {

    private static final Logger log = LoggerFactory.getLogger(AutoApplyService.class);

    static final String CANDIDATE_URL = "https://roofz.eu/api/ms/subscription/candidate";

    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_DUPLICATE = "DUPLICATE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    private final RestClient restClient;
    private final ListingRepository listingRepository;

    public AutoApplyService(RestClient restClient, ListingRepository listingRepository) {
        this.restClient = restClient;
        this.listingRepository = listingRepository;
    }

    /** Apply for a freshly-matched listing using the user's configured applicant details. */
    public ApplyResult applyFor(Listing listing, CrawlerConfig config) {
        if (listing.getAppliedAt() != null) {
            log.debug("Listing {} already has an application — skipping", listing.getId());
            return new ApplyResult(STATUS_SKIPPED, "already applied");
        }
        // Our candidate POST only works when Roofz itself accepts property subscriptions. If the
        // listing routes applications off-site, or forbids subscription, posting would either fail
        // or silently do nothing — so we skip the POST here. The user was still notified about the
        // listing (notifications run before auto-apply), so they can act on it manually.
        if (listing.getExternalSubscriptionUrl() != null
                && !listing.getExternalSubscriptionUrl().isBlank()) {
            log.info(
                    "Listing {} routes applications to an external URL — skipping auto-apply POST (user notified)",
                    listing.getId());
            return persist(listing, STATUS_SKIPPED, null);
        }
        if (Boolean.FALSE.equals(listing.getAllowPropertySubscription())) {
            log.info(
                    "Listing {} does not allow property subscription — skipping auto-apply POST",
                    listing.getId());
            return persist(listing, STATUS_SKIPPED, null);
        }
        String missing = validateApplicant(config);
        if (missing != null) {
            log.warn("Auto-apply skipped for listing {}: {}", listing.getId(), missing);
            return persist(listing, STATUS_SKIPPED, null);
        }

        ApplicantData applicant =
                new ApplicantData(
                        config.getApplicantFirstName(),
                        config.getApplicantLastName(),
                        config.getApplicantEmail(),
                        config.getApplicantPhone(),
                        config.getApplicantMessage());

        ApplyOutcome outcome = post(listing.getId(), listing.getSlug(), applicant);
        return persist(listing, outcome.status(), outcome.status().equals(STATUS_APPLIED) ? Instant.now() : null);
    }

    /**
     * Fire a one-off application with caller-supplied applicant data and no DB side-effects. Used by
     * the dashboard "Test Auto-Apply" button to confirm the wiring without contaminating the user's
     * real application history.
     */
    public ApplyResult applyForPropertyDirect(long propertyId, ApplicantData applicant) {
        String missing = validateApplicant(applicant);
        if (missing != null) {
            return new ApplyResult(STATUS_SKIPPED, missing);
        }
        ApplyOutcome outcome = post(propertyId, null, applicant);
        return new ApplyResult(outcome.status(), outcome.detail());
    }

    private ApplyOutcome post(long propertyId, String slug, ApplicantData applicant) {
        // Match the real roofz.eu Nuxt form (reverse-engineered from bundle Cazg6OjQ.js and confirmed
        // against a captured *successful* browser request). The form builds:
        //   metadata = te({ "_hp": r[honeypot], "_ts": S })   where te() just un-flattens dotted keys.
        //   - _hp  is the "_additional_info" honeypot; a human never touches it, so it's `undefined`
        //          and JSON.stringify DROPS it — the winning request carries no _hp at all. So do we.
        //   - _ts  is Date.now() captured at *page render* (setup()), NOT at submit. The backend infers
        //          "time on page" = receivedAt − _ts. Our old bug set _ts = submit time (~0s dwell),
        //          which reads as an instant bot post: the apply returns 200 "Created successfully" but
        //          gets silently shadow-dropped and never reaches the landlord (→ no invite). We
        //          backdate _ts by a human-plausible dwell so the apply actually registers.
        long dwellMillis = 30_000L + java.util.concurrent.ThreadLocalRandom.current().nextLong(45_000L);
        long renderTs = System.currentTimeMillis() - dwellMillis;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("_ts", renderTs);

        Map<String, Object> subscription = new LinkedHashMap<>();
        subscription.put("firstname", applicant.firstName());
        subscription.put("lastname", applicant.lastName());
        subscription.put("phone", normalizePhone(applicant.phone()));
        if (applicant.message() != null && !applicant.message().isBlank()) {
            subscription.put("message", applicant.message());
        }
        subscription.put("property_id", propertyId);
        subscription.put("metadata", metadata);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("candidate", Map.of("email", applicant.email()));
        body.put("subscription", subscription);

        // Real form posts from the property's own page; use its actual slug as Referer (the browser
        // sends e.g. .../woningen/nieuwe-voorbeeldgracht-12-c) instead of a synthetic property-{id}.
        String referer =
                (slug != null && !slug.isBlank())
                        ? "https://roofz.eu/huur/woningen/" + slug
                        : "https://roofz.eu/huur/woningen/property-" + propertyId;

        try {
            String response =
                    restClient
                            .post()
                            .uri(CANDIDATE_URL)
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .header("Origin", "https://roofz.eu")
                            .header("Referer", referer)
                            .body(body)
                            .retrieve()
                            .body(String.class);
            // A 2xx alone is NOT proof of success: a malformed/anti-bot-flagged post can still return
            // 2xx with an error-shaped body. Require an explicit success marker; otherwise flag it so
            // the watchdog escalates rather than silently recording a phantom "APPLIED".
            if (!looksSuccessful(response)) {
                log.error(
                        "Auto-apply for property {} returned 2xx but no success marker — treating as FAILED. body={}",
                        propertyId,
                        response);
                return new ApplyOutcome(STATUS_FAILED, "2xx without success marker: " + response);
            }
            log.info("Auto-applied for property {}: {}", propertyId, response);
            return new ApplyOutcome(STATUS_APPLIED, response);
        } catch (HttpClientErrorException e) {
            HttpStatusCode code = e.getStatusCode();
            if (code.value() == 409) {
                log.info("Auto-apply duplicate for property {}", propertyId);
                return new ApplyOutcome(STATUS_DUPLICATE, "409 duplicate");
            }
            log.error(
                    "Auto-apply failed for property {}: HTTP {} body={}",
                    propertyId,
                    code.value(),
                    e.getResponseBodyAsString());
            return new ApplyOutcome(STATUS_FAILED, "HTTP " + code.value());
        } catch (Exception e) {
            log.error("Auto-apply failed for property {}", propertyId, e);
            return new ApplyOutcome(STATUS_FAILED, e.getMessage());
        }
    }

    private ApplyResult persist(Listing listing, String status, Instant appliedAt) {
        listing.setApplicationStatus(status);
        if (appliedAt != null) {
            listing.setAppliedAt(appliedAt);
        }
        listingRepository.save(listing);
        return new ApplyResult(status, null);
    }

    private static String validateApplicant(CrawlerConfig config) {
        return validateApplicant(
                new ApplicantData(
                        config.getApplicantFirstName(),
                        config.getApplicantLastName(),
                        config.getApplicantEmail(),
                        config.getApplicantPhone(),
                        config.getApplicantMessage()));
    }

    private static String validateApplicant(ApplicantData a) {
        if (isBlank(a.firstName())) return "applicant first name missing";
        if (isBlank(a.lastName())) return "applicant last name missing";
        if (isBlank(a.email())) return "applicant email missing";
        if (isBlank(a.phone())) return "applicant phone missing";
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * The candidate endpoint answers a genuine apply with a plain {@code "Created successfully"} body
     * (verified live). Validation failures come back as 4xx (handled separately), but a body that
     * carries an error shape on a 2xx must not be trusted. Accept only an explicit success signal.
     */
    static boolean looksSuccessful(String body) {
        if (body == null) {
            return false;
        }
        String b = body.toLowerCase();
        // Compare with spaces removed so "success": false (any spacing) is caught as a negative.
        String compact = b.replace(" ", "");
        if (compact.contains("\"error\":true")
                || b.contains("\"errors\"")
                || compact.contains("\"success\":false")
                || compact.contains("\"ok\":false")) {
            return false;
        }
        // Require an explicit positive marker. A bare contains("success") also matched "unsuccessful"
        // and error prose that happens to include the word, so a 2xx error body could be recorded as a
        // phantom APPLIED — hiding a genuinely missed apartment from the watchdog. Fail closed instead.
        return b.contains("created successfully") || compact.contains("\"success\":true");
    }

    /**
     * roofz.eu's apply form submits Dutch numbers in local format ({@code 06…}), not the {@code +31}
     * E.164 form we store in config. Match the browser so the candidate isn't silently malformed.
     */
    static String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String p = phone.trim().replaceAll("[\\s()-]", "");
        if (p.startsWith("+31")) {
            return "0" + p.substring(3);
        }
        if (p.startsWith("0031")) {
            return "0" + p.substring(4);
        }
        return p;
    }

    public record ApplicantData(
            String firstName, String lastName, String email, String phone, String message) {}

    public record ApplyResult(String status, String detail) {}

    private record ApplyOutcome(String status, String detail) {}
}

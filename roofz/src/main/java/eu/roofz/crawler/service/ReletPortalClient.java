package eu.roofz.crawler.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Thin client for the ReletPortal (roofz.onosre.com) microservices reverse-engineered from the
 * Angular bundle. Handles session login (accountId + email + password → JWT), caches the token and
 * transparently re-logs in on expiry/401, and exposes the handful of endpoints the application
 * pipeline needs.
 *
 * <p>All calls carry {@code Authorization: Bearer <session JWT>} — the same single-interceptor
 * scheme the SPA uses. Responses are JSON:API shaped ({@code {data, meta}}); we return the raw
 * {@link JsonNode} and let callers navigate.
 */
@Service
public class ReletPortalClient {

    private static final Logger log = LoggerFactory.getLogger(ReletPortalClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${reletportal.email:}")
    private String email;

    @Value("${reletportal.password:}")
    private String password;

    @Value("${reletportal.account-id:4def8fb7-ab71-49e4-b3da-77ece7f4c236}")
    private String accountId;

    @Value("${reletportal.domain-host:roofz.onosre.com}")
    private String domainHost;

    @Value("${reletportal.base.entree:https://entree.portal.prd.osre.eu}")
    private String entreeBase;

    @Value("${reletportal.base.authentication:https://authentication.portal.prd.osre.eu}")
    private String authenticationBase;

    @Value("${reletportal.base.relet:https://relet.portal.prd.osre.eu}")
    private String reletBase;

    @Value("${reletportal.base.file:https://file.portal.prd.osre.eu}")
    private String fileBase;

    private volatile String cachedToken;

    public ReletPortalClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return notBlank(email) && notBlank(password);
    }

    // ---------------------------------------------------------------------------------------------
    // Endpoints
    // ---------------------------------------------------------------------------------------------

    /** GET /portal/applications — the account's applications (list-item summaries). */
    public JsonNode listApplications() {
        return get(reletBase + "/portal/applications");
    }

    /** GET /portal/applications/{id} — full application detail. */
    public JsonNode getApplication(String applicationId) {
        return get(reletBase + "/portal/applications/" + applicationId);
    }

    /** GET /portal/invitations/{invitationId} — the pre-application invitation from an email link. */
    public JsonNode getInvitation(String invitationId) {
        return get(reletBase + "/portal/invitations/" + invitationId);
    }

    /**
     * GET /portal/applications/{id}/{magicToken} — the SPA's {@code getApplicationById(id, token)}
     * used during the (pre-login) invitation flow. Returns the stub application whose
     * {@code questionnaire} seeds the pre-application payload.
     */
    public JsonNode getApplicationByMagicToken(String applicationId, String magicToken) {
        return get(reletBase + "/portal/applications/" + applicationId + "/" + magicToken);
    }

    /** POST /portal/applications/pre-application — submit the pre-application questionnaire. */
    public JsonNode submitPreApplication(Object payload) {
        return post(reletBase + "/portal/applications/pre-application", payload);
    }

    /** POST /portal/applications/prospect-application — partner-less pre-application variant. */
    public JsonNode submitProspectApplication(Object payload) {
        return post(reletBase + "/portal/applications/prospect-application", payload);
    }

    /**
     * POST /portal/applications/{id}/files — upload one document. Mirrors the SPA's
     * {@code saveApplicationFile}: a multipart body with parts {@code fileUpload} (the bytes),
     * {@code personId} and {@code type} (the portal attachment type, e.g. {@code salarySlip}).
     */
    public JsonNode uploadApplicationFile(
            String applicationId,
            String personId,
            String type,
            String fileName,
            String contentType,
            byte[] bytes) {
        ByteArrayResource filePart =
                new ByteArrayResource(bytes) {
                    @Override
                    public String getFilename() {
                        return fileName;
                    }
                };
        HttpHeaders fileHeaders = new HttpHeaders();
        if (contentType != null && !contentType.isBlank()) {
            fileHeaders.setContentType(MediaType.parseMediaType(contentType));
        }
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("fileUpload", new HttpEntity<>(filePart, fileHeaders));
        parts.add("personId", personId);
        parts.add("type", type);

        return exchange(() ->
                restClient
                        .post()
                        .uri(reletBase + "/portal/applications/" + applicationId + "/files")
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(parts)
                        .retrieve()
                        .body(JsonNode.class));
    }

    /**
     * GET file/files/download?fileIds[]=… — downloads the given files as a ZIP archive (the SPA's
     * {@code getFilesArchive}). Used to re-import documents from a previous application, since the
     * portal has no per-file download. Entries are named {@code {type}-{attachmentId}-{fileId8}.{ext}}.
     */
    public byte[] downloadFilesArchive(java.util.List<String> fileIds) {
        StringBuilder url = new StringBuilder(fileBase).append("/files/download?");
        for (int i = 0; i < fileIds.size(); i++) {
            if (i > 0) {
                url.append('&');
            }
            url.append("fileIds%5B%5D=").append(fileIds.get(i));
        }
        // Pass a pre-encoded URI so RestClient does not re-encode the %5B%5D ("[]") of fileIds[].
        java.net.URI uri = java.net.URI.create(url.toString());
        return exchangeBytes(() ->
                restClient
                        .get()
                        .uri(uri)
                        .header("Authorization", "Bearer " + token())
                        .accept(MediaType.APPLICATION_OCTET_STREAM, MediaType.valueOf("application/zip"))
                        .retrieve()
                        .body(byte[].class));
    }

    /**
     * PUT /portal/applications/{id}/finalize — submit the completed application. The SPA passes its
     * application object straight through; the exact body is validated live on the first real full
     * application, so it is taken as a caller-supplied {@link JsonNode}.
     */
    public JsonNode finalizeApplication(String applicationId, JsonNode body) {
        return put(reletBase + "/portal/applications/" + applicationId + "/finalize", body);
    }

    /**
     * PUT /portal/applications/{id} — save (not finalize) the full application questionnaire. Body
     * mirrors the SPA's {@code updateApplication}:
     * {@code {application:{questionnaire, verifiedQuestionnaire:null}, hasPreApplication:null, applicationProgress:null}}.
     */
    public JsonNode updateFullApplication(String applicationId, JsonNode questionnaire) {
        Map<String, Object> application = new LinkedHashMap<>();
        application.put("questionnaire", questionnaire);
        application.put("verifiedQuestionnaire", null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("application", application);
        body.put("hasPreApplication", null);
        body.put("applicationProgress", null);

        return put(reletBase + "/portal/applications/" + applicationId, body);
    }

    // ---------------------------------------------------------------------------------------------
    // Auth
    // ---------------------------------------------------------------------------------------------

    /** Returns a valid session token, logging in if none is cached or the cached one has expired. */
    String token() {
        String t = cachedToken;
        if (t != null && !isExpired(t)) {
            return t;
        }
        synchronized (this) {
            if (cachedToken != null && !isExpired(cachedToken)) {
                return cachedToken;
            }
            cachedToken = login();
            return cachedToken;
        }
    }

    private String login() {
        if (!isConfigured()) {
            throw new IllegalStateException("ReletPortal credentials not configured");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", resolveAccountId());
        body.put("email", email);
        body.put("password", password);

        JsonNode resp =
                objectMapper.valueToTree(
                        restClient
                                .post()
                                .uri(entreeBase + "/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(body)
                                .retrieve()
                                .body(JsonNode.class));
        String session = resp.path("data").path("attributes").path("session").asText(null);
        if (session == null || session.isBlank()) {
            throw new IllegalStateException("ReletPortal login returned no session token");
        }
        log.info("ReletPortal login succeeded");
        return session;
    }

    /**
     * Uses the configured accountId, or resolves it from the account-settings endpoint by domain
     * host when unset. Cached implicitly via the {@code accountId} field.
     */
    private String resolveAccountId() {
        if (notBlank(accountId)) {
            return accountId;
        }
        JsonNode settings =
                restClient
                        .get()
                        .uri(authenticationBase + "/accounts/domains/" + domainHost + "/settings")
                        .retrieve()
                        .body(JsonNode.class);
        String resolved =
                settings == null
                        ? null
                        : settings.path("data").path("attributes").path("accountId").asText(null);
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalStateException("Could not resolve ReletPortal accountId for " + domainHost);
        }
        this.accountId = resolved;
        return resolved;
    }

    /** True when the JWT's {@code exp} is within 30s of now (or the token is unparseable). */
    private boolean isExpired(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return true;
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode claims = objectMapper.readTree(new String(payload, StandardCharsets.UTF_8));
            double exp = claims.path("exp").asDouble(0);
            return exp <= (System.currentTimeMillis() / 1000.0) + 30;
        } catch (Exception e) {
            return true;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // HTTP helpers (with one transparent re-login + retry on 401)
    // ---------------------------------------------------------------------------------------------

    private JsonNode get(String url) {
        return exchange(() ->
                restClient
                        .get()
                        .uri(url)
                        .header("Authorization", "Bearer " + token())
                        .retrieve()
                        .body(JsonNode.class));
    }

    private JsonNode post(String url, Object body) {
        return exchange(() ->
                restClient
                        .post()
                        .uri(url)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class));
    }

    private JsonNode put(String url, Object body) {
        return exchange(() ->
                restClient
                        .put()
                        .uri(url)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class));
    }

    private JsonNode exchange(java.util.function.Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException e) {
            HttpStatusCode code = e.getStatusCode();
            if (code.value() == 401) {
                log.info("ReletPortal 401 — refreshing session and retrying once");
                cachedToken = null;
                return call.get();
            }
            throw e;
        }
    }

    private byte[] exchangeBytes(java.util.function.Supplier<byte[]> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401) {
                log.info("ReletPortal 401 — refreshing session and retrying once");
                cachedToken = null;
                return call.get();
            }
            throw e;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}

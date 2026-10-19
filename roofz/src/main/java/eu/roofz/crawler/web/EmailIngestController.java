package eu.roofz.crawler.web;

import eu.roofz.crawler.service.EmailIngestService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives the raw emails sent to the crawler's inbound address, POSTed here by whatever forwards
 * them (e.g. a Cloudflare Email Worker). Guarded by a shared secret ({@code X-Ingest-Token}); the
 * path is public (CSRF-exempt) but useless without the secret. Returns 200 with a short outcome so
 * the forwarder can log delivery.
 */
@RestController
public class EmailIngestController {

    private static final Logger log = LoggerFactory.getLogger(EmailIngestController.class);

    private final EmailIngestService emailIngestService;

    @Value("${ingest.secret:}")
    private String ingestSecret;

    public EmailIngestController(EmailIngestService emailIngestService) {
        this.emailIngestService = emailIngestService;
    }

    @PostMapping("/portal/ingest-email")
    public ResponseEntity<String> ingest(
            @RequestHeader(value = "X-Ingest-Token", required = false) String token,
            @RequestBody(required = false) byte[] raw) {
        if (ingestSecret == null || ingestSecret.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("ingest not configured");
        }
        if (!constantTimeEquals(ingestSecret, token)) {
            log.warn("Rejected ingest-email with bad/missing token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("unauthorized");
        }
        if (raw == null || raw.length == 0) {
            return ResponseEntity.badRequest().body("empty body");
        }
        try {
            String result = emailIngestService.handle(raw);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Email ingest failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("error: " + e.getMessage());
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}

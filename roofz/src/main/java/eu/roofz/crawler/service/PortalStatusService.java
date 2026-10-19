package eu.roofz.crawler.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Cross-references the crawler's applied listings with the live ReletPortal applications, so the
 * dashboard can show — per listing — whether an apply progressed to the portal ("got the invite") or
 * not. The lookup is keyed on a normalised address so a listing title ("Nieuwe Voorbeeldgracht 12 C")
 * matches the portal's structured residence. Results are cached briefly and every failure falls back
 * to the last good snapshot, so a slow/down portal never breaks the dashboard.
 */
@Service
public class PortalStatusService {

    private static final Logger log = LoggerFactory.getLogger(PortalStatusService.class);
    private static final Duration TTL = Duration.ofSeconds(60);

    private final ReletPortalClient portal;

    private volatile Map<String, String> cache = Map.of();
    private volatile Instant cachedAt = Instant.EPOCH;

    public PortalStatusService(ReletPortalClient portal) {
        this.portal = portal;
    }

    /** Portal application status for a listing title, or null if it never reached the portal. */
    public String statusForTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        return snapshot().get(normalize(title));
    }

    private Map<String, String> snapshot() {
        if (Duration.between(cachedAt, Instant.now()).compareTo(TTL) < 0) {
            return cache;
        }
        if (!portal.isConfigured()) {
            return cache;
        }
        try {
            JsonNode data = portal.listApplications().path("data");
            Map<String, String> m = new HashMap<>();
            if (data.isArray()) {
                for (JsonNode it : data) {
                    JsonNode attrs = it.path("attributes");
                    String status = attrs.path("application").path("status").asText(null);
                    JsonNode addr = attrs.path("residence").path("address");
                    String key =
                            normalize(
                                    addr.path("streetName").asText("")
                                            + addr.path("houseNumber").asText("")
                                            + addr.path("houseNumberExtension").asText(""));
                    if (status != null && !key.isBlank()) {
                        m.put(key, status);
                    }
                }
            }
            cache = m;
            cachedAt = Instant.now();
        } catch (Exception e) {
            log.warn("Could not refresh portal status snapshot: {} — using stale", e.getMessage());
            cachedAt = Instant.now(); // avoid hammering a down portal every request
        }
        return cache;
    }

    /** Lowercase and strip everything but letters/digits so spacing/punctuation differences match. */
    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}

package eu.vesteda.crawler.service;

import eu.osmosis.crawler.core.notify.NtfyClient;
import eu.vesteda.crawler.model.CrawlerConfig;
import eu.vesteda.crawler.model.Listing;
import org.springframework.stereotype.Service;

/**
 * Formats Vesteda listings into ntfy notifications. The transport — utf-8 body charset and ASCII
 * Title sanitization — lives once in {@link NtfyClient} (crawler-core). Routing through it also
 * fixes a latent bug: this crawler used to POST the raw title as the ntfy Title header, so a Dutch
 * diacritic or a "→" in a status change would throw and silently drop the whole notification.
 */
@Service
public class NotificationService {

    private final NtfyClient ntfy;

    public NotificationService(NtfyClient ntfy) {
        this.ntfy = ntfy;
    }

    public boolean sendNewListingNotification(Listing listing, CrawlerConfig config) {
        return sendListing(listing, config, "[Vesteda] New: " + listing.getTitle());
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
        sendCrawlerStatusNotification(
                "[Vesteda] " + listings.size() + " new homes", body.toString().trim(), "house", config);
        return true;
    }

    public void sendStatusChangeNotification(Listing listing, String oldStage, CrawlerConfig config) {
        sendListing(
                listing,
                config,
                "[Vesteda] " + listing.getTitle() + ": " + oldStage + " → " + listing.getStage());
    }

    public void sendCrawlerStatusNotification(
            String title, String message, String tag, CrawlerConfig config) {
        ntfy.send(config.getNtfyServerUrl(), config.getNtfyTopic(), title, message, null, tag);
    }

    private boolean sendListing(Listing listing, CrawlerConfig config, String title) {
        String clickUrl = VestedaApiClient.BASE_URL + listing.getSlug();
        return ntfy.send(
                config.getNtfyServerUrl(),
                config.getNtfyTopic(),
                title,
                formatDetails(listing),
                clickUrl,
                "house");
    }

    private String formatDetails(Listing l) {
        StringBuilder sb = new StringBuilder();
        if (l.getCity() != null) sb.append(l.getCity());
        if (l.getLivingArea() != null) sb.append(" • ").append(l.getLivingArea()).append(" m²");
        if (l.getBedrooms() != null) sb.append(" • ").append(l.getBedrooms()).append(" bed");
        if (l.getPrice() != null) sb.append(" • €").append(l.getPrice()).append("/mo");
        sb.append("\nStatus: ").append(l.getStage());
        return sb.toString();
    }
}

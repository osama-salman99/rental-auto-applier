package eu.ikwilhuren.crawler.service;

import eu.ikwilhuren.crawler.model.CrawlerConfig;
import eu.ikwilhuren.crawler.model.Listing;
import eu.osmosis.crawler.core.notify.NtfyClient;
import org.springframework.stereotype.Service;

/**
 * Formats IkWilHuren listings into ntfy notifications. The transport — utf-8 body charset and ASCII
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
        return sendListing(listing, config, "[IkWilHuren] New: " + listing.getTitle());
    }

    public void sendStatusChangeNotification(Listing listing, String oldStage, CrawlerConfig config) {
        sendListing(
                listing,
                config,
                "[IkWilHuren] " + listing.getTitle() + ": " + oldStage + " → " + listing.getStage());
    }

    public void sendCrawlerStatusNotification(
            String title, String message, String tag, CrawlerConfig config) {
        ntfy.send(config.getNtfyServerUrl(), config.getNtfyTopic(), title, message, null, tag);
    }

    private boolean sendListing(Listing listing, CrawlerConfig config, String title) {
        String clickUrl = "https://ikwilhuren.nu/object/" + listing.getSlug() + "/";
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

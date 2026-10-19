package eu.holland2stay.crawler.service;

import eu.osmosis.crawler.core.notify.NtfyClient;
import eu.holland2stay.crawler.model.CrawlerConfig;
import eu.holland2stay.crawler.model.Listing;
import org.springframework.stereotype.Service;

/**
 * Formats [Holland2Stay] listings into ntfy notifications. The transport — utf-8 body charset and ASCII
 * Title sanitization — lives once in {@link NtfyClient} (crawler-core); this class only decides
 * what each notification says.
 */
@Service
public class NotificationService {

    private final NtfyClient ntfy;

    public NotificationService(NtfyClient ntfy) {
        this.ntfy = ntfy;
    }

    public boolean sendNewListingNotification(Listing listing, CrawlerConfig config) {
        return sendListing(listing, config, "[Holland2Stay] New: " + listing.getTitle());
    }

    public void sendStatusChangeNotification(Listing listing, String oldStage, CrawlerConfig config) {
        sendListing(
                listing,
                config,
                "[Holland2Stay] " + listing.getTitle() + ": " + oldStage + " → " + listing.getStage());
    }

    public void sendCrawlerStatusNotification(
            String title, String message, String tag, CrawlerConfig config) {
        ntfy.send(config.getNtfyServerUrl(), config.getNtfyTopic(), title, message, null, tag);
    }

    private boolean sendListing(Listing listing, CrawlerConfig config, String title) {
        String clickUrl =
                listing.getSlug() != null
                        ? Holland2stayClient.BASE_URL + listing.getSlug()
                        : Holland2stayClient.BASE_URL;
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

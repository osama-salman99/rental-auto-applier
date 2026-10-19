package eu.vbt.crawler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "crawler_config")
public class CrawlerConfig {

    @Id private Long id = 1L;

    private Integer minSquareMeters;
    private Integer minBedrooms;
    private Integer maxPrice;
    private String targetCity = "Amsterdam";
    private boolean onlyAvailable = true;
    // No default: ntfy.sh topics are public, so a name written here would let anyone read the alerts.
    // Set on the settings page; until then no alerts are sent.
    private String ntfyTopic;
    private String ntfyServerUrl = "https://ntfy.sh";
    private boolean notificationsEnabled = true;

    public CrawlerConfig() {}

    public static CrawlerConfig defaults() {
        return new CrawlerConfig();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getMinSquareMeters() { return minSquareMeters; }
    public void setMinSquareMeters(Integer minSquareMeters) { this.minSquareMeters = minSquareMeters; }

    public Integer getMinBedrooms() { return minBedrooms; }
    public void setMinBedrooms(Integer minBedrooms) { this.minBedrooms = minBedrooms; }

    public Integer getMaxPrice() { return maxPrice; }
    public void setMaxPrice(Integer maxPrice) { this.maxPrice = maxPrice; }

    public String getTargetCity() { return targetCity; }
    public void setTargetCity(String targetCity) { this.targetCity = targetCity; }

    public boolean isOnlyAvailable() { return onlyAvailable; }
    public void setOnlyAvailable(boolean onlyAvailable) { this.onlyAvailable = onlyAvailable; }

    public String getNtfyTopic() { return ntfyTopic; }
    public void setNtfyTopic(String ntfyTopic) { this.ntfyTopic = ntfyTopic; }

    public String getNtfyServerUrl() { return ntfyServerUrl; }
    public void setNtfyServerUrl(String ntfyServerUrl) { this.ntfyServerUrl = ntfyServerUrl; }

    public boolean isNotificationsEnabled() { return notificationsEnabled; }
    public void setNotificationsEnabled(boolean notificationsEnabled) { this.notificationsEnabled = notificationsEnabled; }
}

package eu.roofz.crawler.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "listings")
public class Listing {

    @Id private Long id;

    private String slug;
    private String title;
    private String city;
    private Integer livingArea;
    private Integer bedrooms;
    private Integer price;
    private Integer serviceCosts;
    private String stage;
    private String imageUrl;
    private Instant firstSeenAt;
    private boolean notificationSent;
    private Instant appliedAt;
    private String applicationStatus;

    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean inviteWatchdogAlerted;
    private Instant availableAt;
    private Instant publishedAt;
    private String subtype;
    private Instant optionAt;
    private Instant occupiedAt;

    /** status.code from the API — the true availability signal, which can disagree with {@link #stage}. */
    private String statusCode;

    /** Set when applications are handled off-site; auto-apply skips these (our candidate POST won't apply). */
    private String externalSubscriptionUrl;

    /** False = this property cannot be subscribed to; auto-apply must not POST to it. Null = unknown. */
    private Boolean allowPropertySubscription;

    public Listing() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public Integer getLivingArea() {
        return livingArea;
    }

    public void setLivingArea(Integer livingArea) {
        this.livingArea = livingArea;
    }

    public Integer getBedrooms() {
        return bedrooms;
    }

    public void setBedrooms(Integer bedrooms) {
        this.bedrooms = bedrooms;
    }

    public Integer getPrice() {
        return price;
    }

    public void setPrice(Integer price) {
        this.price = price;
    }

    public Integer getServiceCosts() {
        return serviceCosts;
    }

    public void setServiceCosts(Integer serviceCosts) {
        this.serviceCosts = serviceCosts;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public void setFirstSeenAt(Instant firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public boolean isNotificationSent() {
        return notificationSent;
    }

    public void setNotificationSent(boolean notificationSent) {
        this.notificationSent = notificationSent;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(Instant appliedAt) {
        this.appliedAt = appliedAt;
    }

    public String getApplicationStatus() {
        return applicationStatus;
    }

    public void setApplicationStatus(String applicationStatus) {
        this.applicationStatus = applicationStatus;
    }

    public boolean isInviteWatchdogAlerted() {
        return inviteWatchdogAlerted;
    }

    public void setInviteWatchdogAlerted(boolean inviteWatchdogAlerted) {
        this.inviteWatchdogAlerted = inviteWatchdogAlerted;
    }

    public Instant getAvailableAt() { return availableAt; }
    public void setAvailableAt(Instant availableAt) { this.availableAt = availableAt; }

    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }

    public String getSubtype() { return subtype; }
    public void setSubtype(String subtype) { this.subtype = subtype; }

    public Instant getOptionAt() { return optionAt; }
    public void setOptionAt(Instant optionAt) { this.optionAt = optionAt; }

    public Instant getOccupiedAt() { return occupiedAt; }
    public void setOccupiedAt(Instant occupiedAt) { this.occupiedAt = occupiedAt; }

    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }

    public String getExternalSubscriptionUrl() { return externalSubscriptionUrl; }
    public void setExternalSubscriptionUrl(String externalSubscriptionUrl) {
        this.externalSubscriptionUrl = externalSubscriptionUrl;
    }

    public Boolean getAllowPropertySubscription() { return allowPropertySubscription; }
    public void setAllowPropertySubscription(Boolean allowPropertySubscription) {
        this.allowPropertySubscription = allowPropertySubscription;
    }
}

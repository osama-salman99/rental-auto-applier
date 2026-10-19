package eu.rebo.crawler.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "listings")
public class Listing {

    @Id private String id;

    @Column(length = 512)
    private String slug;
    private String title;
    private String city;
    private String postalCode;
    private Integer livingArea;
    private Integer bedrooms;
    private Integer price;
    private Double serviceCosts;
    private String stage;
    @Column(length = 1024)
    private String imageUrl;
    private Instant firstSeenAt;
    private boolean notificationSent;
    private boolean applied;
    private Instant appliedAt;
    /** True once we've sent the one "near your ceiling" review ping, so it isn't re-sent every pass. */
    @ColumnDefault("false")
    private boolean boundaryNotified;

    public Listing() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }

    public Integer getLivingArea() { return livingArea; }
    public void setLivingArea(Integer livingArea) { this.livingArea = livingArea; }

    public Integer getBedrooms() { return bedrooms; }
    public void setBedrooms(Integer bedrooms) { this.bedrooms = bedrooms; }

    public Integer getPrice() { return price; }
    public void setPrice(Integer price) { this.price = price; }

    public Double getServiceCosts() { return serviceCosts; }
    public void setServiceCosts(Double serviceCosts) { this.serviceCosts = serviceCosts; }

    /** Base rent plus service-charge advance — the figure REBO's 4×-income rule uses. */
    public double getGrossRent() {
        return (price != null ? price : 0) + (serviceCosts != null ? serviceCosts : 0.0);
    }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }

    public boolean isNotificationSent() { return notificationSent; }
    public void setNotificationSent(boolean notificationSent) { this.notificationSent = notificationSent; }

    public boolean isApplied() { return applied; }
    public void setApplied(boolean applied) { this.applied = applied; }

    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant appliedAt) { this.appliedAt = appliedAt; }

    public boolean isBoundaryNotified() { return boundaryNotified; }
    public void setBoundaryNotified(boolean boundaryNotified) { this.boundaryNotified = boundaryNotified; }
}

package eu.nederwoon.crawler.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

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
    private String stage;
    @Column(length = 1024)
    private String imageUrl;
    private Instant firstSeenAt;
    private boolean notificationSent;
    private boolean applied;

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
}

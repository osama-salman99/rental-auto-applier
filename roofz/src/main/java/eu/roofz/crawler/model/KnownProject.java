package eu.roofz.crawler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "known_projects")
public class KnownProject {

    @Id private String slug;

    private String title;
    private Boolean isFutureOffer;
    private String city;
    private Integer totalUnits;
    private Instant targetDeliveryDate;
    private Instant firstSeenAt;
    private Instant lastSeenAt;

    public KnownProject() {}

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Boolean getIsFutureOffer() { return isFutureOffer; }
    public void setIsFutureOffer(Boolean isFutureOffer) { this.isFutureOffer = isFutureOffer; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public Integer getTotalUnits() { return totalUnits; }
    public void setTotalUnits(Integer totalUnits) { this.totalUnits = totalUnits; }

    public Instant getTargetDeliveryDate() { return targetDeliveryDate; }
    public void setTargetDeliveryDate(Instant targetDeliveryDate) { this.targetDeliveryDate = targetDeliveryDate; }

    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }

    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}

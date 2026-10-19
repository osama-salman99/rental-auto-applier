package eu.ikwilhuren.crawler.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "listings")
public class Listing {

    @Id private String id;

    private String slug;
    private String title;
    private String city;
    private String postalCode;
    private Integer livingArea;
    private Integer bedrooms;
    private Integer price;
    private String stage;
    private String imageUrl;
    private Instant firstSeenAt;
    private boolean notificationSent;
    private boolean applied;
    @Column(columnDefinition = "boolean default false")
    private boolean nieuw;

    /**
     * MVGM has drawn our request for a viewing — the candidacy advanced past a plain request on the
     * portal. Set from the "my reactions" page during an auto-apply pass so the dashboard can flag an
     * invitation even between passes. Only meaningful while the home is still in the draw.
     */
    @Column(columnDefinition = "boolean default false")
    private boolean invited;

    /**
     * Whether this home can actually be applied for. True until proven otherwise: some homes — new
     * builds, care homes, "available soon" — appear in the listings and pass every filter, but their
     * viewing-request form does not exist (a 404), because they use a different or not-yet-open flow.
     * When an apply hits that, the home is benched here so it stops blocking a slot and being retried
     * every pass. Defaults true so a normal home is applied for the moment it appears.
     */
    @Column(columnDefinition = "boolean default true")
    private boolean applicable = true;

    private Instant appliedAt;

    /**
     * MVGM warns "this house has many viewing requests" on oversubscribed properties. Every listing
     * we applied to carrying that warning came straight back as "no more viewings possible", so it
     * is treated as disqualifying. Null means we haven't looked yet — which is not the same as
     * uncrowded, and the allocator waits rather than guessing.
     */
    private Boolean crowded;

    private Instant competitionCheckedAt;

    /** APPLIED, SKIPPED, FAILED, WITHDRAWN or DRY_RUN — see {@link ApplicationStatus}. */
    @Column(length = 32)
    private String applicationStatus;

    /** Why a SKIPPED or FAILED attempt didn't go through, so the dashboard can say so. */
    @Column(length = 500)
    private String applicationDetail;

    public Listing() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
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

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
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

    public boolean isApplied() {
        return applied;
    }

    public void setApplied(boolean applied) {
        this.applied = applied;
    }

    public boolean isNieuw() {
        return nieuw;
    }

    public void setNieuw(boolean nieuw) {
        this.nieuw = nieuw;
    }

    public boolean isInvited() {
        return invited;
    }

    public void setInvited(boolean invited) {
        this.invited = invited;
    }

    public boolean isApplicable() {
        return applicable;
    }

    public void setApplicable(boolean applicable) {
        this.applicable = applicable;
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

    public String getApplicationDetail() {
        return applicationDetail;
    }

    public void setApplicationDetail(String applicationDetail) {
        this.applicationDetail = applicationDetail;
    }

    public Boolean getCrowded() {
        return crowded;
    }

    public void setCrowded(Boolean crowded) {
        this.crowded = crowded;
    }

    public Instant getCompetitionCheckedAt() {
        return competitionCheckedAt;
    }

    public void setCompetitionCheckedAt(Instant competitionCheckedAt) {
        this.competitionCheckedAt = competitionCheckedAt;
    }

    /**
     * The portal identifies a property by the 32-hex hash that also tails our slug, so the viewing
     * request needs no extra lookup.
     */
    public String objectHash() {
        if (id == null || id.length() < 32) {
            return null;
        }
        String tail = id.substring(id.length() - 32);
        return tail.matches("[0-9a-f]{32}") ? tail : null;
    }
}

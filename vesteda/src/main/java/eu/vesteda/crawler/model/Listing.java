package eu.vesteda.crawler.model;

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

    // ---- what the tenant portal knows, which the public API does not ----

    /** The 32-hex id every portal URL keys off. Null until the portal poll has matched this home. */
    private String portalHash;

    /**
     * The rent the portal advertises. Kept apart from {@link #price} on purpose: the income rule is
     * computed from this figure, and the API's copy of it can lag by hundreds of euros.
     */
    private Integer portalPrice;

    /** FOR_RENT / RESERVED / CLOSED / UNKNOWN — whether the portal will still take a request. */
    private String portalStatus;

    /** The portal's "many viewing requests" warning. Latched: a home that filled up does not un-fill. */
    private Boolean crowded;

    /**
     * The portal answered our request with "no more viewings possible — your interest is
     * registered". We are a reserve on this home. Also latched, for the same reason.
     */
    private Boolean standby;

    private String applicationStatus;

    @Column(length = 512)
    private String applicationDetail;

    private Instant appliedAt;

    // ---- target-group flags the public search API carries (and we used to throw away) ----
    //
    // Vesteda advertises who a home is really for, and it decides who gets it. A general applicant
    // wins nothing on a home let only to over-65s or one needing a care indication; and is quietly
    // out-queued on one that prioritises key professions or people leaving social housing. The search
    // feed states all of this on every unit, so it costs nothing to read — the allocator was simply
    // blind to it. Nullable on purpose: ddl-auto adds these columns to the long-lived config/listing
    // rows as NULL, and "unknown" must read as "no restriction", never trip a primitive default.

    /** Prioritised for teachers / healthcare / police etc. Lowers our odds if we are not one of them. */
    private Boolean keyProfessions;

    /** Let only to tenants aged 65+. A categorical gate for anyone younger — never worth a slot. */
    private Boolean sixtyFivePlus;

    /** Priority for applicants vacating a social rental. Out-queues a general applicant. */
    private Boolean prioritySocialHousing;

    /** Regulated middle-rent (middenhuur). Recorded for visibility; it does not by itself gate us. */
    private Boolean middleRent;

    /** Minimum tenant age the listing demands (50 / 55 / 65), or null/0 for none. */
    private Integer minAge;

    /** The portal's own type label — "Appartement", "Eengezinswoning", "Zorgwoning" (a care home). */
    private String dwellingType;

    // ---- facts only the per-unit detail page carries (fetched separately, not in the search feed) ----

    /** Monthly service costs, on top of the rent. The rent figure alone understates the true outlay. */
    private Integer serviceCosts;

    /** "Beschikbaar vanaf" — when the home can be moved into, as the portal prints it (a Dutch date). */
    private String availableFrom;

    /** Energy label, e.g. "A++". */
    private String energyLabel;

    /** When the detail page was last read, so enrichment can refresh stale rows and skip fresh ones. */
    private Instant detailFetchedAt;

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

    public String getPortalHash() { return portalHash; }
    public void setPortalHash(String portalHash) { this.portalHash = portalHash; }

    public Integer getPortalPrice() { return portalPrice; }
    public void setPortalPrice(Integer portalPrice) { this.portalPrice = portalPrice; }

    public String getPortalStatus() { return portalStatus; }
    public void setPortalStatus(String portalStatus) { this.portalStatus = portalStatus; }

    public Boolean getCrowded() { return crowded; }
    public void setCrowded(Boolean crowded) { this.crowded = crowded; }

    public Boolean getStandby() { return standby; }
    public void setStandby(Boolean standby) { this.standby = standby; }

    public String getApplicationStatus() { return applicationStatus; }
    public void setApplicationStatus(String applicationStatus) { this.applicationStatus = applicationStatus; }

    public String getApplicationDetail() { return applicationDetail; }
    public void setApplicationDetail(String applicationDetail) { this.applicationDetail = applicationDetail; }

    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant appliedAt) { this.appliedAt = appliedAt; }

    public Boolean getKeyProfessions() { return keyProfessions; }
    public void setKeyProfessions(Boolean keyProfessions) { this.keyProfessions = keyProfessions; }

    public Boolean getSixtyFivePlus() { return sixtyFivePlus; }
    public void setSixtyFivePlus(Boolean sixtyFivePlus) { this.sixtyFivePlus = sixtyFivePlus; }

    public Boolean getPrioritySocialHousing() { return prioritySocialHousing; }
    public void setPrioritySocialHousing(Boolean prioritySocialHousing) { this.prioritySocialHousing = prioritySocialHousing; }

    public Boolean getMiddleRent() { return middleRent; }
    public void setMiddleRent(Boolean middleRent) { this.middleRent = middleRent; }

    public Integer getMinAge() { return minAge; }
    public void setMinAge(Integer minAge) { this.minAge = minAge; }

    public String getDwellingType() { return dwellingType; }
    public void setDwellingType(String dwellingType) { this.dwellingType = dwellingType; }

    public Integer getServiceCosts() { return serviceCosts; }
    public void setServiceCosts(Integer serviceCosts) { this.serviceCosts = serviceCosts; }

    public String getAvailableFrom() { return availableFrom; }
    public void setAvailableFrom(String availableFrom) { this.availableFrom = availableFrom; }

    public String getEnergyLabel() { return energyLabel; }
    public void setEnergyLabel(String energyLabel) { this.energyLabel = energyLabel; }

    public Instant getDetailFetchedAt() { return detailFetchedAt; }
    public void setDetailFetchedAt(Instant detailFetchedAt) { this.detailFetchedAt = detailFetchedAt; }

    /**
     * The move-in date, phrased for a card. A real date keeps the portal's wording behind an
     * "Available" prefix ("Available 13 augustus 2026"); the portal's several ways of saying "you can
     * move in now" all collapse to a clean "Available now" rather than the raw Dutch phrase. Null when
     * the detail page has not been read yet.
     */
    public String availabilityLabel() {
        if (availableFrom == null || availableFrom.isBlank()) {
            return null;
        }
        String low = availableFrom.toLowerCase();
        if (low.contains("nu beschikbaar") || low.contains("per direct") || low.contains("direct beschikbaar")) {
            return "Available now";
        }
        return "Available " + availableFrom;
    }

    /**
     * Restrictions a general applicant can never clear, so a slot spent here is simply wasted: homes
     * with a minimum-age requirement (50 / 55 / 65+), and care homes (Zorgwoning) that require a care
     * indication. These are categorical. The softer priority schemes (key professions, social-housing
     * leavers) only lower the odds, so they are handled by the score, not shut out here.
     */
    public boolean hasHardEligibilityGate() {
        return Boolean.TRUE.equals(sixtyFivePlus)
                || (minAge != null && minAge >= 50)
                || "Zorgwoning".equalsIgnoreCase(dwellingType);
    }

    /** Human-readable target-group restrictions, for a row of badges on the browse page. */
    public java.util.List<String> restrictionLabels() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (Boolean.TRUE.equals(sixtyFivePlus)) {
            out.add("65+ only");
        } else if (minAge != null && minAge > 0) {
            out.add(minAge + "+");
        }
        if ("Zorgwoning".equalsIgnoreCase(dwellingType)) {
            out.add("Care home");
        }
        if (Boolean.TRUE.equals(keyProfessions)) {
            out.add("Key professions");
        }
        if (Boolean.TRUE.equals(prioritySocialHousing)) {
            out.add("Social-housing priority");
        }
        if (Boolean.TRUE.equals(middleRent)) {
            out.add("Middenhuur");
        }
        return out;
    }

    /**
     * The rent to judge this home by. The portal's figure when we have it, because that is the one
     * Vesteda computes its income requirement from; the API's only as a fallback.
     */
    public Integer rent() {
        return portalPrice != null ? portalPrice : price;
    }

    /** Whether the portal would still accept a viewing request for this home. */
    public boolean portalAcceptsRequests() {
        return "FOR_RENT".equals(portalStatus) || "RESERVED".equals(portalStatus);
    }
}

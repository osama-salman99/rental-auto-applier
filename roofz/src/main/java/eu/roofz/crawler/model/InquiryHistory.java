package eu.roofz.crawler.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(
        name = "inquiry_history",
        indexes = @Index(name = "idx_inquiry_listing_observed", columnList = "listing_id, observed_at"))
public class InquiryHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long listingId;
    private Integer inquiries;
    private Instant observedAt;

    public InquiryHistory() {}

    public InquiryHistory(Long listingId, Integer inquiries, Instant observedAt) {
        this.listingId = listingId;
        this.inquiries = inquiries;
        this.observedAt = observedAt;
    }

    public Long getId() { return id; }
    public Long getListingId() { return listingId; }
    public Integer getInquiries() { return inquiries; }
    public Instant getObservedAt() { return observedAt; }
}

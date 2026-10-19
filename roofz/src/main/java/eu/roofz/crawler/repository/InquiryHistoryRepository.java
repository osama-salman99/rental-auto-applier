package eu.roofz.crawler.repository;

import eu.roofz.crawler.model.InquiryHistory;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InquiryHistoryRepository extends JpaRepository<InquiryHistory, Long> {

    Optional<InquiryHistory> findTopByListingIdOrderByObservedAtDesc(Long listingId);

    List<InquiryHistory> findByListingIdAndObservedAtAfterOrderByObservedAtAsc(
            Long listingId, Instant after);

    /** Batched fetch for many listings at once — avoids the per-listing N+1 in the stats page. */
    List<InquiryHistory> findByListingIdIn(Collection<Long> listingIds);

    /**
     * Removes the inquiry trail of pruned listings. The id list is the handful of listings deleted in
     * one crawl pass, so an IN clause is safe here — unlike a whole batch of rows, this never grows
     * into the kind of oversized query that overflows the HQL parser.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM InquiryHistory h WHERE h.listingId IN :listingIds")
    int deleteByListingIdIn(@Param("listingIds") Collection<Long> listingIds);
}

package eu.roofz.crawler.service;

import eu.roofz.crawler.model.InquiryHistory;
import eu.roofz.crawler.repository.InquiryHistoryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class InquiryHistoryService {

    private static final Duration MIN_INTERVAL = Duration.ofSeconds(60);

    private final InquiryHistoryRepository repository;
    private final Clock clock;

    public InquiryHistoryService(InquiryHistoryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Drops the inquiry trail of listings that have been deleted. Nothing else removes these rows, so
     * without this every pruned listing leaves its history behind forever — the same unbounded growth
     * that crawl_log fell into.
     */
    public void deleteForListings(java.util.List<Long> listingIds) {
        if (listingIds == null || listingIds.isEmpty()) return;
        repository.deleteByListingIdIn(listingIds);
    }

    public void record(Long listingId, Integer inquiries) {
        if (inquiries == null) return;
        Instant now = clock.instant();
        Optional<InquiryHistory> last = repository.findTopByListingIdOrderByObservedAtDesc(listingId);
        if (last.isPresent()) {
            InquiryHistory prev = last.get();
            if (inquiries.equals(prev.getInquiries())) return;
            if (Duration.between(prev.getObservedAt(), now).compareTo(MIN_INTERVAL) < 0) return;
        }
        repository.save(new InquiryHistory(listingId, inquiries, now));
    }
}

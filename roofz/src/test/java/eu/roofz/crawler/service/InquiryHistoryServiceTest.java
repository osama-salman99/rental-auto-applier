package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.roofz.crawler.model.InquiryHistory;
import eu.roofz.crawler.repository.InquiryHistoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class InquiryHistoryServiceTest {

    private static final long LISTING_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-05-26T10:00:00Z");

    private final InquiryHistoryRepository repo = Mockito.mock(InquiryHistoryRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneId.of("UTC"));
    private final InquiryHistoryService service = new InquiryHistoryService(repo, clock);

    @Test
    void insertsFirstObservation() {
        when(repo.findTopByListingIdOrderByObservedAtDesc(LISTING_ID)).thenReturn(Optional.empty());

        service.record(LISTING_ID, 5);

        ArgumentCaptor<InquiryHistory> captor = ArgumentCaptor.forClass(InquiryHistory.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getInquiries()).isEqualTo(5);
        assertThat(captor.getValue().getObservedAt()).isEqualTo(NOW);
    }

    @Test
    void skipsWhenInquiryCountUnchanged() {
        when(repo.findTopByListingIdOrderByObservedAtDesc(LISTING_ID))
                .thenReturn(Optional.of(new InquiryHistory(LISTING_ID, 5, NOW.minusSeconds(30))));

        service.record(LISTING_ID, 5);

        verify(repo, never()).save(any());
    }

    @Test
    void skipsWhenLastWriteWasWithinSixtySeconds() {
        when(repo.findTopByListingIdOrderByObservedAtDesc(LISTING_ID))
                .thenReturn(Optional.of(new InquiryHistory(LISTING_ID, 5, NOW.minusSeconds(45))));

        service.record(LISTING_ID, 6);

        verify(repo, never()).save(any());
    }

    @Test
    void insertsWhenValueChangedAndLastWriteOlderThanSixtySeconds() {
        when(repo.findTopByListingIdOrderByObservedAtDesc(LISTING_ID))
                .thenReturn(Optional.of(new InquiryHistory(LISTING_ID, 5, NOW.minusSeconds(61))));

        service.record(LISTING_ID, 6);

        verify(repo, times(1)).save(any());
    }

    @Test
    void skipsWhenInquiriesNull() {
        service.record(LISTING_ID, null);
        verify(repo, never()).save(any());
    }
}

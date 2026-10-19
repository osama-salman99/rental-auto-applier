package eu.roofz.crawler.repository;

import eu.roofz.crawler.model.Listing;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ListingRepository extends JpaRepository<Listing, Long> {

    List<Listing> findTop50ByOrderByFirstSeenAtDesc();

    List<Listing> findByAppliedAtIsNotNullAndInviteWatchdogAlertedFalse();

    @Modifying
    @Query("UPDATE Listing l SET l.notificationSent = false")
    void resetAllNotificationFlags();
}

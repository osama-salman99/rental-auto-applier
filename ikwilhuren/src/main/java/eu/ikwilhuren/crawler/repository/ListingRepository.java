package eu.ikwilhuren.crawler.repository;

import eu.ikwilhuren.crawler.model.Listing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ListingRepository extends JpaRepository<Listing, String> {

    @Modifying
    @Query("UPDATE Listing l SET l.notificationSent = false")
    void resetAllNotificationFlags();
}

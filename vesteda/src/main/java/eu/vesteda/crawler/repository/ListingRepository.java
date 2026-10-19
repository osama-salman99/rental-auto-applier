package eu.vesteda.crawler.repository;

import eu.vesteda.crawler.model.Listing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ListingRepository extends JpaRepository<Listing, String> {

    @Modifying
    @Query("DELETE FROM Listing l WHERE l.applied = false AND (l.city IS NULL OR LOWER(l.city) != LOWER(?1))")
    int deleteNotInCityAndNotApplied(String city);
}

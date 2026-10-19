package eu.holland2stay.crawler.repository;

import eu.holland2stay.crawler.model.Listing;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingRepository extends JpaRepository<Listing, String> {}

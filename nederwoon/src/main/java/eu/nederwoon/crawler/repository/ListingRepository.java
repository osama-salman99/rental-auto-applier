package eu.nederwoon.crawler.repository;

import eu.nederwoon.crawler.model.Listing;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ListingRepository extends JpaRepository<Listing, String> {}

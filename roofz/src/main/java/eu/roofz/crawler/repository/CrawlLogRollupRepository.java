package eu.roofz.crawler.repository;

import eu.roofz.crawler.model.CrawlLogRollup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlLogRollupRepository extends JpaRepository<CrawlLogRollup, Long> {}

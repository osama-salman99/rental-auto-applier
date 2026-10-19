package eu.ikwilhuren.crawler.repository;

import eu.ikwilhuren.crawler.model.CrawlLogRollup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlLogRollupRepository extends JpaRepository<CrawlLogRollup, Long> {}

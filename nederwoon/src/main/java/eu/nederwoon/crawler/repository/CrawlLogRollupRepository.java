package eu.nederwoon.crawler.repository;

import eu.nederwoon.crawler.model.CrawlLogRollup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlLogRollupRepository extends JpaRepository<CrawlLogRollup, Long> {}

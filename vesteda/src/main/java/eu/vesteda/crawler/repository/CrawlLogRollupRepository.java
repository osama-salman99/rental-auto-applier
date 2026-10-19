package eu.vesteda.crawler.repository;

import eu.vesteda.crawler.model.CrawlLogRollup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlLogRollupRepository extends JpaRepository<CrawlLogRollup, Long> {}

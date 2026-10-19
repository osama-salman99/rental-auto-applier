package eu.vbt.crawler.repository;

import eu.vbt.crawler.model.CrawlLogRollup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlLogRollupRepository extends JpaRepository<CrawlLogRollup, Long> {}

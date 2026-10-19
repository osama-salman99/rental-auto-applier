package eu.vbt.crawler.repository;

import eu.vbt.crawler.model.CrawlerConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlerConfigRepository extends JpaRepository<CrawlerConfig, Long> {}

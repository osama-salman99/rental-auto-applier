package eu.rebo.crawler.repository;

import eu.rebo.crawler.model.CrawlerConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlerConfigRepository extends JpaRepository<CrawlerConfig, Long> {}

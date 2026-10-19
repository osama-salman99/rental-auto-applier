package eu.roofz.crawler.repository;

import eu.roofz.crawler.model.CrawlerConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlerConfigRepository extends JpaRepository<CrawlerConfig, Long> {}

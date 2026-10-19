package eu.ikwilhuren.crawler.repository;

import eu.ikwilhuren.crawler.model.CrawlerConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlerConfigRepository extends JpaRepository<CrawlerConfig, Long> {}

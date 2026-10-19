package eu.vesteda.crawler.repository;

import eu.vesteda.crawler.model.CrawlerConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlerConfigRepository extends JpaRepository<CrawlerConfig, Long> {}

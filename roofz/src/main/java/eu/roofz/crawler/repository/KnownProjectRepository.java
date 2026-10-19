package eu.roofz.crawler.repository;

import eu.roofz.crawler.model.KnownProject;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnownProjectRepository extends JpaRepository<KnownProject, String> {}

package eu.roofz.crawler.scheduler;

import eu.roofz.crawler.service.ProjectWatcherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ProjectWatcherScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProjectWatcherScheduler.class);
    private static final long HOUR_MS = 60L * 60L * 1000L;

    private final ProjectWatcherService projectWatcherService;

    public ProjectWatcherScheduler(ProjectWatcherService projectWatcherService) {
        this.projectWatcherService = projectWatcherService;
    }

    @Scheduled(fixedRate = HOUR_MS, initialDelay = 60_000)
    public void sweep() {
        try {
            projectWatcherService.sweep();
        } catch (Exception e) {
            log.error("Project sweep failed", e);
        }
    }
}

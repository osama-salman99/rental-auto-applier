package eu.roofz.crawler.service;

import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.KnownProject;
import eu.roofz.crawler.model.api.ApiProject;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import eu.roofz.crawler.repository.KnownProjectRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectWatcherService {

    private static final Logger log = LoggerFactory.getLogger(ProjectWatcherService.class);

    private final RoofzApiClient apiClient;
    private final KnownProjectRepository projectRepository;
    private final NotificationService notificationService;
    private final CrawlerConfigRepository configRepository;

    public ProjectWatcherService(
            RoofzApiClient apiClient,
            KnownProjectRepository projectRepository,
            NotificationService notificationService,
            CrawlerConfigRepository configRepository) {
        this.apiClient = apiClient;
        this.projectRepository = projectRepository;
        this.notificationService = notificationService;
        this.configRepository = configRepository;
    }

    @Transactional
    public void sweep() {
        List<ApiProject> projects = apiClient.fetchAllProjects();
        boolean firstSweep = projectRepository.count() == 0;
        CrawlerConfig config = configRepository.findById(1L).orElseGet(CrawlerConfig::defaults);
        Instant now = Instant.now();

        for (ApiProject p : projects) {
            Optional<KnownProject> known = projectRepository.findById(p.slug());
            boolean incomingFuture = Boolean.TRUE.equals(p.isFutureOffer());

            if (known.isEmpty()) {
                KnownProject row = new KnownProject();
                row.setSlug(p.slug());
                row.setTitle(p.title());
                row.setIsFutureOffer(incomingFuture);
                row.setCity(p.address() == null ? null : p.address().location());
                row.setTotalUnits(p.statistics() == null ? null : p.statistics().total());
                row.setFirstSeenAt(now);
                row.setLastSeenAt(now);
                projectRepository.save(row);
                if (!firstSweep && config.isNotificationsEnabled()) {
                    notificationService.sendNewProjectNotification(p.slug(), p.title(), config);
                }
            } else {
                KnownProject row = known.get();
                boolean wasFuture = Boolean.TRUE.equals(row.getIsFutureOffer());
                row.setTitle(p.title());
                row.setIsFutureOffer(incomingFuture);
                row.setCity(p.address() == null ? null : p.address().location());
                row.setTotalUnits(p.statistics() == null ? null : p.statistics().total());
                row.setLastSeenAt(now);
                projectRepository.save(row);
                if (!wasFuture && incomingFuture && config.isNotificationsEnabled()) {
                    notificationService.sendProjectFutureOfferNotification(
                            p.slug(), p.title(), config);
                }
            }
        }
        log.debug("Project sweep: {} projects, firstSweep={}", projects.size(), firstSweep);
    }
}

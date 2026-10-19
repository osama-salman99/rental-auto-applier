package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.model.KnownProject;
import eu.roofz.crawler.model.api.ApiProject;
import eu.roofz.crawler.model.api.ApiProjectAddress;
import eu.roofz.crawler.model.api.ApiProjectStatistics;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import eu.roofz.crawler.repository.KnownProjectRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProjectWatcherServiceTest {

    private final RoofzApiClient api = mock(RoofzApiClient.class);
    private final KnownProjectRepository repo = mock(KnownProjectRepository.class);
    private final NotificationService notif = mock(NotificationService.class);
    private final CrawlerConfigRepository configs = mock(CrawlerConfigRepository.class);
    private final ProjectWatcherService service =
            new ProjectWatcherService(api, repo, notif, configs);

    private ApiProject project(String slug, String title, boolean isFuture) {
        return new ApiProject(
                1L, slug, title, "unknown", isFuture,
                new ApiProjectAddress("Amsterdam"),
                new ApiProjectStatistics(100),
                null,
                true);
    }

    private CrawlerConfig configWithNotifications(boolean enabled) {
        CrawlerConfig c = new CrawlerConfig();
        c.setNotificationsEnabled(enabled);
        c.setNtfyTopic("test-topic");
        return c;
    }

    @Test
    void firstSweepInsertsAllProjectsWithoutNotifying() {
        when(repo.count()).thenReturn(0L);
        when(repo.findAll()).thenReturn(List.of());
        lenient().when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(configs.findById(1L)).thenReturn(Optional.of(configWithNotifications(true)));
        when(api.fetchAllProjects()).thenReturn(List.of(
                project("the-cohesion-lofts", "The Cohesion Lofts", false),
                project("stunest-spartaan", "StuNest Spartaan", false)));

        service.sweep();

        verify(repo, times(2)).save(any(KnownProject.class));
        verify(notif, never()).sendNewProjectNotification(any(), any(), any());
        verify(notif, never()).sendProjectFutureOfferNotification(any(), any(), any());
    }

    @Test
    void subsequentSweepNotifiesOnNewSlug() {
        KnownProject existing = new KnownProject();
        existing.setSlug("the-cohesion-lofts");
        existing.setIsFutureOffer(false);
        when(repo.count()).thenReturn(1L);
        when(repo.findAll()).thenReturn(List.of(existing));
        when(repo.findById("the-cohesion-lofts")).thenReturn(Optional.of(existing));
        when(repo.findById("new-building")).thenReturn(Optional.empty());
        lenient().when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(configs.findById(1L)).thenReturn(Optional.of(configWithNotifications(true)));
        when(api.fetchAllProjects()).thenReturn(List.of(
                project("the-cohesion-lofts", "The Cohesion Lofts", false),
                project("new-building", "New Building", false)));

        service.sweep();

        verify(notif, times(1)).sendNewProjectNotification(eq("new-building"), eq("New Building"), any());
    }

    @Test
    void subsequentSweepNotifiesOnFutureOfferFlip() {
        KnownProject existing = new KnownProject();
        existing.setSlug("the-cohesion-lofts");
        existing.setIsFutureOffer(false);
        when(repo.count()).thenReturn(1L);
        when(repo.findAll()).thenReturn(List.of(existing));
        when(repo.findById("the-cohesion-lofts")).thenReturn(Optional.of(existing));
        lenient().when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(configs.findById(1L)).thenReturn(Optional.of(configWithNotifications(true)));
        when(api.fetchAllProjects()).thenReturn(List.of(
                project("the-cohesion-lofts", "The Cohesion Lofts", true)));

        service.sweep();

        verify(notif, times(1)).sendProjectFutureOfferNotification(
                eq("the-cohesion-lofts"), eq("The Cohesion Lofts"), any());
    }

    @Test
    void respectsNotificationsDisabledFlag() {
        when(repo.count()).thenReturn(1L);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(any())).thenReturn(Optional.empty());
        lenient().when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(configs.findById(1L)).thenReturn(Optional.of(configWithNotifications(false)));
        when(api.fetchAllProjects()).thenReturn(List.of(
                project("new-building", "New Building", true)));

        service.sweep();

        verify(notif, never()).sendNewProjectNotification(any(), any(), any());
        verify(notif, never()).sendProjectFutureOfferNotification(any(), any(), any());
    }
}

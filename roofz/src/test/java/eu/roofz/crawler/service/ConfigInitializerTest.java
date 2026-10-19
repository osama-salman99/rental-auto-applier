package eu.roofz.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.roofz.crawler.model.ApplicantProfile;
import eu.roofz.crawler.model.CrawlerConfig;
import eu.roofz.crawler.repository.ApplicantProfileRepository;
import eu.roofz.crawler.repository.CrawlerConfigRepository;
import eu.roofz.crawler.repository.ListingRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * The applicant's details are personal, so the code carries none: a fresh install has no applicant
 * and does not apply until switched on, and the application profile comes from the file named by
 * {@code ROOFZ_APPLICANT_PROFILE} or from the Documents page. The values here are made up.
 */
class ConfigInitializerTest {

    private final CrawlerConfigRepository configRepo = mock(CrawlerConfigRepository.class);
    private final ApplicantProfileRepository profileRepo = mock(ApplicantProfileRepository.class);
    private final ListingRepository listingRepo = mock(ListingRepository.class);

    @Test
    void aFreshInstallHasNoApplicantAndDoesNotApply() {
        CrawlerConfig config = CrawlerConfig.defaults();

        assertThat(config.getApplicantFirstName()).isNull();
        assertThat(config.getApplicantLastName()).isNull();
        assertThat(config.getApplicantEmail()).isNull();
        assertThat(config.getApplicantPhone()).isNull();
        assertThat(config.getAutoApplyEnabled()).isFalse();
    }

    @Test
    void withoutAFileNoProfileIsStored() {
        when(configRepo.findById(1L)).thenReturn(Optional.of(CrawlerConfig.defaults()));

        new ConfigInitializer(configRepo, profileRepo, listingRepo, "").run(null);

        verify(profileRepo, never()).save(any());
    }

    @Test
    void theFileSeedsAFreshDatabase(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("applicant-profile.json");
        String json = "{\"person\":{\"personalDetails\":{\"firstName\":\"Jan\"}}}";
        Files.writeString(file, json);
        when(configRepo.findById(1L)).thenReturn(Optional.of(CrawlerConfig.defaults()));

        new ConfigInitializer(configRepo, profileRepo, listingRepo, file.toString()).run(null);

        ArgumentCaptor<ApplicantProfile> saved = ArgumentCaptor.forClass(ApplicantProfile.class);
        verify(profileRepo).save(saved.capture());
        assertThat(saved.getValue().getQuestionnaireJson()).contains("\"firstName\":\"Jan\"");
    }

    @Test
    void theFileNeverReplacesAStoredProfile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("applicant-profile.json");
        Files.writeString(file, "{}");
        when(profileRepo.existsById(1L)).thenReturn(true);
        when(configRepo.findById(1L)).thenReturn(Optional.of(CrawlerConfig.defaults()));

        new ConfigInitializer(configRepo, profileRepo, listingRepo, file.toString()).run(null);

        verify(profileRepo, never()).save(any());
    }

    @Test
    void anExistingSettingsRowGetsNoApplicantDetails() {
        // A stored row whose applicant columns are empty: nothing may be filled in.
        CrawlerConfig stored = new CrawlerConfig();
        stored.setApplicantFirstName(null);
        stored.setApplicantLastName(null);
        stored.setApplicantEmail(null);
        stored.setApplicantPhone(null);
        when(profileRepo.existsById(1L)).thenReturn(true);
        when(configRepo.findById(1L)).thenReturn(Optional.of(stored));

        new ConfigInitializer(configRepo, profileRepo, listingRepo, "").run(null);

        assertThat(stored.getApplicantFirstName()).isNull();
        assertThat(stored.getApplicantLastName()).isNull();
        assertThat(stored.getApplicantEmail()).isNull();
        assertThat(stored.getApplicantPhone()).isNull();
    }
}

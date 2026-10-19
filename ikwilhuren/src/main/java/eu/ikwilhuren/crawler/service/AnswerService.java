package eu.ikwilhuren.crawler.service;

import eu.ikwilhuren.crawler.model.AnswerCatalogue;
import eu.ikwilhuren.crawler.model.ApplicationAnswer;
import eu.ikwilhuren.crawler.model.ProfileValue;
import eu.ikwilhuren.crawler.repository.ApplicationAnswerRepository;
import eu.ikwilhuren.crawler.repository.ProfileValueRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The answers we put into every viewing request, on top of what MVGM has already prefilled from the
 * account profile.
 *
 * <p>An answer left blank is not sent: the portal's own prefill stands. That is safe, but it is only
 * <em>informed</em> if you can see what the prefill says — so we also remember what MVGM's form last
 * told us your profile answers, and show it. "Keep my profile's answer" is not much of an offer if
 * nobody will tell you what the answer is.
 */
@Service
public class AnswerService {

    private final ApplicationAnswerRepository repository;
    private final ProfileValueRepository profileRepository;

    public AnswerService(
            ApplicationAnswerRepository repository, ProfileValueRepository profileRepository) {
        this.repository = repository;
        this.profileRepository = profileRepository;
    }

    /** Field → value for every answer the user has actually set. */
    public Map<String, String> overrides() {
        Map<String, String> out = new LinkedHashMap<>();
        for (ApplicationAnswer a : repository.findAll()) {
            if (a.isSet()) {
                out.put(a.getFieldName(), a.getValue().trim());
            }
        }
        return out;
    }

    /** Field → value for the whole catalogue, blank where unset. Drives the settings form. */
    public Map<String, String> current() {
        Map<String, String> out = new LinkedHashMap<>();
        for (AnswerCatalogue.Answer a : AnswerCatalogue.all()) {
            out.put(a.field(), "");
        }
        out.putAll(overrides());
        return out;
    }

    /**
     * What your ikwilhuren.nu profile currently answers, in plain English where we can manage it.
     * Empty until the first application has been made — the request form is the only place MVGM
     * shows these, and opening it registers interest, so we cannot go and look on demand.
     */
    public Map<String, String> profile() {
        Map<String, String> raw = new LinkedHashMap<>();
        for (ProfileValue v : profileRepository.findAll()) {
            raw.put(v.getFieldName(), v.getValue());
        }

        Map<String, String> readable = new LinkedHashMap<>();
        for (AnswerCatalogue.Answer a : AnswerCatalogue.all()) {
            String value = raw.get(a.field());
            if (value == null || value.isBlank()) {
                continue;
            }
            readable.put(a.field(), a.options().getOrDefault(value, value));
        }
        return readable;
    }

    /** Have we ever managed to read what the profile answers? */
    public boolean knowsProfile() {
        return profileRepository.count() > 0;
    }

    /** Snapshot the prefilled form we just read from MVGM. Only the answers we let you change. */
    @Transactional
    public void rememberProfile(Map<String, String> formFields) {
        for (AnswerCatalogue.Answer a : AnswerCatalogue.all()) {
            String value = formFields.get(a.field());
            if (value != null && !value.isBlank()) {
                profileRepository.save(new ProfileValue(a.field(), value));
            }
        }
    }

    @Transactional
    public void save(Map<String, String> submitted) {
        for (AnswerCatalogue.Answer a : AnswerCatalogue.all()) {
            String value = submitted.get(a.field());
            repository.save(new ApplicationAnswer(a.field(), value == null ? "" : value.trim()));
        }
    }
}

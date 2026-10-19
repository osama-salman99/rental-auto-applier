package eu.ikwilhuren.crawler.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.ikwilhuren.crawler.model.ApplicationStatus;
import eu.ikwilhuren.crawler.model.Listing;
import eu.ikwilhuren.crawler.repository.ListingRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Renders every page for real.
 *
 * <p>A broken Thymeleaf expression compiles perfectly happily and only fails when someone opens the
 * page — and the health check would still report the app as up, so a bad template would sail
 * through the deploy. These tests are the thing that stops that.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false) // the login wall is crawler-core's job, not what's under test
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pages;DB_CLOSE_DELAY=-1",
            "crawler.scheduling.enabled=false",
            "ikwilhuren.email=",
            "ikwilhuren.password=",
            // A made-up income, given the way a deployment gives it (IKWILHUREN_SEED_INCOME), so the
            // ceiling is 4900 × 12 / 42 = €1400.
            "ikwilhuren.seed.income=4900"
        })
class PagesRenderTest {

    @Autowired private MockMvc mvc;
    @Autowired private ListingRepository listingRepository;

    @BeforeEach
    void seed() {
        listingRepository.deleteAll();

        Listing winnable = new Listing();
        winnable.setId("amsterdam-1066ld-35-hamontstraat-8e1b0d1869fb346f68be6224cabf72ac");
        winnable.setSlug(winnable.getId());
        winnable.setTitle("Appartement Hamontstraat 35");
        winnable.setCity("Amsterdam");
        winnable.setPrice(1274);
        winnable.setLivingArea(90);
        winnable.setBedrooms(2);
        winnable.setStage("available");
        winnable.setNieuw(true);
        winnable.setFirstSeenAt(Instant.now());
        listingRepository.save(winnable);

        Listing tooDear = new Listing();
        tooDear.setId("amsterdam-1022la-373-voorbeeldsingel-466d07e2b38d35ba896c538bb34d5500");
        tooDear.setSlug(tooDear.getId());
        tooDear.setTitle("Appartement Voorbeeldsingel 373");
        tooDear.setCity("Amsterdam");
        tooDear.setPrice(2045);
        tooDear.setLivingArea(70);
        tooDear.setStage("available");
        tooDear.setFirstSeenAt(Instant.now());
        listingRepository.save(tooDear);

        // A held request MVGM has drawn: applied, still available, and flagged invited. It must show
        // under its own "Invited" heading with the celebratory flag — never buried among the others.
        Listing invited = new Listing();
        invited.setId("amsterdam-1043aw-285d-testweg-715520fe3c7926241736692f948b8e29");
        invited.setSlug(invited.getId());
        invited.setTitle("Appartement Testweg 285 D");
        invited.setCity("Amsterdam");
        invited.setPrice(1171);
        invited.setLivingArea(68);
        invited.setBedrooms(2);
        invited.setStage("available");
        invited.setFirstSeenAt(Instant.now());
        invited.setApplied(true);
        invited.setApplicationStatus(ApplicationStatus.APPLIED);
        invited.setInvited(true);
        listingRepository.save(invited);
    }

    @Test
    void homeExplainsWhatTheFiveSlotsAreDoing() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Viewing requests")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Hamontstraat 35")));
    }

    @Test
    void aDrawnRequestIsFlaggedAsInvitedInItsOwnSection() throws Exception {
        // The one event on this page worth acting on. A drawn request must surface under an "Invited"
        // heading with the celebratory flag, not blend into the other four held requests.
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(">Invited<")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("lc-invited")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Testweg 285 D")));
    }

    @Test
    void homeCountsTheAlsoRansInsteadOfListingThemAll() throws Exception {
        // Home used to print a card for every listing the crawler had ever seen — 876 of them, on a
        // phone. The ones we can never apply for are now a tally, not a wall.
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Not applying for")))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.containsString(
                                                "Too expensive for your income")))
                // the over-ceiling flat is counted, not rendered as a card
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "Voorbeeldsingel"))));
    }

    @Test
    void theFullListLivesOnItsOwnPage() throws Exception {
        mvc.perform(get("/listings"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Voorbeeldsingel")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Over your")));
    }

    @Test
    void applicationPageRenders() throws Exception {
        mvc.perform(get("/application"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Parking space")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Parking space")));
    }

    @Test
    void settingsPageRendersAndShowsTheDerivedCeiling() throws Exception {
        mvc.perform(get("/config"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("€1400")));
    }

    @Test
    void activityPageRenders() throws Exception {
        mvc.perform(get("/stats")).andExpect(status().isOk());
    }
}

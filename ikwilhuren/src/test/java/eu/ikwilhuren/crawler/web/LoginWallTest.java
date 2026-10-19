package eu.ikwilhuren.crawler.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Every page is behind the shared login now, and there is no local login page: an unauthenticated
 * request is redirected to the central sign-on at auth.osmosis.work. This pins that so a future
 * change is deliberate rather than accidental.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:loginwall;DB_CLOSE_DELAY=-1",
            "crawler.scheduling.enabled=false"
        })
class LoginWallTest {

    @Autowired private MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"/", "/listings", "/stats", "/application", "/config"})
    void everyPageRedirectsToCentralLogin(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("auth.osmosis.work")));
    }

    /** The applied POST changes which homes hold our five requests — still gated. */
    @Test
    void aStrangerCannotChangeWhatWeAppliedFor() throws Exception {
        mvc.perform(post("/listings/1/applied"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("auth.osmosis.work")));
    }
}

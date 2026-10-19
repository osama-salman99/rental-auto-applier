package eu.osmosis.crawler.core.notify;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Registers the shared {@link NtfyClient} so every consuming crawler gets it by injection. */
@Configuration
public class NotificationConfig {

    @Bean
    public NtfyClient ntfyClient(RestClient restClient) {
        return new NtfyClient(restClient);
    }
}

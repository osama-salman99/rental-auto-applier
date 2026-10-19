package eu.osmosis.crawler.core.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Value("${crawler.http.user-agent:Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36}")
    private String userAgent;

    @Bean
    public RestClient restClient() {
        // Give the shared client explicit timeouts. Without them a target that accepts the
        // connection then stalls hangs the calling thread forever, pinning a scheduler/worker
        // thread; the timeouts are generous enough never to cut a legitimate crawl/ntfy/portal
        // call. Build via ClientHttpRequestFactoryBuilder.detect() so it keeps the SAME
        // (auto-detected) factory type RestClient would use by default — only the timeouts change.
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(20))
                .withReadTimeout(Duration.ofSeconds(120));
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .defaultHeader("User-Agent", userAgent)
                .build();
    }

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}

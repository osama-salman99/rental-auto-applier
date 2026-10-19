package eu.osmosis.crawler.core.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the shared {@link CurlFetcher} for the portal crawlers that opt in by setting
 * {@code crawler.curl.base-origin} (the portal's own origin, sent as the {@code Origin} header on
 * form POSTs). Gated on that property so the crawlers that never talk to a cookie-session portal
 * (they scrape JSON APIs) do not get an unused bean or a missing-placeholder failure.
 */
@Configuration
@ConditionalOnProperty(name = "crawler.curl.base-origin")
public class HttpFetchConfig {

    @Bean
    public CurlFetcher curlFetcher(@Value("${crawler.curl.base-origin}") String baseOrigin) {
        return new CurlFetcher(baseOrigin);
    }
}

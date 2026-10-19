package eu.osmosis.crawler.core;

import eu.osmosis.crawler.core.config.RestClientConfig;
import eu.osmosis.crawler.core.http.HttpFetchConfig;
import eu.osmosis.crawler.core.notify.NotificationConfig;
import eu.osmosis.crawler.core.web.GlobalModelAdvice;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * The crawler toolkit: shared HTTP fetching, ntfy notifications and the shared UI assets.
 *
 * <p>Two things a crawler still gets from here are <b>not</b> owned here any more. The SSO login
 * wall lives in auth-core and the {@code /version} endpoint in version-core; crawler-core depends
 * on both, so every crawler keeps exactly what it had. Each registers its own auto-configuration,
 * so neither needs importing below.
 *
 * <p>crawler-core still <i>writes</i> the {@code osmosis-version} resource that version-core reads
 * (see processResources in build.gradle) — the value is baked into app.js at the same time, and
 * comparing the two is how a stale cached tab is detected.
 */
@AutoConfiguration
@Import({
    RestClientConfig.class,
    GlobalModelAdvice.class,
    NotificationConfig.class,
    HttpFetchConfig.class
})
public class CrawlerCoreAutoConfiguration {
}

package eu.vesteda.crawler.service;

import eu.osmosis.crawler.core.http.CurlFetcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Holds a logged-in hurenbij.vesteda.com session in a curl cookie jar, and hands out the Laravel
 * CSRF token that every POST needs.
 *
 * <p>Vesteda's tenant portal is the same white-label rental platform ikwilhuren.nu runs on — same
 * {@code postrequest}/{@code _token} form protocol, same seven-step candidacy model — so the login
 * handshake is identical: warm the homepage first, then take a token from the login form. A token
 * minted by the very response that first sets the session cookie is stale by the time it is posted,
 * and the rejection is silent (still 200, still redirects home, just never signs you in).
 *
 * <p>The session cookie is issued with a two-hour lifetime, so it will expire during any quiet
 * night. Expiry is detected by the portal serving the login form again, and re-login is transparent.
 */
@Service
public class PortalSession {

    private static final Logger log = LoggerFactory.getLogger(PortalSession.class);

    static final String BASE = "https://hurenbij.vesteda.com";
    static final String LOGIN_URL = BASE + "/login/";

    /** Laravel drops the token in both a hidden field and a meta tag; either will do. */
    private static final Pattern TOKEN =
            Pattern.compile(
                    "name=\"_token\"\\s+value=\"([^\"]+)\"|name=\"csrf\"\\s+content=\"([^\"]+)\"");

    private final CurlFetcher curl;
    private final String email;
    private final String password;
    private final Path cookieJar;

    private volatile String csrfToken;

    public PortalSession(
            CurlFetcher curl,
            @Value("${vesteda.email:}") String email,
            @Value("${vesteda.password:}") String password)
            throws IOException {
        this.curl = curl;
        this.email = email;
        this.password = password;
        this.cookieJar = Files.createTempFile("vesteda-session", ".cookies");
        this.cookieJar.toFile().deleteOnExit();
    }

    public boolean isConfigured() {
        return email != null && !email.isBlank() && password != null && !password.isBlank();
    }

    public String csrfToken() {
        return csrfToken;
    }

    /**
     * Fetches a page as the logged-in user, logging in first if the session is cold or has expired.
     */
    public synchronized String getAuthenticated(String url, String referer) {
        if (csrfToken == null) {
            login();
        }
        String html = curl.get(url, cookieJar, referer);
        if (looksLoggedOut(html)) {
            log.info("Portal session expired, logging in again");
            login();
            html = curl.get(url, cookieJar, referer);
            if (looksLoggedOut(html)) {
                throw new PortalException("Still logged out after re-login — check credentials");
            }
        }
        refreshToken(html);
        return html;
    }

    public synchronized String postAuthenticated(
            String url, Map<String, String> form, String referer) {
        if (csrfToken == null) {
            login();
        }
        return curl.postForm(url, form, cookieJar, referer);
    }

    /** Cheap ping that keeps the session warm so we aren't re-logging in on every pass. */
    public synchronized void keepAlive() {
        if (csrfToken == null) {
            return;
        }
        try {
            String html = curl.get(BASE + "/", cookieJar, null);
            if (looksLoggedOut(html)) {
                csrfToken = null;
            } else {
                refreshToken(html);
            }
        } catch (Exception e) {
            log.debug("Keepalive ping failed, will re-login on next use: {}", e.getMessage());
            csrfToken = null;
        }
    }

    private void login() {
        if (!isConfigured()) {
            throw new PortalException(
                    "Vesteda portal credentials not set (VESTEDA_EMAIL / VESTEDA_PASSWORD)");
        }
        curl.get(BASE + "/", cookieJar, null);

        String form = curl.get(LOGIN_URL, cookieJar, BASE + "/");
        String token = extractToken(form);
        if (token == null) {
            throw new PortalException("Could not find CSRF token on the login page");
        }

        Map<String, String> body = new LinkedHashMap<>();
        body.put("postrequest", "login");
        body.put("_token", token);
        body.put("txtEmail", email);
        body.put("txtWachtwoord", password);

        String result = curl.postForm(LOGIN_URL, body, cookieJar, LOGIN_URL);
        if (looksLoggedOut(result)) {
            throw new PortalException("Login rejected — check VESTEDA_EMAIL / VESTEDA_PASSWORD");
        }
        refreshToken(result);
        if (csrfToken == null) {
            csrfToken = token;
        }
        log.info("Logged in to hurenbij.vesteda.com as {}", email);
    }

    private void refreshToken(String html) {
        String token = extractToken(html);
        if (token != null) {
            csrfToken = token;
        }
    }

    /**
     * The portal serves the login form (not a 401) when the session is gone, so "logged out" means
     * "this page is asking me to log in": it carries the login form's own marker field.
     */
    static boolean looksLoggedOut(String html) {
        if (html == null || html.isBlank()) {
            return true;
        }
        // Require two markers for the frmLogin case (not id="frmLogin" alone), matching the sibling
        // ikwilhuren crawler: a stray login form id on an otherwise-authenticated page must not read as
        // "logged out" and trigger a needless re-login mid-apply. The password field alone is decisive.
        return html.contains("name=\"txtWachtwoord\"")
                || (html.contains("value=\"login\"") && html.contains("id=\"frmLogin\""));
    }

    static String extractToken(String html) {
        if (html == null) {
            return null;
        }
        Matcher m = TOKEN.matcher(html);
        if (m.find()) {
            return m.group(1) != null ? m.group(1) : m.group(2);
        }
        return null;
    }
}

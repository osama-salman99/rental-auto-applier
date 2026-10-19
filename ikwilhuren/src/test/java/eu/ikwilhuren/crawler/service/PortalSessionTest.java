package eu.ikwilhuren.crawler.service;

import eu.osmosis.crawler.core.http.CurlFetcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PortalSessionTest {

    private static final String LOGIN_PAGE =
            "<form id=\"frmLogin\"><input name=\"_token\" value=\"tok123\">"
                    + "<input name=\"txtWachtwoord\" type=\"password\"></form>";
    private static final String HOME_LOGGED_IN =
            "<html><body>Welkom, je bent succesvol ingelogd. <a href=\"/logout/\">Logout</a></body></html>";

    private PortalSession session(CurlFetcher curl) throws IOException {
        return new PortalSession(curl, "user@example.com", "secret");
    }

    @Test
    void warmsTheSessionOnTheHomepageBeforeTakingACsrfToken() throws IOException {
        // The portal rotates the session on first contact: a token minted by the same response that
        // first sets PHPSESSID is silently rejected — the POST still 200s and still redirects, it
        // just never signs you in. Landing on /login/ cold is the difference between working and not.
        CurlFetcher curl = mock(CurlFetcher.class);
        when(curl.get(eq(PortalSession.BASE + "/"), any(), any())).thenReturn("<html>home</html>");
        when(curl.get(eq(PortalSession.LOGIN_URL), any(), any())).thenReturn(LOGIN_PAGE);
        when(curl.postForm(eq(PortalSession.LOGIN_URL), anyMap(), any(), any()))
                .thenReturn(HOME_LOGGED_IN);
        when(curl.get(eq("https://ikwilhuren.nu/mijnwoning/"), any(), any()))
                .thenReturn(HOME_LOGGED_IN);

        session(curl).getAuthenticated("https://ikwilhuren.nu/mijnwoning/", null);

        InOrder order = inOrder(curl);
        order.verify(curl).get(eq(PortalSession.BASE + "/"), any(Path.class), any());
        order.verify(curl).get(eq(PortalSession.LOGIN_URL), any(Path.class), any());
        order.verify(curl).postForm(eq(PortalSession.LOGIN_URL), anyMap(), any(Path.class), any());
    }

    @Test
    void refusesToPretendItIsLoggedInWhenThePortalHandsBackTheLoginForm() throws IOException {
        CurlFetcher curl = mock(CurlFetcher.class);
        when(curl.get(any(), any(), any())).thenReturn(LOGIN_PAGE);
        when(curl.postForm(any(), anyMap(), any(), any())).thenReturn(LOGIN_PAGE);

        PortalSession session = session(curl);

        assertThatThrownBy(() -> session.getAuthenticated(PortalSession.BASE + "/x", null))
                .isInstanceOf(PortalException.class)
                .hasMessageContaining("Login rejected");
    }

    @Test
    void recognisesTheLoginFormAsTheSignalThatWeAreLoggedOut() {
        assertThat(PortalSession.looksLoggedOut(LOGIN_PAGE)).isTrue();
        assertThat(PortalSession.looksLoggedOut(HOME_LOGGED_IN)).isFalse();
        assertThat(PortalSession.looksLoggedOut("")).isTrue();
    }

    @Test
    void readsTheTokenFromEitherTheHiddenFieldOrTheMetaTag() {
        assertThat(PortalSession.extractToken(LOGIN_PAGE)).isEqualTo("tok123");
        assertThat(PortalSession.extractToken("<meta name=\"csrf\" content=\"abc\">"))
                .isEqualTo("abc");
        assertThat(PortalSession.extractToken("<html>nothing</html>")).isNull();
    }

    @Test
    void staysQuietWhenNoCredentialsAreConfigured() throws IOException {
        PortalSession unconfigured = new PortalSession(mock(CurlFetcher.class), "", "");

        assertThat(unconfigured.isConfigured()).isFalse();
    }
}

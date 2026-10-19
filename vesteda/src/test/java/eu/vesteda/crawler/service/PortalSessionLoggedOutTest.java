package eu.vesteda.crawler.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * looksLoggedOut must not false-positive on an authenticated page that merely carries a login form's
 * id somewhere — a needless re-login mid-apply is disruptive. Aligned with the sibling ikwilhuren
 * crawler: the password field alone is decisive, and the frmLogin case needs two markers.
 */
class PortalSessionLoggedOutTest {

    @Test
    void blankOrNullIsLoggedOut() {
        assertThat(PortalSession.looksLoggedOut(null)).isTrue();
        assertThat(PortalSession.looksLoggedOut("   ")).isTrue();
    }

    @Test
    void theRealLoginPageIsLoggedOut() {
        assertThat(PortalSession.looksLoggedOut(
                        "<form id=\"frmLogin\"><input name=\"txtWachtwoord\"><button value=\"login\"></form>"))
                .isTrue();
        // Password field alone is decisive.
        assertThat(PortalSession.looksLoggedOut("<input name=\"txtWachtwoord\" type=\"password\">")).isTrue();
    }

    @Test
    void anAuthenticatedPageWithAStrayFrmLoginIdIsNotLoggedOut() {
        // id="frmLogin" alone (e.g. a header login widget on an otherwise logged-in page) must NOT
        // read as logged out — the previous A||C form would have, triggering a needless re-login.
        assertThat(PortalSession.looksLoggedOut("<div id=\"frmLogin\">Welcome back — your requests</div>"))
                .isFalse();
    }
}

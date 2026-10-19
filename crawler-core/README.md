# crawler-core

The toolkit every crawler in this repository shares: HTTP fetching (`CurlFetcher`), phone alerts
through ntfy, the shared page styling and script (`app.css` / `app.js`) and the common page data.

It re-exports two small libraries that sit inside this folder, so a crawler only depends on
`project(':crawler-core')`:

* [auth-core](auth-core) — the login wall: every page needs the shared signed login cookie.
* [version-core](version-core) — `GET /version`, the build number the server is running.

crawler-core also bakes that build number into `app.js` when it builds (see `processResources`).
The page compares the two, which is how a stale cached tab is spotted.

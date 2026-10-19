# holland2stay

Watches Holland2Stay's rental listings and sends a phone alert through [ntfy](https://ntfy.sh) when a
new one matches your filters. A dashboard lists everything it has seen, and you can mark the homes
you applied to. It does not apply by itself.

It sits behind the shared login, so the one other service it needs is the login service,
**osmosis-auth**.

## Settings

Everything comes from environment variables; nothing secret or personal lives in this repository.
See [`.env.example`](.env.example).

| Variable | | What it does |
|---|---|---|
| `SSO_SIGNING_KEY` | required | Exactly the login service's key. The login cookie is signed with it. |
| `OSMOSIS_AUTH_DOMAIN` | | The domain the login and this crawler live under, as set in the login service. Default `osmosis.work`. |
| `OSMOSIS_AUTH_URL` | | Where the login is. Default `https://auth.<domain>/`. |
| `ADMIN_USERNAME` | | The name given to whoever is signed in. Default `osmosis`. |
| `SERVER_ADDRESS`, `SERVER_PORT` | | Where it listens. Default `127.0.0.1:8160`; the Docker image listens on every interface. |
| `HOLLAND2STAY_CF_CLEARANCE` | needed to see listings | The Cloudflare cookie described below. Without it the crawler runs but reads nothing. |
| `HOLLAND2STAY_USER_AGENT` | | The user agent of the browser the cookie came from. Default: a recent desktop Chrome. |
| `HOLLAND2STAY_GRAPHQL_QUERY` | | Replaces the built-in listing query, should the site's data change. |
| `SERVER_TOMCAT_ACCESSLOG_DIRECTORY` | | Where the access log goes. Default `/opt/holland2stay-crawler/logs`. |

The rest is set on the **Settings** page once it runs: the city and filters, and the ntfy topic to
send alerts to. There is no default topic, so nothing is sent until you choose one. Topics on
ntfy.sh are public, so pick a long random name: anyone who guesses it can read the alerts.

## Running it

The build needs Java 21. Run it from the repository root; the jar lands in this folder's
`build/libs/`. The commands below are run from this folder.
The listing data is fetched with the `curl` program, so running the jar directly needs `curl`
installed; the Docker image has it.

```bash
(cd .. && ./gradlew clean build --parallel)
cp .env.example .env        # then fill it in

# with Docker
docker compose up -d --build

# or straight from the jar
set -a; . ./.env; set +a
java -jar build/libs/holland2stay-1.0.0.jar
```

Listings are kept in an H2 database under `data/`, which is a volume in the Docker setup.

Serve it at `holland2stay.<your domain>` through the same kind of HTTPS reverse proxy as the login.
The proxy must set the `X-Forwarded-*` headers itself; with no proxy at all, also set
`SERVER_FORWARD_HEADERS_STRATEGY=none`. To try everything on one machine instead, set
`OSMOSIS_AUTH_DOMAIN=localhost` and `OSMOSIS_AUTH_URL=http://localhost:8210/`, start the login
service the same way, and open `http://localhost:8160/`.

## Getting past Cloudflare

Holland2Stay serves its listing data from an API behind Cloudflare's bot check, and the crawler
cannot pass that check by itself. It borrows the pass from a real browser instead:

1. Open https://www.holland2stay.com in a desktop browser, from the same network address the
   crawler will use, and let the check finish.
2. In the browser's developer tools, copy the value of the `cf_clearance` cookie into
   `HOLLAND2STAY_CF_CLEARANCE`, and the browser's user agent into `HOLLAND2STAY_USER_AGENT`. The
   cookie only works together with that exact user agent.
3. Restart the crawler. When the cookie expires, the log says the request was blocked; repeat.

Without a cookie the crawler starts normally, says once in its log that it cannot read listings yet,
and otherwise stays quiet.

# roofz

Watches the rental listings on roofz.eu and sends a phone alert through [ntfy](https://ntfy.sh)
when a new one matches your filters. A dashboard lists everything it has seen. It can also apply
for matching homes by itself and fill in the application that follows on roofz's portal; all of
that is off until you turn it on (see below).

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
| `ROOFZ_PUBLIC_URL` | | Where this crawler is reached, for links in its alerts. Default `https://roofz.<domain>`. |
| `SERVER_ADDRESS`, `SERVER_PORT` | | Where it listens. Default `127.0.0.1:8080`; the Docker image listens on every interface. |
| `SERVER_TOMCAT_ACCESSLOG_DIRECTORY` | | Where the access log goes. Default `/opt/roofz-crawler/logs`. |

The rest is set on the **Settings** page once it runs: the filters, and the ntfy topic to send
alerts to. There is no default topic, so nothing is sent until you choose one. Topics on ntfy.sh
are public, so pick a long random name: anyone who guesses it can read the alerts.

## Running it

The build needs Java 21. Run it from the repository root; the jar lands in this folder's
`build/libs/`. The commands below are run from this folder.

```bash
(cd .. && ./gradlew clean build --parallel)
cp .env.example .env        # then fill it in

# with Docker
docker compose up -d --build

# or straight from the jar
set -a; . ./.env; set +a
java -jar build/libs/roofz-1.0.0.jar
```

Listings, settings and uploaded documents are kept in an H2 database under `data/`, which is a
volume in the Docker setup.

Serve it at `roofz.<your domain>` through the same kind of HTTPS reverse proxy as the login. The
proxy must set the `X-Forwarded-*` headers itself; with no proxy at all, also set
`SERVER_FORWARD_HEADERS_STRATEGY=none`. To try everything on one machine instead, set
`OSMOSIS_AUTH_DOMAIN=localhost`, `OSMOSIS_AUTH_URL=http://localhost:8210/` and
`ROOFZ_PUBLIC_URL=http://localhost:8080`, start the login service the same way, and open
`http://localhost:8080/`.

## Applying by itself

This happens in two steps. Both are switched on, and set up, on the **Settings** page.

**1. Interest on roofz.eu.** For each matching home the crawler sends roofz.eu's own "subscribe to
this home" form, with the name, email and phone set on the Settings page. It applies to nothing
while any of those is missing.

**2. The application on roofz's portal (ReletPortal).** roofz then emails an invitation to fill in
an application on its portal, and the crawler can do that too:

- `RELETPORTAL_EMAIL` and `RELETPORTAL_PASSWORD` are your account on the portal.
- roofz's emails (the invitation, then its confirmations) must reach the crawler. Forward them,
  for example with a Cloudflare Email Worker, as a `POST` of the raw email to
  `/portal/ingest-email` with the header `X-Ingest-Token` set to `INGEST_TOKEN`. That is the only
  address reachable without the login.
- The answers the application asks for (personal details, address, ID document, work and income,
  bank account, household) are filled in on the **Documents** page, where you also upload the
  documents: ID, payslips, employer statement and bank statements. `ROOFZ_APPLICANT_PROFILE` can
  point at a JSON file instead, read once on the first start;
  [`src/test/resources/applicant-profile.json`](src/test/resources/applicant-profile.json) shows its
  shape, with made-up values.
- It starts in dry run, which only logs what it would send. Uploading the documents and the final
  submit each have their own switch and start off, because a submitted application cannot be
  taken back.

## Phone calls

With the `TWILIO_*` settings, each new listing also starts a Twilio Studio flow that phones
`TWILIO_TO_NUMBER` from `TWILIO_FROM_NUMBER`. Without them no calls are made.

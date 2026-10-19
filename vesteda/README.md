# vesteda

Watches Vesteda's rental listings and sends a phone alert through [ntfy](https://ntfy.sh) when a
new one matches your filters. A dashboard lists everything it has seen, and you can mark the homes
you applied to. It can also request viewings by itself through your Vesteda account; that is off
until you turn it on (see below).

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
| `SERVER_ADDRESS`, `SERVER_PORT` | | Where it listens. Default `127.0.0.1:8120`; the Docker image listens on every interface. |
| `SERVER_TOMCAT_ACCESSLOG_DIRECTORY` | | Where the access log goes. Default `/opt/vesteda-crawler/logs`. |

The rest is set on the **Settings** page once it runs: the city and filters, and the ntfy topic to
send alerts to. There is no default topic, so nothing is sent until you choose one. Topics on
ntfy.sh are public, so pick a long random name: anyone who guesses it can read the alerts.

## Running it

The build needs Java 21. Run it from the repository root; the jar lands in this folder's
`build/libs/`. The commands below are run from this folder.
The Vesteda portal is reached with the `curl` program, so running the jar directly with a portal
account needs `curl` installed; the Docker image has it.

```bash
(cd .. && ./gradlew clean build --parallel)
cp .env.example .env        # then fill it in

# with Docker
docker compose up -d --build

# or straight from the jar
set -a; . ./.env; set +a
java -jar build/libs/vesteda-1.0.0.jar
```

Listings are kept in an H2 database under `data/`, which is a volume in the Docker setup.

Serve it at `vesteda.<your domain>` through the same kind of HTTPS reverse proxy as the login.
The proxy must set the `X-Forwarded-*` headers itself; with no proxy at all, also set
`SERVER_FORWARD_HEADERS_STRATEGY=none`. To try everything on one machine instead, set
`OSMOSIS_AUTH_DOMAIN=localhost` and `OSMOSIS_AUTH_URL=http://localhost:8210/`, start the login
service the same way, and open `http://localhost:8120/`.

## Applying by itself

The crawler can also request viewings of matching homes on the Vesteda portal
(hurenbij.vesteda.com), from your own account there. The portal fills each request in from the
account, so the account's profile and documents must be complete first.

- `VESTEDA_EMAIL` and `VESTEDA_PASSWORD` are that account. Without them the crawler only watches
  and sends alerts.
- It is switched on, and its figures set, on the **Settings** page. Turned on, it only logs what it
  would have sent until dry run is switched off as well.
- It needs your gross monthly income, and savings if you have them. It only applies for homes whose
  rent is at most the income plus a 120th of the savings, divided by 3.5, which is Vesteda's own
  rule. Without an income it applies to nothing.
- Vesteda allows five requests at a time. The crawler keeps the five best, and can withdraw one
  when a better home appears.

The `VESTEDA_SEED_*` variables in [`.env.example`](.env.example) can set the same things when it
starts, without opening the settings page.

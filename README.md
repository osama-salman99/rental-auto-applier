# rental-auto-applier

Finding a rental home in Amsterdam is a race: a good listing is gone within minutes. These
crawlers watch seven Dutch rental websites around the clock, send a phone alert through
[ntfy](https://ntfy.sh) the moment a matching home appears, and on four of the sites apply for it
by themselves.

Each site is its own small Spring Boot app with its own dashboard and settings page. They share one
core library.

| Folder | Website | Applies by itself |
|---|---|---|
| [`roofz`](roofz) | roofz.eu | Yes: registers interest, then fills in the application on roofz's portal |
| [`ikwilhuren`](ikwilhuren) | ikwilhuren.nu (MVGM) | Yes: requests viewings, keeping the five best homes |
| [`vesteda`](vesteda) | Vesteda | Yes: requests viewings, keeping the five best homes |
| [`rebo`](rebo) | REBO | Yes: registers interest in new homes |
| [`holland2stay`](holland2stay) | Holland2Stay | No, alerts only |
| [`nederwoon`](nederwoon) | NederWoon | No, alerts only |
| [`vbt`](vbt) | vb&t Verhuurmakelaars | No, alerts only |
| [`crawler-core`](crawler-core) | | The shared core: fetching, alerts, page styling, the login wall |

## How applying works

- **Only what you can get.** Each site has its own income rule (for example, rent at most the
  income divided by 3.5 on Vesteda, or the yearly income divided by 42 on ikwilhuren.nu). The
  crawlers apply that rule, so they never spend an application on a home that would be refused.
- **The best few, not the first few.** Where a site allows only five open requests, the crawler
  scores the homes (how full they already are, how new, how well they fit) and holds the five best,
  and can withdraw a request when a clearly better home turns up.
- **Off until you say so.** Applying is off by default, and once on it starts in dry run, where it
  only logs what it would send. A step that cannot be undone, such as roofz's final submit, has its
  own switch.
- **Nothing personal in the code.** Accounts, income and the applicant's details come from
  environment variables or the settings pages, never from this repository. The applicants and
  incomes in the tests are made up.

## Building

Needs Java 21. From this folder:

```bash
./gradlew clean build --parallel
```

Each app's jar lands in its own `build/libs/`. Each folder's README lists its settings and how to
run it (with Docker or straight from the jar).

## Login

The pages sit behind a shared login: each app checks a signed login cookie (HMAC-SHA256 with
`SSO_SIGNING_KEY`) set by a separate central login service, which is not part of this repository.
Only the health check, the version number, the page styling and the email intake roofz needs
are reachable without it, and the email intake checks its own secret.

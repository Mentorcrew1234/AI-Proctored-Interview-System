# Deployment

How to put this application somewhere other than a development laptop, and what
will bite you when you do.

**Never done this before, and starting from a blank Ubuntu server?** Read
`VPS-SETUP-WALKTHROUGH.md` in this folder first — it is the sequential,
install-everything-from-nothing version of this document. This file is the
reference to come back to afterwards; it assumes Java and Node are already
installed and jumps around by topic rather than walking through in order.

**In a hurry?** [What you need](#what-you-need-before-you-start) →
[the ten-minute path](#the-ten-minute-path) →
[after deploying, check these](#after-deploying-check-these). If something is
already broken, start at [Troubleshooting](#troubleshooting).

| Section | For |
|---|---|
| [What you need before you start](#what-you-need-before-you-start) | versions and prerequisites |
| [The ten-minute path](#the-ten-minute-path) | the whole deployment as one checklist |
| [The one non-negotiable: HTTPS](#the-one-non-negotiable-https) | why the camera needs a certificate |
| [Build](#build) | producing the JAR, and the build order that matters |
| [Configuration](#configuration) | where secrets live, every setting, and `JWT_SECRET` |
| [Database](#database) | schema, MySQL vs MariaDB, backups |
| [Memory](#memory) | heap sizing on a shared host |
| [Reverse proxy](#reverse-proxy) | nginx, forwarded headers, caching the 55 MB cold load |
| [Running as a service](#running-as-a-service) | the systemd unit |
| [Upgrading an existing deployment](#upgrading-an-existing-deployment) | artifact swap, migrations, rollback |
| [Troubleshooting](#troubleshooting) | symptom → cause → fix |
| [Email](#email) | switching invitations and reset links on |
| [Administrative pages](#administrative-pages-worth-knowing) | what a deployer will want to find |
| [After deploying, check these](#after-deploying-check-these) | the acceptance checklist |
| [Known operational limits](#known-operational-limits) | what this deployment cannot do |

It is a **single runnable JAR**. Vite builds the React exam screen into
`src/main/resources/static/exam/`, so there is one artifact, one process and no
separate web server to configure. There is no Docker, no orchestration and no
clustering — see the scope guardrails in `CLAUDE.md`; a prototype that needs a
cluster to be demonstrated is a prototype nobody can demonstrate.

---

## What you need before you start

| Requirement | Version | Needed for | Notes |
|---|---|---|---|
| **JDK** | **25** | building and running | `java.version` is 25 in `pom.xml`; an older JDK will not compile |
| **Node.js + npm** | 24.x / 11.x | building the exam screen | build machine only — the server never needs Node |
| **MySQL 8** or **MariaDB 10.6+** | — | everything | one schema, UTF-8; see [Database](#database) |
| **A hostname and a TLS certificate** | — | the camera | **not optional** — see the next section |
| A Gemini API key | — | live AI questions and grading | optional; without it the app runs on the offline bank |
| SMTP credentials | — | invitation and reset email | optional; off by default |

Verified build/run environment: Java 25.0.3 LTS, Node v24.18.0, npm 11.16.0.

---

## The ten-minute path

The whole deployment, in order. Every step is expanded in its own section below;
this is the checklist to follow, not a substitute for reading them.

```bash
# 1. Build (on a machine with Node and the JDK)
cd frontend && npm install && npm run build && cd ..
./mvnw.cmd clean package                 # -> target/proctor-interview-0.0.1-SNAPSHOT.jar

# 2. Create the database (on the server)
mysql -u root -p -e "CREATE DATABASE proctor_interview   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 3. Lay out the deployment directory
sudo mkdir -p /opt/proctor/config
sudo cp target/proctor-interview-0.0.1-SNAPSHOT.jar /opt/proctor/proctor-interview.jar

# 4. Write /opt/proctor/config/application.yml   (DB password, Gemini key, SMTP)
#    and set JWT_SECRET in the environment - never leave the packaged default

# 5. First start, in the foreground, and WATCH THE LOG
cd /opt/proctor && java -Xms256m -Xmx512m -jar proctor-interview.jar

# 6. Once the log is clean: install the systemd unit, put nginx in front with
#    a real certificate, then work through "After deploying, check these"
```

**Step 5 in the foreground is not optional the first time.** Flyway migrating,
`ddl-auto: validate` agreeing with the schema, and the mode/secret warnings all
appear there and nowhere else. A service that starts "successfully" with the
development JWT secret looks identical to one configured correctly.

---

## The one non-negotiable: HTTPS

**The interview does not work over plain HTTP anywhere except `localhost`.**

`getUserMedia` — the call that opens the camera — is restricted to a *secure
context*. Browsers make one exception, for `localhost`, which is why this never
surfaces in development and always surfaces on first deployment. Over
`http://` on a real hostname the camera request is rejected before the user is
even asked, the device check fails, and no interview can start.

The same applies to fullscreen and to speech **recognition** (the microphone,
which records the candidate's answer). Speech **synthesis** — reading the
question aloud — is not gated on a secure context, so on plain HTTP you get the
confusing symptom of a question being read to a candidate who cannot answer it.

So: a certificate is a requirement, not a hardening step. Terminating TLS at a
reverse proxy is the simplest way, and it is what the `X-Forwarded-*` handling
below is for.

---

## Build

```bash
cd frontend && npm install && npm run build && cd ..
./mvnw.cmd clean package -DskipTests=false
```

`npm run build` must come first. Maven packages whatever is in
`src/main/resources/static/exam/` at the time it runs, so a JAR built without a
fresh frontend build silently ships the previous exam screen.

The result is `target/proctor-interview-0.0.1-SNAPSHOT.jar`.

```bash
java -jar target/proctor-interview-0.0.1-SNAPSHOT.jar
```

### What the build warns about, and why it is fine

Vite reports that the exam chunk is over its 500 kB hint — it is about 2.2 MB.
That is deliberate: MediaPipe and COCO-SSD are bundled so an interview needs no
CDN. Code-splitting them would trade a smaller first paint for a network
dependency in the middle of an interview, which is the wrong way round.

---

## Configuration

Nothing sensitive belongs in the JAR. Spring Boot loads `./config/application.yml`
from the working directory at **higher precedence** than the packaged
`classpath:application.yml`, so the deployment's secrets live in a file beside
the JAR that git never sees (`/config/` is gitignored — note the leading slash;
see the gotcha in `CLAUDE.md` about what happens without it).

```
/opt/proctor/
├── proctor-interview.jar
└── config/
    └── application.yml      # DB password, Gemini key, SMTP credentials
```

Every setting also has an environment-variable form, which is usually the better
choice under systemd:

| Variable | Purpose | Default |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | database | `localhost` / `3360` / `proctor_interview` |
| `DB_USER` / `DB_PASSWORD` | database credentials | `root` / empty |
| `JWT_SECRET` | **must be set** — see below | a development placeholder |
| `INTERVIEW_MODE` | `NORMAL` or `DEMO` | `NORMAL` |
| `INTERVIEW_AI_MODE` | `MOCK` or `GEMINI` | `MOCK` |
| `GEMINI_MODEL` | model id | `gemini-3.1-flash-lite` |
| `INTERVIEW_QUESTION_MODE` | `FIXED` or `ADAPTIVE` — the default for interviews that name none | `FIXED` |
| `LOGIN_THROTTLE_ENABLED` | login lockout | `true` |
| `GEMINI_API_KEY` / `GEMINI_TIMEOUT_SECONDS` | live AI, and how long a candidate waits before it falls back | empty / `8` |
| `MAIL_ENABLED` | switches all outgoing email on | `false` |
| `MAIL_FROM` / `MAIL_FROM_NAME` | the visible sender. For Gmail, `MAIL_FROM` must equal `MAIL_USERNAME` | empty / `Proctored Interviews` |
| `MAIL_BASE_URL` | **must be the public HTTPS address** — every invite and reset link is built from it | `http://localhost:8080` |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` | SMTP transport | `smtp.gmail.com` / `587` / empty / empty |

Settings with **no** environment form — these must go in
`config/application.yml`:

| Key | Purpose | Default |
|---|---|---|
| `app.retention.months` | retention window. Drives a **report**, never a purge | `24` |
| `app.password-reset.expiry-minutes` | how long a reset link lives | `30` |
| `app.login-throttle.{max-attempts,window-minutes,lock-minutes}` | lockout shape (the on/off switch *does* have one, above) | `5` / `15` / `10` |
| `app.transcript.{filler-words,filler-phrases}` | what is stripped before scoring | see `docs/system/features/SCORING.md` |
| `app.jwt.expiry-minutes` | token lifetime (the *secret* has an env form, above) | `240` |
| `app.interview.default-question-count` | the scheduling form's default | `5` |
| `app.report.scoring.*`, `app.report.proctoring.*` | weights, thresholds, and which observations can cap a result | see `docs/system/features/SCORING.md` |

**`MAIL_BASE_URL` (`app.mail.base-url`) must be the public HTTPS address.**
Invitation and password-reset links are built from it; left at its default, every
link points at `localhost` and nobody can follow one. **`INTERVIEW_MODE` must stay `NORMAL`** for real candidates — `DEMO` adds
a live detection panel to the exam screen, and silently running a real interview
in it is the one mistake the default exists to prevent.

**`JWT_SECRET` is the one that matters.** The packaged default is
`dev-only-secret-change-me-…`, which is in the repository and therefore public.
Leaving it means anyone can mint a valid token for any account. It must be at
least 32 characters for HS256.

---

## Database

MySQL 8 or MariaDB 10.6+, schema `proctor_interview`, UTF-8:

```sql
CREATE DATABASE proctor_interview CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'proctor'@'localhost' IDENTIFIED BY '…';
GRANT ALL PRIVILEGES ON proctor_interview.* TO 'proctor'@'localhost';
```

Flyway creates every table on first start. `ddl-auto: validate` then checks the
entities against what Flyway produced and **refuses to start** on any mismatch —
which is the behaviour you want, because the alternative is a running
application quietly writing to the wrong shape.

### MariaDB

The migrations are written against MySQL 8 and work on MariaDB, with two things
worth knowing:

- `V6` uses `SHA2(...)`, which MariaDB supports.
- MariaDB reports its version in a way older MySQL connector releases dislike.
  The bundled connector is fine; if you swap it, check startup rather than
  assuming.

**Verify a deployment by starting it, not by running the test suite.** The test
profile uses H2 with Flyway *disabled* (`src/test/resources/application-test.yml`),
so a passing suite says nothing about whether a migration applies. The startup
log is the proof:

```
Migrating schema `proctor_interview` to version "11 - add interview question mode"
Successfully applied 1 migration
Started ProctorInterviewApplication
```

### Backups

`mysqldump proctor_interview` is the whole story — there is no state outside the
database except the JAR itself. Note that two things are deliberately *not*
recoverable from a backup: bulk-generated candidate passwords (never persisted)
and AI health counters (in memory, reset by restart).

---

## Memory

The JVM is the constraint on a small shared host. The application is modest —
one process, no in-memory caches beyond the login throttle and the bulk staging
store — but the default heap is a *fraction of total RAM*, which on a shared box
means it will happily size itself larger than its share.

Set it explicitly:

```bash
java -Xms256m -Xmx512m -jar proctor-interview.jar
```

512 MB is comfortable for prototype-scale use. Below about 256 MB, Hibernate
start-up and the Apache POI paths used by bulk scheduling become uncomfortable.

Two in-memory stores expire on their own and need no attention: `BulkStagingStore`
(30 minutes, per user) and `LoginAttemptService` (minutes). Both are cleared by a
restart, which is a documented limitation rather than a bug — see
`docs/system/quality/LIMITATIONS.md`.

---

## Reverse proxy

Terminate TLS at the proxy and forward to `127.0.0.1:8080`. The two headers that
matter are the forwarded-proto pair — without them Spring builds redirect URLs
with `http://`, and the login redirect after sign-in breaks.

```nginx
server {
    listen 443 ssl http2;
    server_name interviews.example.com;

    ssl_certificate     /etc/letsencrypt/live/interviews.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/interviews.example.com/privkey.pem;

    # The exam screen ships ~55 MB of models and WASM on a cold load.
    client_max_body_size 20m;
    proxy_read_timeout   120s;

    location / {
        proxy_pass         http://127.0.0.1:8080;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
    }
}
```

and tell Boot to trust them:

```yaml
server:
  forward-headers-strategy: framework
```

### The exam screen is a large cold load

A candidate opening the exam for the first time downloads roughly:

| Asset | Size |
|---|---|
| MediaPipe WASM runtime | ~33 MB |
| Face + COCO-SSD models | ~22 MB |
| JS bundle | ~2.2 MB |

Around **55 MB**, once, then cached. On a slow connection that is a visible wait
on the device-check screen, which is why the screen reports what it is loading
rather than showing a blank panel.

These are static files under `/exam/`. Let the proxy cache them hard — they are
content-hashed or version-pinned, so a long `Cache-Control` is safe and turns the
second interview on a machine into an instant start:

```nginx
location /exam/models/ { proxy_pass http://127.0.0.1:8080; expires 30d; }
location /exam/wasm/   { proxy_pass http://127.0.0.1:8080; expires 30d; }
```

---

## Running as a service

```ini
# /etc/systemd/system/proctor.service
[Unit]
Description=AI-Based Proctored Interview System
After=network.target mysql.service

[Service]
User=proctor
WorkingDirectory=/opt/proctor
ExecStart=/usr/bin/java -Xms256m -Xmx512m -jar /opt/proctor/proctor-interview.jar
Environment=JWT_SECRET=…
Environment=DB_PASSWORD=…
SuccessExitStatus=143
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

`WorkingDirectory` must be the directory containing `config/`, or the secrets
file is not found and the application starts with the packaged defaults — which
it will do *successfully*, with the development JWT secret. Check the startup log
rather than assuming.

---

## Upgrading an existing deployment

The same artifact swap every time, and the only stateful step is Flyway.

```bash
# on the build machine
cd frontend && npm install && npm run build && cd ..
./mvnw.cmd clean package

# on the server
mysqldump -u proctor -p proctor_interview > /var/backups/proctor-$(date +%F).sql
sudo systemctl stop proctor
sudo cp proctor-interview-0.0.1-SNAPSHOT.jar /opt/proctor/proctor-interview.jar
sudo systemctl start proctor
sudo journalctl -u proctor -f          # watch Flyway apply the new migrations
```

- **Back up before every upgrade**, because there are **no down migrations**.
  Flyway only rolls forward; undoing a schema change means restoring the dump.
- **Rolling back the JAR alone is not a rollback.** An older JAR against a newer
  schema fails `ddl-auto: validate` at startup — loudly, which is the point.
  Restore the dump first, then the older JAR.
### The live deployment at test.triospark.in

The runbook above describes a clean install. The running deployment predates it
and uses different names, so following the commands verbatim will not find it:

| | Runbook says | test.triospark.in actually uses |
|---|---|---|
| Directory | `/opt/proctor/` | `/opt/proctor-interview/` |
| JAR | `proctor-interview.jar` | `app.jar` |
| Unit | `proctor.service` | `proctor-interview.service` |
| App port | 8080 | 8081 (nginx proxies to it) |
| Database | MySQL 8 on 3360 | MariaDB on 3306, reported to Hibernate as 5.5.5 |

Rotate the seeded demo passwords immediately after the first deploy. `DataSeeder`
creates `admin@demo.local`, `recruiter@demo.local` and `candidate@demo.local` with
the passwords printed in `CLAUDE.md`, which is public. Rotating them is a bcrypt
hash written straight to `users.password_hash` (cost 10, `$2a$`); the seeder will
not undo it, because it only fills in accounts that do not exist.

Secrets live in `/etc/proctor-interview.env` (`600 root:root`), referenced by
`EnvironmentFile=` in the unit - **not** as inline `Environment=` lines. That is
not a style preference. Systemd exposes `Environment=` values through its D-Bus
API, so `systemctl show proctor-interview -p Environment` printed the database
password, the Gemini key and the JWT secret to **any** local user, including
`www-data`, regardless of the unit file being `600`. On a box that also runs
seven PHP sites as `www-data`, one vulnerability in any of them was enough to
read all three. `EnvironmentFile=` publishes only the path, so the same command
now returns an empty `Environment=`. Keep it that way when editing the unit.

Two consequences worth knowing before an upgrade there. Hibernate warns that the
5.5.5 dialect is below its minimum supported 8.0.0; it is the MariaDB version
string, the schema validates, and every migration through V13 applies cleanly.
And **startup takes about 110 seconds** on that 937 MB box, so a 502 immediately
after `systemctl start` is the application still booting, not a failed deploy -
wait for `Started ProctorInterviewApplication` before concluding anything.

- **`npm run build` before `package`, every time.** Maven packages whatever is
  sitting in `src/main/resources/static/exam/`; skip the frontend build and the
  new JAR silently ships the previous exam screen.
- **Interviews in flight do not survive a restart cleanly.** A candidate mid-
  interview loses the page; their submitted answers are already stored, and the
  expiry sweep closes the session within a minute of its deadline. Upgrade
  outside interview hours.
- **Config is not versioned with the JAR.** A release that adds a setting needs
  `config/application.yml` updated by hand; check this document's configuration
  table after every upgrade.

---

## Troubleshooting

| Symptom | Almost always | Fix |
|---|---|---|
| Camera never prompts; device check fails | The page is not on HTTPS (or the certificate is invalid) | Fix TLS. `getUserMedia` needs a secure context; `localhost` is the only exception |
| Application will not start, `SchemaManagementException` | Entities and schema disagree | Read the message: a migration did not run, or a JAR older than the schema was deployed. Never "fix" it by setting `ddl-auto: update` |
| Startup stops on a Flyway checksum error | A migration file was edited after being applied | Restore the file. Migrations are immutable once applied — `V1__baseline.sql` especially |
| Login works, then every page 403s | Signed in as the wrong role | `/recruiter/**` is closed to admins and vice versa. Check the sidebar; it is generated from the role |
| Redirect after sign-in goes to `http://` | `X-Forwarded-Proto` not forwarded or not trusted | Set the header in nginx **and** `server.forward-headers-strategy: framework` |
| Exam screen loads but never leaves "loading models" | `/exam/models/` or `/exam/wasm/` not served | Check the paths through the proxy; the report will say "monitoring incomplete" for such a session |
| Report says "monitoring incomplete" | The browser never confirmed the detectors loaded, or dropped events | Previous row. This describes the observer, never the candidate, and never changes a score |
| Every interview uses the offline bank | No API key, `INTERVIEW_AI_MODE=MOCK`, or the model is refused | `/admin/ai-health` — the only place this is visible, because the fallback hides it |
| Questions are silent | The browser has no speech synthesis | Cosmetic: the question is on screen as text and answering is unaffected |
| No email arrives, no error | `app.mail.enabled` is false, or `app.mail.from` is unset | `sendable()` needs both. Sending failures are logged, never thrown — check the log, not the UI |
| Invitation links point at `localhost` | `app.mail.base-url` left at its default | Set it to the public HTTPS address |
| Account locked out repeatedly | 5 failed logins in 15 minutes | It clears itself in 10 minutes, or restart the process. Keyed by account, not IP |
| Java heap errors under bulk upload | Default heap on a shared host | Set `-Xms256m -Xmx512m` explicitly |

---

## Email

Off by default. `app.mail.enabled=true` plus `app.mail.from` and the
`spring.mail.*` credentials switch it on; `app.mail.base-url` must be the public
HTTPS address, because it is what invitation and password-reset links are built
from. Full detail in `docs/system/features/EMAIL-NOTIFICATIONS.md`.

With mail off, the forgot-password page says so plainly rather than pretending a
link was sent — but it means an administrator has to set passwords by hand, so
enabling mail is worth doing before any pilot with real candidates.

---

## Administrative pages worth knowing

Three admin-only pages exist that a deployer will want to find:

| Page | What it is for |
|---|---|
| `/admin/ai-health` | Whether the language model is actually working. The offline fallback hides outages — every interview still completes — so this is the only place a retired model or a bad key becomes visible. In memory, so it resets on restart. |
| `/admin/data-retention` | What is past the retention window, and the log of what has been erased. Reports only; it deletes nothing. |
| `/admin/users/{id}/data` | Preview and erase one candidate's data. Requires the candidate's email typed to confirm. Irreversible. |

`/{admin|recruiter}/candidates` is not administrative — it is an ordinary staff
view of one candidate and their interviews, scoped to the viewer.

---

## After deploying, check these

In order, because each depends on the last:

1. **Startup log** — Flyway migrated to the expected version, `Started
   ProctorInterviewApplication`, no schema-validation error.
2. **`https://…/login` loads over HTTPS** with a valid certificate. Not `http`.
3. **Sign in** as each seeded role and confirm the sidebar differs.
4. **`JWT_SECRET` is not the default** — grep the process environment, not the
   config file you *meant* to write.
5. **Open an exam link in Chrome or Edge** and reach the device check. This is
   the step that catches a missing certificate, because it is the first one that
   needs the camera.
6. **Complete one interview end to end** and open its report. Confirm the
   proctoring tab does *not* say "monitoring incomplete" — if it does, the
   models are not loading, which on a fresh deployment usually means the
   `/exam/wasm/` or `/exam/models/` paths are not being served.
7. **`/admin/ai-health`**, if `INTERVIEW_AI_MODE=GEMINI`. It is the only place an
   API-key or model problem is visible, because the offline fallback hides it —
   every interview still completes.

Step 6 is the one people skip and the one that matters. `docs/system/quality/TEST-PLAN.md` has
the fuller manual script.

---

## Known operational limits

Restating the ones that affect running it, from `docs/system/quality/LIMITATIONS.md`:

- **Single instance.** The login throttle, the bulk staging store and AI health
  are per-process, so a second instance would not share them. There is no session
  replication either. Scale up, not out.
- **Gemini free tier** is roughly 10 requests/minute and 250/day — about 35
  interviews a day. Beyond that every call falls back to the offline bank, which
  the report records honestly but which is a worse interview.
- **Chrome or Edge only** for the candidate exam screen, because of the Web
  Speech API. Firefox users get the typing fallback.
- **Erasure exists but reaches no backups**, because the prototype takes none.
  On a real deployment, a `mysqldump` taken before an erasure still contains the
  data that was erased — decide what your backup retention means before promising
  anyone their data is gone.
- **Nothing purges old data on a schedule.** `app.retention.months` drives a
  *report* on `/admin/data-retention`; a person acts on it. That is deliberate —
  an automatic destroyer's failure mode is unrecoverable.
- **Password reset needs mail configured.** With `app.mail.enabled` false the
  page says so plainly rather than pretending, but an administrator then has to
  set passwords by hand. Worth enabling before any pilot with real candidates.

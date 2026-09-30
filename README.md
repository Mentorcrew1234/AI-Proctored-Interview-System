# AI-Based Proctored Interview System

A working prototype of an AI-assisted technical interview platform with
browser-based environment monitoring.

A recruiter schedules an interview → the candidate opens an invite link →
the browser monitors the environment using local AI models → an AI conducts a
voice interview → answers are transcribed, cleaned and evaluated → a report is
produced with scores, a deterministic recommendation, and proctoring
observations.

> **This is an academic prototype, not a production system.** It demonstrates a
> complete workflow end to end. It is not a security control, and its detection
> accuracy is browser-grade. See [Known limitations](docs/system/quality/LIMITATIONS.md) — that
> document is as much a part of the project as the code.

---

## Two products, one session

| | Product A — Proctoring | Product B — AI Interview |
|---|---|---|
| Runs | in the candidate's browser | on the server |
| Does | face presence, face count, person count, phone detection, head pose, browser focus | question generation, questions read aloud, speech-to-text, transcript cleaning, answer evaluation |
| Produces | debounced observation events | answers with four sub-scores each |

Both attach to the same `InterviewSession`, which is what makes a combined
report possible.

---

## Quick start

**Prerequisites:** **JDK 25** (`pom.xml` sets `java.version` to 25, so an older
JDK will not compile it), Node 20+ (built on 24), MySQL 8 or MariaDB 10.6+.
Maven is *not* required — the wrapper is included. For a real deployment, read
[docs/system/deployment/DEPLOYMENT.md](docs/system/deployment/DEPLOYMENT.md) first: the camera needs HTTPS.

### 1. Create the database

```sql
CREATE DATABASE proctor_interview CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 2. Supply credentials

Create `config/application.yml` in the project root (gitignored, and outside
`src/` so it is never packaged into the JAR):

```yaml
spring:
  datasource:
    username: root
    password: YOUR_MYSQL_PASSWORD

app:
  gemini:
    # Optional. Free key: https://aistudio.google.com/apikey
    # Leave blank to run entirely offline.
    api-key: ""
```

If MySQL is not on port 3306, set `DB_PORT`, or override
`spring.datasource.url` in the same file.

### 3. Build the frontend and run

```bash
cd frontend && npm install && npm run build && cd ..
./mvnw spring-boot:run          # Windows: .\mvnw.cmd spring-boot:run
```

Open <http://localhost:8080>.

### Demo accounts

| Email | Password | Role |
|---|---|---|
| `admin@demo.local` | `Admin@123` | Administrator |
| `recruiter@demo.local` | `Recruiter@123` | Recruiter |
| `candidate@demo.local` | `Candidate@123` | Candidate |

Seeded on startup, **per account and idempotently** — a missing one is recreated,
existing ones are left alone — so deleting a demo user brings it back on the next
restart. Turn it off with `app.seed.enabled: false`.
**Development credentials — change them before any real use.**

### Try the whole flow

1. Sign in as the **recruiter** → *New Interview* → pick the candidate, domain
   and question count → **Create**. (For many candidates at once, use
   *Bulk Schedule* — see [bulk scheduling](docs/system/features/BULK-SCHEDULING.md).)
2. Copy the generated invite link.
3. Open it in **Chrome or Edge** (speech recognition needs them), sign in as the
   **candidate**, allow camera and microphone, pass the device check.
4. Answer the questions by speaking. During the interview, try covering the
   camera or switching tabs — observations appear live on the left.
5. Finish, then view the report as the recruiter.

---

## Running without an API key

The system runs fully offline. With no Gemini key configured it uses a curated
question bank and a transparent heuristic scorer, and every stored evaluation
records which path produced it (`evaluator = LLM | FALLBACK`) so a report never
overstates how it was graded.

This is not only a convenience: the same fallback engages automatically if the
API quota runs out or the network drops **mid-interview**, so a candidate is
never stranded.

---

## Tests

```bash
./mvnw test                     # 591 backend tests
cd frontend && npx vitest run   # 140 browser-logic tests
```

The most interesting suites are `eventEngine.test.js` (the debounce and dedup
logic that decides what gets recorded about a candidate) and `ReportScoringTest`
(that proctoring observations can never lower a score). See
[docs/system/quality/TEST-PLAN.md](docs/system/quality/TEST-PLAN.md) for the manual checks that need a webcam.

---

## Documentation

| Document | Contents |
|---|---|
| **[System overview](docs/system/overview/SYSTEM-OVERVIEW.md)** | **start here** — the whole app in one document: features, flows, invariants, traps |
| [Architecture](docs/system/overview/ARCHITECTURE.md) | components, both pipelines, the event lifecycle |
| [Interview management](docs/system/features/INTERVIEW-MANAGEMENT.md) | naming, search, filters, tabs, sorting, paging, insights, edit/delete |
| [Bulk scheduling](docs/system/features/BULK-SCHEDULING.md) | Excel template, validation rules, the confirm-before-create flow |
| **[Scoring](docs/system/features/SCORING.md)** | how a mark is produced, and how to recompute one by hand |
| [Database](docs/system/reference/DATABASE.md) | ER diagram, tables, indexes, migrations |
| [API](docs/system/reference/API.md) | every endpoint, request and response shapes |
| [Limitations](docs/system/quality/LIMITATIONS.md) | measured accuracy, what this cannot do, future scope |
| [UI architecture](docs/system/overview/UI-ARCHITECTURE.md) | the page shell, sidebar, role-based navigation, theming |
| **[Deployment](docs/system/deployment/DEPLOYMENT.md)** | prerequisites, build, HTTPS, nginx, systemd, upgrades, troubleshooting |
| [VPS setup walkthrough](docs/system/deployment/VPS-SETUP-WALKTHROUGH.md) | new to this? — a blank Ubuntu server to a running HTTPS deployment, in order |
| [Email notifications](docs/system/features/EMAIL-NOTIFICATIONS.md) | SMTP setup and the three emails this system sends |
| [Test plan](docs/system/quality/TEST-PLAN.md) | automated coverage and manual checks |
| [Release readiness](docs/system/quality/RELEASE-READINESS.md) | what has actually been verified, and the pilot recommendation |
| [Improvement plan](docs/system/planning/IMPROVEMENT-PLAN.md) | the audit behind the recent phases, and what is left |
| [Attribution](docs/system/credits/ATTRIBUTION.md) | third-party components and licences |

---

## Project layout

```
src/main/java/com/project/proctorinterview/
├── ai/          LLM clients (Gemini + offline fallback) and prompts
├── answer/      answers, evaluation, transcript cleaning
├── auth/        JWT, user details, login endpoints
├── bulk/        bulk scheduling: Excel template, validation, batch creation
├── common/      enums, JSON converters, error handling
├── config/      security chains, typed configuration, seeding
├── interview/   interviews, sessions, the candidate exam API
├── proctor/     proctoring observations
├── question/    question generation and storage
├── report/      score aggregation, recommendation, reports
└── web/         Thymeleaf page controllers

src/main/resources/
├── db/migration/    Flyway schema
├── templates/       Thymeleaf pages
└── question-bank.json   offline fallback questions

frontend/src/
├── proctor/     detectors, event engine, uploader   ← the core of Product A
├── interview/   speech recognition and the question panel
└── screens/     instructions, device check, exam
```

---

## Technology

Spring Boot 4 · Java 25 · Spring Security 7 · Spring Data JPA · MySQL 8 ·
Flyway · Thymeleaf · React 19 · Vite 6 · MediaPipe Face Landmarker ·
TensorFlow.js COCO-SSD · Web Speech API · Google Gemini

Design decisions and the reasoning behind them are in
[docs/system/overview/ARCHITECTURE.md](docs/system/overview/ARCHITECTURE.md).

---

## Principles this project holds to

1. **Video never leaves the browser.** All detection is local; only short
   observation records are uploaded.
2. **A detection is an observation, not an accusation.** `PHONE_DETECTED` means
   a phone was detected — nothing more. This wording is enforced in the code,
   the UI and the report.
3. **The AI does not decide outcomes.** It produces sub-scores; weights and
   thresholds are configuration, applied in plain arithmetic.
4. **Proctoring can never reject a candidate.** At most it holds a result for
   human review.
5. **The raw answer is never modified.** Cleaning produces a second copy.
6. **Limitations are documented, not hidden.**

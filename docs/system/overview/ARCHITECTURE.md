# Architecture

## Shape of the system

A single Spring Boot application serving two different kinds of UI, plus one
outbound call to an LLM.

```
                          BROWSER (Chrome / Edge)
  ┌──────────────────────────────┐   ┌────────────────────────────────────┐
  │ Thymeleaf pages              │   │ React SPA at /exam/{token}         │
  │ login, admin, recruiter    │   │  ├ MediaPipe Face Landmarker  5fps │
  │ session cookie + CSRF        │   │  ├ COCO-SSD (person, phone)   1fps │
  │                              │   │  └ Web Speech: question read out,  │
  │                              │   │    answer spoken in                │
  └──────────────┬───────────────┘   └────────────────┬───────────────────┘
                 │                                    │  JWT
                 │        events + answers only — never video
                 ▼                                    ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │                    SPRING BOOT 4  (port 8080)                        │
  │  chain 1  /api/**        stateless JWT, JSON errors                  │
  │  chain 2  everything else  form login + session + CSRF               │
  │                                                                      │
  │  web ──► service ──► repository                                      │
  │            │                                                         │
  │            └── ai ──► Gemini REST ──┐                                │
  │                       └─ on any failure ─► offline fallback          │
  └──────────────────────────────┬───────────────────────────────────────┘
                                 ▼
                    MySQL 8 — schema proctor_interview
                    (Flyway-managed, Hibernate validates)
```

The React bundle is built by Vite into `src/main/resources/static/exam/`, so the
whole system ships as **one runnable JAR**.

### Why two UI technologies

The admin and recruiter screens are forms and tables — server-rendered
Thymeleaf is the least machinery that does the job, and it gets CSRF and session
handling for free.

The candidate screen is a different problem: two continuous inference loops, a
live event engine, a speech recogniser and a stateful interview flow. That is
genuinely a client application, so it is React.

### Layers

| Layer | Rule |
|---|---|
| Controllers (`web/`, `*Controller`) | thin adapters — bind, delegate, return |
| Services | **all business rules live here, once** |
| Repositories | Spring Data interfaces |

`InterviewService`, `ExamService` and `ReportService` are each called by *both* a
Thymeleaf controller and a REST controller. Neither duplicates a rule.

---

## Product A — the proctoring pipeline

```
getUserMedia → <video>            (never uploaded, never recorded)
   │
   ├─ face loop   every 200 ms ─→ FaceLandmarker ─→ faceCount, yaw, pitch
   └─ object loop every 1000 ms ─→ COCO-SSD      ─→ person count, phone + bbox
                    │
                    ▼
            normalised detection state
                    │
                    ▼
   ┌──────────────── EventEngine ─────────────────┐
   │  confidence gate                              │
   │  enter debounce → exit debounce                │
   │  minimum duration → discard if too brief       │
   │  one open event per type → no duplicates       │
   └────────────────────┬──────────────────────────┘
                        │  completed events only
                        ▼
              EventUploader (batch of 20, or every 10 s, retry-safe)
                        ▼
      POST /api/interview-sessions/{id}/proctor-events
                        ▼
                  proctor_events
```

**Two loops at different rates** because the models cost very different amounts.
Face landmarking is cheap enough to run five times a second; object detection is
not, so it runs once a second on its own timer and skips a tick if the previous
one is still running rather than queueing work.

### The event engine

`frontend/src/proctor/eventEngine.js` — the most important file in Product A.

A raw detector fires several times a second. Uploading that would mean thousands
of rows per interview, nearly all of it noise from a hand passing the lens or one
bad frame. The engine collapses it into *"a phone was visible for 5.2 seconds,
once"*.

Each rule is a small state machine:

```
IDLE ──condition true──► PENDING ──held for enterMs──► OPEN
  ▲                         │                            │
  │                  condition false             condition false
  └─────────────────────────┘                            │
  │                                        held clear for exitMs
  └───────────────────────────────────────────────────────┘
                                                          │
                                    duration ≥ minDurationMs ? emit : discard
```

Properties that matter, each locked in by a test:

- **Honest timing.** An event's start is backdated to when the condition really
  began, and its end is when the condition really stopped — not when the rule
  noticed. Durations reflect what happened, not detection lag.
- **No duplicates.** At most one open event per type. Re-triggering extends it.
- **No noise.** Anything under the minimum duration is discarded entirely.
- **Confidence gating.** A detection below its floor is treated as no detection.
- **No double-reporting.** `HEAD_TURN` is suppressed when no face is visible,
  because `NO_FACE` already describes that situation.
- **Crash tolerance.** An event open past 30 s is split, so losing the tab cannot
  lose the observation.

It is a pure module — no DOM, no timers, no network, time is injected. That is
what makes 24 tests possible without a browser.

### Thresholds

All in `frontend/src/proctor/proctorConfig.js`:

| Condition | Event | Min confidence | Enter | Exit | Min duration |
|---|---|---|---|---|---|
| 0 faces | `NO_FACE` | — | 3.0 s | 1.0 s | 3.0 s |
| ≥2 faces | `MULTIPLE_FACES` | — | 2.0 s | 1.5 s | 2.0 s |
| ≥2 persons | `MULTIPLE_PERSONS` | 0.60 | 3.0 s | 2.0 s | 3.0 s |
| `cell phone` | `PHONE_DETECTED` | 0.55 | 1.5 s | 2.0 s | 1.5 s |
| head ≠ CENTER | `HEAD_TURN` | — | 4.0 s | 1.5 s | 4.0 s |
| tab hidden | `TAB_SWITCH` | — | instant | on visible | — |
| window blurred | `WINDOW_BLUR` | — | 0.5 s | on focus | — |
| fullscreen exited | `FULLSCREEN_EXIT` | — | instant | on re-enter | — |

Head pose is **orientation only** — yaw and pitch from MediaPipe's facial
transformation matrix. No gaze, iris, lip or expression analysis: those are out
of scope by design, not merely unimplemented.

### Idempotent upload

Every event carries a client-generated `clientEventId` (a UUID). The server
skips ids it already holds and reports them as duplicates rather than errors.

This is what makes retrying safe, and retrying is what makes the pipeline
survive a flaky connection. Both halves are tested: the client resends identical
ids, and the server counts rather than duplicates them.

The **uniqueness constraint**, not a pre-read, is what actually enforces it: the
service checks the whole batch in one query and then catches the constraint
violation, so two overlapping batches sharing an id produce a *duplicate* rather
than a 500 that discards a batch which was mostly new.
`ProctorEventRetryService` re-ingests one event per `REQUIRES_NEW` transaction,
because the transaction that called it is already rolled back.

### The pipeline reports on itself

A session with no observations used to be indistinguishable from a session
nobody watched. The browser now posts its own coverage to
`POST /api/interview-sessions/{id}/monitoring-status` — whether both detectors
loaded, how many events it gave up uploading, and why — and the report derives a
verdict from it (`MonitoringCoverage`).

It describes the **observer, never the candidate**, and it changes the wording of
the report's explanation and nothing else: it can never move a score or a
recommendation, because a GPU fault is not the candidate's doing.

---

## Product B — the interview pipeline

```
POST /start
   └─► session created, interview → IN_PROGRESS

GET /questions   (first call only)
   └─► AiService ──► Gemini ──✗──► offline question bank
                       │
                       └──► questions persisted, source = LLM | BANK
                            fixed from here on

for each question:
   speak ──► Web Speech API ──► raw transcript (editable by the candidate)
        └─► POST /answers
              ├─ TranscriptCleaner  → clean text, filler count, word count
              ├─ AiService.evaluate → 4 sub-scores + feedback
              │      └─ on any failure → heuristic scorer, evaluator = FALLBACK
              └─ overall score computed in Java from configured weights

POST /complete
   └─► aggregate → recommendation → report; session + interview → COMPLETED
```

### Question generation

The prompt explicitly forbids definition questions and requires concrete
`expectedPoints` for each question, which then serve as the grading rubric.

**How many calls that costs depends on the interview's question mode**
(`interviews.question_mode`, falling back to `app.interview.question-mode`):

| Mode | Generation | Calls |
|---|---|---|
| `FIXED` | the whole set, on the first `GET /questions`, then fixed | one per interview |
| `ADAPTIVE` | the next question only, once the previous answer has been **scored** | one per question |

Either way a reload resumes the same interview rather than producing a new one:
FIXED re-serves the fixed set, ADAPTIVE re-serves everything generated so far and
generates the next only when one is actually due. `SYSTEM-OVERVIEW.md` §8 has the
state machine and the concurrency guarantee.

`expectedPoints` are **never sent to the browser** — they are the answer key.

### Transcript cleaning

Multi-word fillers are stripped first (a phrase must be matched before its
component words disappear), then single words, then stuttered repeats, then
spacing. Word boundaries are respected so `umbrella` survives `um`.

**The word list is deliberately short.** A listed word is removed wherever it
appears and the cleaned text is what the evaluator scores, so a word with any
common technical meaning would corrupt the answer being marked — `right`, `okay`,
`like` and `actually` are therefore *not* on it. Position-sensitive hesitation
is handled as a phrase instead (`", right"`). See `docs/system/features/SCORING.md`.

**The raw transcript is never modified.** Cleaning writes a second column. What
the candidate actually said is always recoverable.

### Evaluation and scoring

The model returns four sub-scores, feedback, strengths and weaknesses,
constrained by a JSON schema. It does **not** return an overall score — that is
computed in Java:

```
answer.overall  = 0.35·technical + 0.25·problemSolving + 0.20·communication + 0.20·relevance
session.<dim>   = Σ answer.<dim> / totalQuestions      ← unanswered counts as 0
report.overall  = same weights applied to the session dimensions

recommendation:  ≥ 70 → RECOMMENDED
                 50–69 → FURTHER_REVIEW
                 < 50  → NOT_RECOMMENDED
```

Weights and thresholds live in `application.yml`. Any result can be recomputed
by hand.

### How proctoring affects the outcome

Narrowly, and in one direction only:

- Observations **never** lower a score.
- If a configured trigger type occurred (`PHONE_DETECTED`, `MULTIPLE_FACES`,
  `MULTIPLE_PERSONS`), a `RECOMMENDED` result is **capped** at `FURTHER_REVIEW`
  so a human looks at it.
- Proctoring can **never** produce `NOT_RECOMMENDED`.

The reasoning: these detections are unreliable and ambiguous — measured false
positives are documented in [LIMITATIONS.md](../quality/LIMITATIONS.md). Escalating to a
human is the strongest action the system is entitled to take on that evidence.
The whole rule is configurable and can be switched off.

---

## Security

Two independent filter chains, because an API call and a web page need opposite
failure behaviour — JSON 401 versus a redirect to the login form.

| | `/api/**` | everything else |
|---|---|---|
| Auth | JWT bearer (HS256, jjwt) | form login |
| State | stateless | session |
| CSRF | not applicable | enabled |
| Failure | JSON `401` / `403` | redirect to `/login` |

Decisions worth stating:

- **Disabling a user revokes their token immediately.** The JWT filter re-reads
  the account per request rather than trusting the token until it expires.
- **An invite link alone is not access.** The signed-in candidate must be the one
  the interview was scheduled for. A stranger's link returns **404, not 403**, so
  it does not confirm the link exists.
- **Login gives the same response** for an unknown email and a wrong password, so
  accounts cannot be enumerated.
- Passwords are BCrypt. The hash is never serialised.
- Report access is enforced once in the service and shared by the REST and page
  controllers, so they cannot drift apart.
- **Repeated failed logins lock an account** for ten minutes (`LoginAttemptService`).
  Hooked through `AppUserDetails.isAccountNonLocked`, so one mechanism covers
  both chains — they authenticate through the same `ProviderManager`. Keyed by
  account rather than IP, in memory, and a locked account fails *identically* to
  a wrong password. It deliberately does **not** reach an already-issued JWT, so
  nobody can eject a candidate mid-interview by failing logins against their
  address.
- **Password reset** stores only the SHA-256 of a single-use token (30 minutes).
  SHA-256 rather than BCrypt on purpose: the token is 256 random bits, so there
  is no dictionary to slow down, and lookup is *by* the hash, which a per-row
  salt would make impossible.
- **A Content Security Policy pins every origin to `'self'`.** It keeps
  `'unsafe-inline'` and `'unsafe-eval'`/`'wasm-unsafe-eval'`, which are
  load-bearing for the inline handlers, the ~150 inline styles, TensorFlow.js and
  the MediaPipe runtime — so it does **not** stop injected script executing. What
  it stops is that script reaching anywhere else.
- **Scope is ANDed in the query, never filtered afterwards.** A recruiter's
  interview list, report list and candidate list are each restricted at the
  source; a result set that was ever unrestricted is one forgotten filter away
  from a leak.
- **Erasure is admin-only and candidates-only**, needs the address typed to
  confirm, and its log retains no name or email.

---

## Configuration

Everything tunable is in `application.yml` under `app.*`:

| Key | Purpose |
|---|---|
| `app.jwt.*` | signing secret, token lifetime |
| `app.gemini.*` | API key, model, timeout — blank key ⇒ offline mode |
| `app.transcript.*` | filler word and phrase lists — see the note above on why the word list is short |
| `app.interview.mode` | `NORMAL` / `DEMO` — what the browser draws, nothing else |
| `app.interview.question-mode` | `FIXED` / `ADAPTIVE`, the default for interviews that name none |
| `app.interview.ai-mode` | `MOCK` / `GEMINI` |
| `app.login-throttle.*` | attempts, window, lock duration |
| `app.password-reset.expiry-minutes` | how long a reset link lives |
| `app.retention.months` | retention window — drives a **report**, never a purge |
| `app.mail.*` | invitation and reset email; off by default |
| `app.report.scoring.*` | the four weights, both thresholds |
| `app.report.proctoring.*` | whether observations cap a result, and which types |
| `app.seed.*` | demo accounts |

Secrets go in `config/application.yml` at the project root — gitignored, loaded
at higher precedence than the packaged file, and outside `src/` so they are never
bundled into the JAR.

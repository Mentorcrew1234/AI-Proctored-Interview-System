# Test plan

Automated coverage plus the manual checks that need a human, a webcam, or a
network cable.

## Automated

```bash
.\mvnw.cmd test                 # backend
cd frontend && npx vitest run   # browser logic
```

| Suite | Covers |
|---|---|
| `DomainPersistenceTest` | every entity round-trips; JSON-in-TEXT converters; invite-token lookup; duplicate `client_event_id` detection |
| `JwtServiceTest` | issue/verify; rejects expired, tampered, foreign-signed and malformed tokens; refuses a short secret at startup |
| `AuthApiTest` | login, the role matrix, no account enumeration, immediate revocation when an account is disabled |
| `InterviewServiceTest` | scheduling rules, recruiter ownership, invite-token uniqueness |
| `RecruiterPagesTest` | page flow, validation redisplay, CSRF rejection |
| `ProctorEventApiTest` | batch ingest, **idempotent retry**, ownership, validation |
| `TranscriptCleanerTest` | filler removal without damaging real words; raw preserved |
| `ScoringPropertiesTest` | weighting and threshold boundaries (49/50/69/70) |
| `ReportScoringTest` | aggregation with unanswered = 0; the proctoring cap; explanation wording |
| `GeminiLlmClientTest` | schema-constrained parsing; 429, 500, malformed JSON and empty candidates all raise |
| `AiServiceFallbackTest` | every LLM failure degrades to offline scoring, labelled honestly |
| `SessionLifecycleTest` | reload, double-answer, cross-session question, completion, cancellation |
| `InterviewDurationTest` | the server-owned clock: 409 past the deadline, `TIME_EXPIRED`, the expiry sweep |
| `QuestionModeIntegrationTest`, `AdaptiveQuestionFlowTest`, `AdaptiveGeminiIntegrationTest` | FIXED still front-loads the set; ADAPTIVE generates one at a time, stops at `questionCount`, survives a refresh and a concurrent race |
| `PerInterviewQuestionModeTest`, `QuestionModePropertiesTest` | the per-interview choice, and `null` meaning "inherit the server default" |
| `QuestionProvenanceTest`, `QuestionHistoryTest` | model name and text hash persist; the avoid-list is bounded and context-scoped |
| `ReportReviewTest` | the human decision: append-only, scope, and that a review never alters the report |
| `DataRetentionTest` | erasure preview, ordering, and a log that keeps counts but no identity |
| `AuthHardeningTest` | login throttling across both chains, password reset, and the response headers |
| `InterviewMailServiceTest`, `MailTemplateRenderingTest`, `SchedulingEmailCoverageTest`, `MailPropertiesTest` | the three emails, their templates, every scheduling path, and "off by default" |
| `CandidateViewTest` | the candidates page: scope in the query, and 404 rather than 403 for a stranger |
| `eventEngine.test.js` | debounce, dedup, honest durations, confidence gate, long-event splitting |
| `eventUploader.test.js` | retry with stable ids, order preservation, bounded give-up |
| `faceDetector.test.js` | the face gate (minimum area, minimum separation) and the yaw/pitch axes |
| `browserEvents.test.js`, `useFullscreen.test.js` | tab, blur and fullscreen signals |
| `useCountdown.test.js` | ticking against wall-clock deltas, and resync from the server |
| `ExamScreen.test.jsx` | both completion paths, single completion under repeated clicks, the adaptive fetch-next flow |
| `QuestionPanel.speech.test.jsx` | questions read aloud: cancel-before-speak, StrictMode double-mount, and playback stopping when the microphone opens |

## Manual — needs a webcam

Log in as `recruiter@demo.local`, create an interview, open the invite link in
Chrome, sign in as `candidate@demo.local`, pass the device check.

| # | Action | Expected |
|---|---|---|
| M1 | Cover the camera ~4 s | one `NO_FACE`, duration ≈ 4 s |
| M2 | Have someone stand behind you ~4 s | `MULTIPLE_PERSONS`, possibly `MULTIPLE_FACES` |
| M3 | Hold a phone in view ~3 s | one `PHONE_DETECTED` with confidence and bbox |
| M4 | Look away ~5 s | one `HEAD_TURN` with a direction |
| M5 | Watch the first question appear | it is **read aloud** once, and only once. **Repeat question** reads it again; **Stop reading** silences it |
| M6 | Press **Start speaking** while the question is still being read | playback stops immediately, and the question's own words do **not** appear in the transcript |
| M5 | Switch tab and return | one `TAB_SWITCH` |
| M6 | Exit fullscreen | one `FULLSCREEN_EXIT` |
| M7 | Glance away for 1 s | **no** event (under threshold) |

Each sustained action must produce **exactly one** row, not one per frame:

```sql
SELECT event_type, COUNT(*), ROUND(AVG(duration_ms)) FROM proctor_events
WHERE session_id = ? GROUP BY event_type;
```

### Results — 2026-08-15 (real webcam, Chrome)

M1, M4, M5, M6 correct. M2 correct. M3 fires **only when the whole phone is
visible**; a half-visible phone is missed. Holding a phone in front of the face
produces a **false `MULTIPLE_FACES`**. Both are model limitations rather than
pipeline bugs — see the accuracy section of `CLAUDE.md`. Tuning is scheduled
after Phase 8.

## Manual — permissions and failure paths

| # | Action | Expected |
|---|---|---|
| P1 | Deny camera at the browser prompt | device check shows Camera "Not available", Start is disabled, clear message |
| P2 | Deny microphone, allow camera | interview proceeds; notice says answers will be typed |
| P3 | Use Firefox | device check flags speech recognition unsupported; typing still works |
| P4 | Sit in a dark room | device check warns about lighting before the interview starts |
| P5 | Unplug the camera mid-interview | monitoring degrades; the page does not crash |
| P6 | Disconnect the network mid-interview, reconnect | queued observations upload on reconnect with no duplicates |
| P7 | Disconnect and finish anyway | interview completes; the uploader gives up bounded rather than hanging |
| P8 | Reload mid-interview | same session, same questions, resumes at the first unanswered one |
| P9 | Open another candidate's invite link | 404, not 403 — does not confirm the link exists |
| P10 | Reopen a completed interview | "already been completed", no new session |
| P11 | Open a cancelled interview | clear message, no session created |

`P6` verification:

```sql
SELECT COUNT(*), COUNT(DISTINCT client_event_id) FROM proctor_events WHERE session_id = ?;
-- the two numbers must be equal
```

## Manual — LLM

| # | Action | Expected |
|---|---|---|
| L1 | No `app.gemini.api-key` | questions come from the bank, `aiGenerated: false`, interview completes |
| L2 | Valid key | `source = LLM`, `evaluator = LLM`, questions are scenario-based, never "What is X?" |
| L3 | Invalid key | falls back silently to offline, interview still completes |
| L4 | Pull the network after question 2 | remaining answers score `FALLBACK`; report discloses the mix |

**L2 has not been run** — no API key has been configured yet, so the live Gemini
path is unverified end to end. Its parsing and every failure mode are covered by
`GeminiLlmClientTest` against a stubbed transport.

---

## Camera review — the three things only a webcam can settle

Everything below needs a **real camera and a person**. No automated run
substitutes for any of it, which is why each is still open. Work through them in
one sitting: `C1` gives you `C2` and `C3` almost for free.

### Setting up

Run in DEMO mode so the detection state is visible on screen beside the
interview:

```bash
INTERVIEW_MODE=DEMO .\mvnw.cmd spring-boot:run
```

**Set the environment variable — do not edit `application.yml`.** The committed
default is `NORMAL` and must stay that way: `SessionLifecycleTest` asserts it,
because the dangerous mistake is silently running a real candidate in DEMO.

DEMO changes only what the browser *draws*. Thresholds, the event engine and
what is recorded are identical to NORMAL.

### C1 — Sit a full interview, end to end

**Why it is open:** every verification so far has been automated. Phases 11–19
all changed code in the candidate's path, and no human has completed an
interview on any of those builds.

Schedule one for yourself, join from the dashboard, complete the device check,
answer every question by **speaking** (not typing — speech is the part no
automated run can reach), finish, and open the report.

Worth watching for:

- Speech recognition actually transcribing, and the transcript being editable
- The countdown matching the server (refresh mid-interview; it should resume,
  not restart)
- The report's proctoring tab **not** saying "Monitoring incomplete" — if it
  does, the models did not load and the coverage feature is correctly telling
  you so

### Before C2 and C3: be alone in frame

**This is not optional, and it is what invalidated the first attempt.** In the
28 August run other people were in frame for 112 of the interview's 115 seconds,
which makes both checks below unreadable:

- head pose is taken from the **largest** face, so with company it may be
  measuring somebody else — no `HEAD_TURN` was recorded at all in that run;
- `MULTIPLE_FACES` firing is *correct* when two real people are visible, so it
  cannot be used to judge whether a phone caused a false one.

Nobody else in shot, for both checks.

### C2 — The yaw sign

**What yaw is.** Head rotation has three axes. **Yaw** is turning left/right, as
if shaking your head "no". **Pitch** is nodding up/down, "yes". (Roll — tilting
towards a shoulder — is not used.) The system reads yaw and pitch only, as
orientation; there is no gaze or eye tracking.

**What is already settled.** Phase 12 added tests that build rotation matrices
directly and confirm the extraction is correct: rotation about Y reads as yaw,
rotation about X reads as pitch, and neither bleeds into the other. The *maths*
is not in question.

**What is not settled — the sign.** `classifyHead` in
`frontend/src/proctor/faceDetector.js` says:

```js
if (yaw >  20) return 'RIGHT';
if (yaw < -20) return 'LEFT';
```

Whether a *positive* yaw really corresponds to turning right depends on
MediaPipe's own coordinate convention, and on whether the preview is mirrored.
Reading the code cannot answer it; only a camera can.

**How to check.** Alone in frame, in DEMO, watch the head-direction readout.
Face the camera, then turn your head to **your own right** and **hold it for at
least 6 seconds** — the rule needs 4 continuous seconds beyond 20°, so a glance
never registers.

Watch the live readout rather than waiting for the report: if the readout changes
to LEFT or RIGHT, the pose is being measured correctly and you have your answer
immediately, whether or not the event ends up long enough to record.

- Reads `RIGHT` → correct, nothing to change. Record that it was confirmed.
- Reads `LEFT` → the two labels are swapped. Swap `'RIGHT'` and `'LEFT'` in
  `classifyHead`, and update the note in `docs/system/quality/LIMITATIONS.md`.

**How much it matters: very little, but it should still be settled.** Nothing in
scoring reads the direction — `HEAD_TURN` fires identically either way, and the
recommendation never sees it. The consequence is confined to one detail field on
the observation timeline, where a recruiter could picture the wrong direction.
It is a labelling bug, not a scoring bug.

### C3 — The face-count gate

**Why it is open:** Phase 12 fixed the false `MULTIPLE_FACES` — a phone held in
front of the face made the landmarker report the occluded region as a second
face, so the most suspicious-looking gesture produced the most likely false
observation. The two thresholds in `frontend/src/proctor/proctorConfig.js` are
**reasoned and unit-tested, not measured**:

| Threshold | Value | Meaning |
|---|---|---|
| `minAreaFraction` | `0.015` | a face must cover at least 1.5% of the frame |
| `minCentreDistance` | `0.15` | two faces' centres must be this far apart (normalised) to count as two people |

Three tests, in DEMO so the face count is on screen:

| # | Do this | Expect | If wrong |
|---|---|---|---|
| C3a | Hold a phone directly in front of your face, covering part of it, for ~5 s | face count stays **1**; no `MULTIPLE_FACES` | Still 2 → thresholds too permissive; raise `minCentreDistance` |
| C3b | Have a second person sit beside you, both faces in frame, for ~5 s | face count **2**; `MULTIPLE_FACES` recorded | Stays 1 → too strict; lower `minCentreDistance` |
| C3c | Sit further back than normal, alone | face count stays **1** | Drops to 0 → `minAreaFraction` too high for your camera |

`C3b` matters as much as `C3a`. The gate can only ever *remove* a face, so
tuning it too hard turns a fixed false positive into a new false negative — a
real second person going unobserved.

### Recording the outcome

Whatever you find, update `docs/system/quality/LIMITATIONS.md` — it currently says the
thresholds are unmeasured and the yaw sign unconfirmed, and those sentences
should stop being true once you have looked. A confirmed "the numbers were
right" is as much a result as a correction.

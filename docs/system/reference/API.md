# API reference

Two authentication schemes, split by path:

| Paths | Auth | Errors |
|---|---|---|
| `/api/**` | `Authorization: Bearer <jwt>` | JSON |
| everything else | session cookie + CSRF token | HTML redirect |

## Error format

Every `/api/**` failure returns the same shape:

```json
{
  "timestamp": "2026-08-15T10:31:02.145Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/interview-sessions/3/answers",
  "fieldErrors": { "questionId": "questionId is required" }
}
```

`fieldErrors` is present only for validation failures.

| Status | Meaning here |
|---|---|
| 400 | validation failed, or a business rule rejected the request |
| 401 | missing, expired, tampered or revoked token |
| 403 | authenticated, but not allowed |
| 404 | not found — **also** returned when a resource exists but belongs to someone else |
| 409 | conflict with current state (already answered, already completed) |

> 404-instead-of-403 for another user's resource is deliberate: a 403 would
> confirm the resource exists.

---

## Authentication

### `POST /api/auth/login` — public

```json
{ "email": "candidate@demo.local", "password": "Candidate@123" }
```

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "expiresInSeconds": 14400,
  "user": { "id": 3, "email": "candidate@demo.local",
            "fullName": "Arun Kumar", "role": "CANDIDATE" }
}
```

`401` for both an unknown email and a wrong password, with an identical message.

### `GET /api/auth/me` — any authenticated role

Returns the same `user` object.

### `POST /api/auth/logout` — any authenticated role

`204`. JWTs are stateless; the client discards the token. Revocation before
expiry happens by disabling the account, which the filter honours immediately.

---

## Candidate exam

All require role `CANDIDATE`, and the signed-in candidate must be the one the
interview was scheduled for.

### `GET /api/exam/{inviteToken}`

Configuration and instructions for the invite link.

```json
{
  "inviteToken": "b2ebc5ea-...", "candidateName": "Arun Kumar",
  "recruiterName": "Priya Raman", "domain": "Java",
  "interviewType": "TECHNICAL", "candidateType": "FRESHER",
  "experienceYears": null, "questionCount": 5, "durationMinutes": 30,
  "language": "ENGLISH",
  "scheduledAt": "2026-08-20T04:30:00Z", "scheduledAtText": "20 Aug 2026, 10:00",
  "status": "SCHEDULED", "sessionId": null, "sessionStatus": null,
  "resultVisibleToCandidate": false, "interviewMode": "NORMAL"
}
```

`sessionId` is non-null if the interview was already started, which is how the
UI resumes after a reload. `404` if the token is unknown **or** belongs to
another candidate.

### `POST /api/exam/{inviteToken}/start`

```json
{ "cameraGranted": true, "micGranted": true, "browserInfo": "Chrome/141..." }
```

```json
{ "sessionId": 3, "questionCount": 5,
  "startedAt": "2026-08-15T10:00:00Z",
  "durationMinutes": 30,
  "deadline": "2026-08-15T10:30:00Z",
  "remainingSeconds": 1800 }
```

Creates the session and moves the interview to `IN_PROGRESS`. Calling it again
returns the **existing** session rather than creating a second one — with its
**original** `startedAt`, so a reload resumes the clock instead of restarting it.

`remainingSeconds` is what the countdown actually runs on: a duration computed by
the server, not an absolute deadline the browser must compare against its own
clock. `startedAt` and `deadline` are for display. A session resumed after its
deadline is finalised and refused with `409`.

| Status | Cause |
|---|---|
| 400 | `cameraGranted` false — the camera is required |
| 409 | interview cancelled, or already completed |

---

## Interview session

`{id}` is the session id. Role `CANDIDATE`, owner only.

### `GET /api/interview-sessions/{id}/questions`

What this generates depends on the interview's **question mode**
(`interviews.question_mode`, falling back to `app.interview.question-mode`):

| Mode | On this call |
|---|---|
| `FIXED` | generates the whole set on the first call, then returns the same set every time |
| `ADAPTIVE` | generates the **next** question if one is due, then returns everything generated so far |

Either way the call is safe to repeat: in ADAPTIVE it is a no-op unless a
question is genuinely due, which is what makes a refresh recover a question whose
response was lost.

```json
{
  "sessionId": 3, "totalQuestions": 5, "answeredCount": 1,
  "aiGenerated": false, "mockMode": true, "remainingSeconds": 1520,
  "questions": [
    { "id": 1, "sequenceNo": 1,
      "text": "You are building the checkout for an online store...",
      "difficulty": "EASY", "answered": true }
  ]
}
```

`aiGenerated` is `false` when the offline bank was used; `mockMode` is true when
the server is running `INTERVIEW_AI_MODE=MOCK`, which the exam screen shows as a
development notice. `remainingSeconds` re-syncs the countdown on every load.
`expectedPoints` are deliberately **not** included — they are the grading rubric.

### `POST /api/interview-sessions/{id}/answers`

```json
{ "questionId": 1, "rawTranscript": "So um, basically I would use an interface...",
  "durationSeconds": 47 }
```

```json
{ "answerId": 1, "sequenceNo": 1,
  "cleanTranscript": "So, I would use an interface...",
  "fillerCount": 4, "wordCount": 30,
  "answeredCount": 1, "totalQuestions": 5, "complete": false,
  "remainingSeconds": 1470 }
```

Scores are **withheld** — showing them mid-interview would let a candidate infer
how they are doing and adjust. An empty transcript is accepted and scores zero.
`remainingSeconds` corrects any drift in the browser's countdown after every
answer.

**In `ADAPTIVE` mode this request also generates the next question**, because the
answer it just scored is the context that generation needs. The next question's
text is not in this response; the client re-fetches `GET …/questions` for it.

| Status | Cause |
|---|---|
| 403 | the question belongs to another session |
| 404 | unknown `questionId` |
| 409 | already answered, or the session is no longer active |

### `POST /api/interview-sessions/{id}/proctor-events`

Batch upload. **Events only — no video, images or per-frame detections.**

```json
{ "events": [
  { "clientEventId": "11111111-1111-4111-8111-111111111111",
    "type": "PHONE_DETECTED",
    "startTime": "2026-08-15T10:31:02.145Z",
    "endTime":   "2026-08-15T10:31:07.380Z",
    "durationMs": 5235,
    "confidence": 0.91,
    "details": { "bbox": [220, 140, 90, 180] } }
] }
```

```json
{ "accepted": 1, "duplicates": 0, "total": 1 }
```

| Field | Rules |
|---|---|
| `clientEventId` | **required**, 8–64 chars, unique — the idempotency key |
| `type` | one of the ten event types |
| `startTime` | required, ISO-8601 |
| `endTime` | optional (null if still open at session end) |
| `durationMs` | optional; derived from the timestamps if omitted |
| `confidence` | optional, 0.0–1.0 |
| `details` | optional free-form object |

Max 200 events per batch. **Safe to retry** — an already-stored
`clientEventId` is counted as a duplicate, not an error and not a second row.
Batches are still accepted after completion, so the browser can flush its queue
as the session closes.

### `POST /api/interview-sessions/{id}/proctor-events/summary`

Per-type counts and total durations, for the live monitoring panel.

### `POST /api/interview-sessions/{id}/monitoring-status`

The browser reporting on **its own monitoring**, never on the candidate. It is
what lets a report distinguish "nothing was observed" from "nothing was
watching"; without it, both look identical and read as clean.

```json
{ "ready": true,            // both detection models loaded
  "droppedEvents": 0,       // cumulative, events abandoned after retries
  "note": null }            // why monitoring failed, in the browser's words
```

```json
{ "sessionId": 3, "ready": true, "droppedEvents": 0, "coverage": "NONE_OBSERVED" }
```

`coverage` is derived, never stored: `OBSERVATIONS_RECORDED`, `NONE_OBSERVED`,
`INCOMPLETE`, or `NOT_RECORDED` for a session predating this feature.

Sent when the detectors load or fail to load, whenever a batch is abandoned, and
once more as the interview closes (from `pagehide`, via `fetch(keepalive)` —
`sendBeacon` cannot carry the bearer token). Accepted after the session
completes, deliberately: the last and most important report arrives exactly when
an active-session check would start refusing, and a coverage report refused is a
coverage gap hidden.

Both fields are folded rather than overwritten, so repeated and out-of-order
calls are safe: `ready` is sticky-true, and `droppedEvents` takes the maximum, so
a stale report can never erase a loss already recorded.

### `POST /api/interview-sessions/{id}/complete`

Ends the interview and generates the report. Idempotent — a second call returns
the existing report rather than regenerating it.

```json
{ "sessionId": 3, "completed": true, "resultVisible": false,
  "overallScore": null, "recommendation": null,
  "completionReason": "CANDIDATE_FINISHED" }
```

Score and recommendation are included **only** when the recruiter enabled
`resultVisibleToCandidate`. `completionReason` is `CANDIDATE_FINISHED` or
`TIME_EXPIRED` — the client only ever asks to close, the server decides why — so
a candidate whose time ran out is told it was submitted for them rather than
being shown the same message as a voluntary finish.

---

## Reports

### `GET /api/reports/{sessionId}`

| Role | Access |
|---|---|
| ADMIN | any report |
| RECRUITER | their own interviews only |
| CANDIDATE | their own, **and** only if the recruiter shared the result |

Returns candidate and interview details, the five scores, the recommendation and
its explanation, a per-question breakdown (raw and cleaned transcripts, four
sub-scores, feedback, strengths, weaknesses, which evaluator graded it), plus
proctoring observation counts and a timeline.

| Status | Cause |
|---|---|
| 403 | recruiter who does not own it, or candidate whose result was not shared |
| 404 | no report yet, or another candidate's session |

---

## Server-rendered routes

Session + CSRF. Thymeleaf injects the CSRF token into forms automatically; a
scripted client must scrape it from the page.

| Route | Role |
|---|---|
| `/login`, `/logout` | public |
| `/forgot-password`, `/reset-password` | public — necessarily so; see below |
| `/` | any — redirects to the role's landing page |
| `/admin/dashboard`, `/admin/users`, `/admin/users/new`, `/admin/interviews` | ADMIN |
| `/admin/ai-health` | ADMIN |
| `/admin/data-retention` | ADMIN |
| `/admin/users/{id}/data`, `POST /admin/users/{id}/data/delete` | ADMIN |
| `/admin/users/{id}/edit`, `POST /admin/users/{id}` | ADMIN — corrects name and profile detail only; email, role, password and enabled have no field on the request |
| `/recruiter/dashboard`, `/recruiter/interviews`, `/recruiter/interviews/new` | RECRUITER |
| `/{admin\|recruiter}/interviews/new-multi`, `POST …/interviews/multi` | ADMIN, RECRUITER |
| `/{admin\|recruiter}/interviews/{id}/{edit,delete}`, `…/bulk-{edit,delete}` | ADMIN, RECRUITER |
| `/{admin\|recruiter}/reports`, `/{admin\|recruiter}/interviews/export.xlsx` | ADMIN, RECRUITER |
| `/{admin\|recruiter}/candidates`, `/{admin\|recruiter}/candidates/{id}` | ADMIN, RECRUITER |
| `/interviews/{id}` | ADMIN, RECRUITER |
| `/reports/{sessionId}`, `/reports/{sessionId}/export.pdf` | ADMIN, RECRUITER, CANDIDATE (own, if visible) |
| `POST /reports/{sessionId}/review` | ADMIN, RECRUITER |
| `/scheduling/bulk/**` | ADMIN, RECRUITER — see [bulk scheduling](../features/BULK-SCHEDULING.md) |
| `/exam/{inviteToken}` | public shell — the React app; its API calls enforce access |

### Password reset

`/forgot-password` and `/reset-password` are reachable **signed out**,
necessarily: someone who cannot log in must not be redirected to the login page.

Requesting a reset **always reports the same thing**, whatever the address —
real, unknown or disabled. Saying "no such account" would make the page an
account-enumeration tool, which the login and the invite link both already
refuse to be. The token is single-use, expires in 30 minutes, and only its
SHA-256 is stored; requesting again supersedes any earlier link.

### Recording a decision on a report

`POST /reports/{sessionId}/review` records `ADVANCED`, `ON_HOLD` or `DECLINED`
plus an optional note. It is **append-only** — a revised decision keeps the
earlier one — and it never alters the report's scores, recommendation or
explanation.

A **candidate is refused with 404, not 403**, even for their own report and even
when their scores are visible to them: they have no business learning the
facility exists.

### Candidates

`/{admin|recruiter}/candidates` lists candidates within the caller's scope, with
interview and completion counts; `?search=` matches name and email.
`/{admin|recruiter}/candidates/{id}` shows one candidate and their interviews —
both routes are role-prefixed; there is no unprefixed `/candidates`.

A recruiter sees only candidates they scheduled and only their own interviews
with them — **including in the counts**, so the page cannot disclose that another
recruiter is also interviewing that person. A candidate the caller has never
interviewed returns **404, not 403**. Candidates are refused both routes.

### Data retention and erasure

`/admin/data-retention` reports what is past the retention window and lists the
erasure log; nothing on it deletes anything, and there is no scheduled purge.
`/admin/users/{id}/data` previews exactly what would be erased for one
candidate, and the POST erases it after the candidate's email is typed to
confirm.

Only a **candidate** can be the subject — staff own interviews rather than being
recorded by them. The erasure log deliberately retains no name or email.

---

## Event types

| Type | Meaning |
|---|---|
| `FACE_PRESENT` | a face became visible (baseline marker) |
| `NO_FACE` | no face detected |
| `MULTIPLE_FACES` | more than one face detected |
| `MULTIPLE_PERSONS` | more than one person detected |
| `PHONE_DETECTED` | an object classified as a mobile phone was detected |
| `OBJECT_DETECTED` | reserved for future object classes |
| `HEAD_TURN` | head held away from centre |
| `TAB_SWITCH` | the tab became hidden |
| `WINDOW_BLUR` | the window lost focus |
| `FULLSCREEN_EXIT` | fullscreen was exited |

Every one of these is an **observation**. `PHONE_DETECTED` means a phone was
detected — it does not mean the candidate did anything wrong.

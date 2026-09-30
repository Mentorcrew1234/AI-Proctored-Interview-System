# Improvement plan

Written after a full audit of the working tree at `9827c4d`, with both suites
green (406 Java tests, 69 JavaScript tests). It records what is actually wrong
with the system today, and the order in which it is worth fixing.

Two rules shape the ordering:

1. **Honesty first.** `LIMITATIONS.md` opens by saying a proctoring system that
   overstates what it can do is worse than one that admits its limits. Anywhere
   the system is currently quiet about something a person would act on outranks
   anything that is merely unfinished.
2. **No scope growth.** Several genuinely valuable ideas are recorded in
   `LIMITATIONS.md` under *Future scope* and are deliberately **not** planned
   here — see "Deliberately out of this plan" at the end.

---

## Findings

Ordered by value for effort, not by severity alone.

### 1. The report cannot distinguish "clean" from "not watched"

The most serious finding, and the only one that makes the system misleading
rather than merely limited.

`RecommendationEngine` computes `integrityFlag = !observed.isEmpty()`, so a
session with zero proctoring events reads as clean. There are four ways to reach
zero events without the session being clean, and none of them leaves any record:

- **Model load failure is client-only.** `useProctoring` sets `status.error` in
  React state and stops. Nothing reaches the server, the interview completes
  normally, and in `NORMAL` mode there is no monitor panel for anyone to notice
  it on.
- **Dropped uploads are silent.** `EventUploader` abandons a batch after five
  consecutive failures and increments `stats.failed`. Nothing reads
  `stats.failed` — not the UI, not the server, not the report.
- **No final drain on tab close.** There is no `pagehide` handler, so up to ten
  seconds of queued events plus any still-open event are lost when the tab goes
  away.
- **The camera is checked once.** `ExamService` refuses to start without
  `cameraGranted`, but nothing confirms frames kept arriving afterwards.

The rest of the codebase is scrupulous about this distinction. This gap reads as
an oversight, not a decision.

### 2. Detector tuning, deferred since Phase 8

Unchanged and still true in the code:

- A phone held in front of the face produces a false `MULTIPLE_FACES`, because
  the landmarker treats the occluded facial region as a second face. This is the
  most misleading current failure, and the fix is already known: require a
  minimum face box area and a minimum separation before `faceCount >= 2` counts.
- Face bounding boxes are computed **only** when the detector is constructed
  with `withBoxes` (DEMO). The fix above needs them always — which costs
  nothing, because a box is min/max over landmarks the model already returned on
  that frame. No extra inference.
- `eulerFromMatrix` and `classifyHead` are exported pure functions with **no
  tests**, and the yaw sign (LEFT/RIGHT possibly swapped) is still unconfirmed.
  Reading the maths settles the axes — it is the standard `R = Rz·Ry·Rx`
  extraction, so `atan2(-r20, hypot(r21, r22))` genuinely is rotation about Y —
  but the *sign* depends on MediaPipe's coordinate convention, which only a real
  camera can settle.

### 3. Idempotent ingest is read-then-write, not atomic

`ProctorEventService.ingest` calls `existsByClientEventId` once per event and
then `saveAll`. Two overlapping batches carrying the same id both pass the
check, the second insert trips `uk_proctor_events_client_event_id`, and the
whole batch rolls back as a 500.

The codebase already knows the fix — `QuestionService` and
`InterviewSessionController` both catch `DataIntegrityViolationException`. It was
simply never applied to the one place the design leans on hardest (design
decision 2: idempotent ingest is what makes retry-on-failure safe).

The per-event `exists` is also up to twenty round trips per batch where one
`findAllByClientEventIdIn` would do.

### 4. Authentication gaps

`LIMITATIONS.md` already admits no login rate limiting and no account lockout.
It does not admit:

- **There is no password reset flow at all.** A candidate created by the bulk
  upload who loses their emailed password has no route back except an admin.
  This became much cheaper to build once the invitation email added SMTP support
  in `9827c4d`.
- The JWT lasts 240 minutes with no revocation list. Disabling the account
  revokes it (design decision 4); nothing else does.
- No CSP or security-header configuration in `SecurityConfig`.

### 5. No deployment documentation

`docs/` holds twelve files and no runbook, although the system runs on a shared
VPS. Nothing records that HTTPS is mandatory (`getUserMedia` needs a secure
context), how to size the JVM heap on a RAM-capped host, what differs between
MariaDB and the MySQL 8 the Flyway baseline was written against, or that a cold
exam load ships roughly 55 MB of models and WASM.

### 6. Documentation drift

Small, but this project treats documentation as a deliverable:

- `CLAUDE.md` claims "386 Java + 67 JavaScript"; the real figures are **406**
  and **69**.
- `RELEASE-READINESS.md` names `ff9e30f` as the last code-changing commit, but
  `9827c4d` changed production code after it. The readiness snapshot therefore
  predates the newest feature, and email appears nowhere in its verification
  table.
- `CLAUDE.md`'s status table has no row for the email feature.
- V6 (question provenance) joins V5 among the migrations that `ARCHITECTURE.md`,
  `API.md` and `DATABASE.md` do not cover.

### 7. Frontend test coverage is concentrated in the pure modules

At the time of the audit — tested: `eventEngine` (24), `eventUploader` (13),
`ExamScreen` (19), `useCountdown` (7), `interviewMode` (6); untested:
`faceDetector.js`, `objectDetector.js`, `useProctoring.js`, `browserEvents.js`,
`useFullscreen.js`, `DemoMonitor`, `DetectionOverlay`.

That was a reasonable place to have stopped — the tested modules are the pure,
deterministic ones, which is exactly where unit tests pay — but it meant findings
1 and 2 both lived in untested code.

**Partly addressed in Phase 12**: `faceDetector.js` now has 20 tests, and
`eventUploader` gained 6 more in Phase 11. The rest remain untested, all of them
bound to a model, a timer or the DOM, which is why they were left.

---

## Plan

Each phase follows the established rhythm: implement, run, verify, document,
commit.

### Phase 11 — Proctoring coverage, so a report is honest about what it saw ✅ done

Fixes finding 1. No new detection, no new model, no new inference — the change
records what already happened.

**Verified:** V7 applied against real MySQL with `ddl-auto: validate` passing;
all 29 pre-existing sessions read as `NOT_RECORDED` rather than being relabelled
failures. Every coverage transition driven live through the running application
(unconfirmed → `INCOMPLETE`, models loaded → `NONE_OBSERVED`, drops reported →
`INCOMPLETE`, and a stale report neither un-saying `ready` nor erasing a loss).
The report page and the exported PDF both carry the caveat, and the old sentence
"Nothing was detected during this interview" no longer appears. Verification data
removed afterwards; 29 sessions, zero orphans, the borrowed interview restored to
`SCHEDULED`. Suites: 419 Java, 75 JavaScript.

- Migration **V7** plus the matching entity change: the session records whether
  the detectors ever became ready, how many events the client gave up on, and a
  short note if monitoring failed.
- A small endpoint on the candidate JWT chain for the browser to report that
  status. `useProctoring` calls it when the models load or fail to load, and
  again at the end of the interview.
- A `pagehide` drain using `fetch(keepalive: true)`. **Not** `sendBeacon`, which
  cannot carry an `Authorization` header.
- The report gains a derived, three-state **coverage** value: observations
  recorded / none observed / monitoring incomplete. Derived, never stored, from
  the session's own columns and the event count — the same reasoning that keeps
  the interview deadline derived.
- The recommendation is **deliberately unchanged**. Incomplete monitoring is not
  the candidate's fault and must not cost them a score; it is surfaced
  prominently so that the human who decides can see it. Observations still only
  ever cap at `FURTHER_REVIEW` (design decision 10).

### Phase 12 — Detector tuning ✅ done

Fixes finding 2, the item deferred by the user after Phase 8.

**Verified:** 20 new tests on `faceDetector`, the module that previously had
none, including the regression the gate exists for (a phantom face on top of a
real one is rejected; two people genuinely apart are both kept). The yaw *axes*
are now settled — tests build rotation matrices and confirm rotation about Y
reads as yaw and about X as pitch with no bleed. The yaw **sign** remains open
and needs a real camera; recorded in `LIMITATIONS.md` rather than guessed.

- Compute face boxes unconditionally and gate `faceCount >= 2` on a minimum box
  area and a minimum separation between boxes.
- Unit-test `eulerFromMatrix` and `classifyHead`, then settle the yaw sign in
  one real-camera session and record the answer either way.
- Leave the 0.55 phone confidence floor **alone**. Lowering it trades a
  documented miss for an undocumented false positive on any dark rectangle.

### Phase 13 — Ingest atomicity and batch efficiency ✅ done

Fixes finding 3.

**Verified:** two concurrent identical batches both return 200 with the events
stored exactly once and no duplicate `clientEventId` — previously one would have
been a 500 discarding the whole batch. Also covers a duplicate id *inside* one
batch, which no pre-read can catch, and a 50-event batch checked in one query.

- Catch `DataIntegrityViolationException` per event and count it as a duplicate,
  matching `QuestionService`.
- Replace the per-event `exists` with a single `findAllByClientEventIdIn`.
- Test that concurrent identical batches produce `{accepted, duplicates}` and
  never a 500.

### Phase 14 — Authentication hardening ✅ done

Fixes finding 4.

**Verified:** 21 new tests, plus a live run against the running application —
five failed logins lock the account, the correct password is then refused with
the *identical* message a wrong one gives, and a different account is
unaffected. The CSP was verified in a real browser driving the actual exam
screen: all nine WASM and model assets served, no CSP violation logged, and the
browser reported `ready:true` coverage. That was the real risk in this phase —
TensorFlow.js and MediaPipe need `unsafe-eval` and `wasm-unsafe-eval`, so a CSP
could have broken the most important flow in the app with every test still
green.

- Login attempt throttling and a simple lockout.
- A password reset flow over the SMTP support the invitation email already
  added.
- Security headers and a CSP in `SecurityConfig`.

### Phase 15 — Deployment runbook and documentation reconciliation ✅ done

Fixes findings 5 and 6.

**Verified:** `docs/system/deployment/DEPLOYMENT.md` written, covering the HTTPS requirement
(`getUserMedia` needs a secure context, so a certificate is a prerequisite not a
hardening step), heap sizing, MariaDB notes, the ~55 MB cold exam load, a systemd
unit and a post-deploy checklist. Test counts corrected across `CLAUDE.md`, and
the email feature added to its status table.

- A new `docs/system/deployment/DEPLOYMENT.md`.
- Correct the test counts in `CLAUDE.md`, add the email row to its status table,
  and re-cut `RELEASE-READINESS.md` against the current commit with email in its
  verification table.

### Phase 16 — A human sits a full interview ⬜ **needs you**

Not something automation can stand in for. Together with the yaw sign and the
face-gate thresholds, this is the whole of what remains — and all three are one
sitting at a webcam.

**The step-by-step is in `docs/system/quality/TEST-PLAN.md`, "Camera review".** It explains what
yaw is, exactly what to do, what each outcome means, and which file to change if
something is wrong.

### Phase 23 — Recruiter-scoped candidate page ✅ done

The last item on the deferred list that was a real feature rather than polish,
and `LIMITATIONS.md` had already said why it was missing: no recruiter-scoped
candidate query existed. Adding the page meant adding the query.

The subtle part is the **counts**, which are the viewer's rather than the
candidate's: a recruiter looking at someone two recruiters have interviewed sees
one interview, not two. The true total would disclose that a competitor for that
hire exists, which the interview list has never done.

**Verified:** 19 tests plus a live run — list, detail with its scope caveat,
search with no matches, and a candidate refused with 403.

### Phase 22 — Any domain, and a bank worth falling back to ✅ done

Asked to add "a few more domains", the better answer was to stop having a fixed
list at all. Domain is free text; the AI generator always could take any topic,
and only the offline bank needed a familiar name.

The real problem was underneath: an unknown domain fell to the *Software
Engineering* questions, which is a domain in its own right, so a Rust candidate
was quietly asked about estimation. There is now a genuinely neutral `_default`
set. The bank went from 70 questions to **230** across 17 technical domains,
uniform 4 easy / 4 medium / 4 hard.

Bulk upload also stopped rejecting unknown domains — it had been able to do
*less* than the single form, the opposite of the standing rule.

### Phase 21 — The filler list was changing what an answer means ✅ done

Asked whether the filler list was long enough, the answer was that it was both
too short **and too aggressive**, and the second half mattered far more. A
listed word is stripped wherever it appears and the cleaned text is what the
evaluator scores, so "that's the right approach" was reaching the evaluator as
"that's the approach".

`right`, `okay`, `like` and `actually` came off the list; position-sensitive
hesitation became phrases. Fixing it exposed a real bug in `phrasePattern`, and
two test gaps — the unit test built its own lists and so never saw the shipped
configuration at all.

### Phase 20 — Tests for the browser-signal modules ✅ done

Closes most of finding 7. `browserEvents.js` produces three of the eight event
types — `TAB_SWITCH`, `WINDOW_BLUR` and `FULLSCREEN_EXIT` — and `LIMITATIONS.md`
describes those three as the **most reliable** detections in the system, because
they come from deterministic browser APIs rather than inference. Everything
downstream trusts them accordingly, and they had no tests at all.

29 tests added: 15 for `browserEvents.js`, 14 for `useFullscreen.js`. The
behaviours worth naming are the ones a reader would not guess:

- a tab switch fires **both** `visibilitychange` and `blur`, and recording each
  would report one action twice on a timeline a human reads about a candidate;
- `useFullscreen` reads the real state back after `requestFullscreen` resolves,
  because a resolved promise is not the same as fullscreen having happened;
- "not in fullscreen" and "fullscreen is forbidden" are kept apart, so a
  candidate is never offered a retry button that can never work.

Still untested, deliberately: `objectDetector.js` and `useProctoring.js`, both
bound to a model or a timer.

### Phase 19 — Per-interview question mode ✅ done

The last of the three deferred items that was a *constraint* rather than new
capability: adaptive questioning already worked, but choosing it was one
server-wide setting that needed a restart, so one drive could not use it while
another used a fixed set.

`interviews.question_mode` is nullable and null means **inherit the server
default** — which is honest for the 48 interviews scheduled before the column
existed, and is a genuine third option on the form rather than a missing answer.

**Verified:** 11 tests plus a browser run over all three forms — three
interviews scheduled minutes apart carrying ADAPTIVE, FIXED and inherit, each
detail page showing the *resolved* mode, and the edit form reopening on what was
chosen. Confirmed in the database, then removed.

The suite caught one real break: the multi-candidate form binds
`MultiScheduleRequest`, not `CreateInterviewRequest`, so adding the control to
the shared template threw a parse error until that DTO gained the field too.

### Phase 18 — Data retention and erasure ✅ done

**Not in the original plan**, chosen from the deferred list for the same reason
as Phase 17: it closes a gap the documentation already admitted rather than
adding capability. `LIMITATIONS.md` said "No data retention policy or deletion
endpoint", and for a system that stores transcripts, camera-derived observations
and scores about a person, that was the largest one left.

**Verified:** 24 tests, plus a browser run driving the real admin UI end to end —
preview, a wrong confirmation destroying nothing, the erasure itself, and the
log. Confirmed at the database level afterwards: zero probe accounts, zero probe
transcripts, zero orphan rows anywhere.

That run found a real bug the suite could not: `deletionLog()` handed entities to
the template, which threw mid-render **after the response was committed** — the
half-written-page trap documented in `CLAUDE.md`. The suite missed it because it
only ever rendered the page with an *empty* log. Fixed by mapping to records
inside the transaction, with a regression test that renders a populated log.

**What it deliberately is not:** no scheduled purge, no candidate-facing request
flow, no anonymisation.

### Phase 17 — Recording the human decision ✅ done

**Not in the original plan**, and taken from the deferred list deliberately, so
the reasoning is worth stating. It was chosen because it is a *gap* rather than
an addition: every report ends with "advisory input for a human decision, not a
hiring decision", and there was nowhere to record that decision. The system
deferred to a person and discarded their answer — the same shape as finding 1,
where the system made a claim about itself that was not yet true.

It also needs no new AI, no new models, and nothing from the out-of-scope list.

**Verified:** 20 tests, plus a browser run covering both halves that unit tests
cannot: staff record a decision, revise it, and see the earlier one preserved;
the candidate — whose *scores* are visible to them — never sees the Decision tab,
the wording, or the history. One real bug was found and fixed by that run: the
standing decision's reason was rendered only once it had been superseded, which
is precisely backwards. A regression test now covers it.

**What it is not:** a workflow. No assignment, no notification, no approval
chain, no second-reviewer requirement.

`RELEASE-READINESS.md` already names this as an open condition, and every phase
above changes code in the candidate's path, so it belongs last. No automated run
substitutes for it.

---

## Deliberately out of this plan

All of these are real, all are recorded in `LIMITATIONS.md` under *Future
scope*, and all are larger than the prototype's remit. Building them would be
scope growth, not repair:

Whisper for self-hosted speech-to-text · multi-language support · calibrating
the scoring against human graders · confidence bands on the report · fairness
evaluation across demographic groups · sortable table headers and saved filter
views.

*(Four items left this list and were built, each because it closed a gap the
documentation already conceded rather than adding capability: the recruiter
review workflow as Phase 17, data retention and deletion as Phase 18,
per-interview question mode as Phase 19, and the recruiter-scoped candidate page
as Phase 23.)*



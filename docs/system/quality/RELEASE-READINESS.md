# Release readiness

Final readiness pass for the AI-Based Proctored Interview System, recording what
has actually been verified and what has not. It is a snapshot, not a promise:
where something was checked by automation rather than by a person, this document
says so.

**Re-cut at `f2e7c15`**, after Phases 11–19, and refreshed through Phase 23. The previous version named
`ff9e30f` and predated all of them, so its figures and its conclusions described
a system that no longer existed. Nothing below is carried over unchecked.

**Last code-changing commit:** `165b5d0` — *Phase 23: give candidates a page of
their own, scoped to who is looking.*

---

## Implementation scope

Everything in `CLAUDE.md`'s status table is implemented: scheduling (single,
multi-candidate and bulk-from-Excel), invite links, the proctoring pipeline and
event engine, the voice/typing interview, LLM evaluation with an offline
fallback, deterministic scoring and recommendation, the report, the
admin/recruiter/candidate UI, per-interview timed tests, NORMAL/DEMO execution
modes, and per-interview FIXED/ADAPTIVE question modes.

Added since the previous readiness pass:

| Phase | What it added |
|---|---|
| 11 | Monitoring coverage (V7) — a report can say "not observed" instead of implying "observed and clean" |
| 12 | Detector tuning — the false `MULTIPLE_FACES` from a phone held at the face |
| 13 | Idempotent ingest enforced by the unique constraint, not a pre-read |
| 14 | Login throttling, password reset (V8), and a Content Security Policy |
| 15 | `docs/system/deployment/DEPLOYMENT.md` |
| 17 | The human decision on a report (V9) |
| 18 | Data retention and erasure (V10) |
| 19 | Per-interview question mode (V11) |
| 20 | Tests for the browser-signal modules |
| 21 | Filler list corrected — it was changing what an answer means |
| 22 | Any domain, and an offline bank worth falling back to |
| 23 | Recruiter-scoped candidate page |

Phase 16 — a human sitting a full interview — remains open and is the subject of
most of this document's caveats.

## FIXED and ADAPTIVE status

Both work end to end and are exercised by the automated suites and by a real
browser.

- **FIXED** is still the default and remains the more heavily exercised path.
- **ADAPTIVE** generates one question at a time from the previous answer's
  Java-computed score.
- **The choice is now per interview** (Phase 19, V11). `interviews.question_mode`
  is nullable, and null means *inherit the server default* rather than unknown,
  so every interview scheduled before V11 behaves exactly as it did.

`FIXED` staying the default is deliberate, not an oversight: it is the mode with
the longer verification history. **No human candidate has sat an adaptive
interview** — every adaptive run has been automated.

## Gemini and fallback status

- Timeout remains **8 seconds** (`app.gemini.timeout-seconds`), unchanged, and
  still marginal for the current model — see `docs/system/quality/LIMITATIONS.md`.
- `INTERVIEW_AI_MODE` defaults to **MOCK**, which never calls Gemini at all.
- Live Gemini was verified in Phase 6.5: real successes, real fallbacks and a
  real timeout, each correctly classified by `AiHealthRegistry.classify`.
- The fallback is per call, so an interview always completes.

## Browser verification status

Driven in Chromium against the running application, with a fake camera where a
camera was needed. Each run is recorded against the phase that prompted it.

| Phase | Verified in a real browser |
|---|---|
| 9.1 | The full candidate flow, both question modes, refresh recovery, the lost-response `409` path |
| 11 | Every monitoring-coverage transition, and the caveat reaching both the page and the PDF |
| 12–14 | **The CSP against the actual exam screen** — all nine WASM and model assets served, no violation logged, coverage reported `ready:true` |
| 14 | Login lockout live: five failures, then the correct password refused with an identical message |
| 17 | Recording, revising and preserving a decision; the candidate seeing none of it |
| 18 | Erasure end to end: preview, a wrong confirmation destroying nothing, the deletion, the log |
| 19 | All three scheduling forms, three interviews carrying different modes, the resolved mode on each detail page |
| 22 | A free-text domain accepted and kept verbatim, a known one folded to its canonical spelling, both findable in the filter |
| 23 | The candidate list and detail page, their scope caveat, search with no matches, and a candidate refused with 403 |

**Two of those runs found real defects the suites had missed**, both of the same
shape — a template reading something that was not there, failing *after* the
response was committed:

- Phase 17: the standing decision's reason rendered only once it had been
  superseded.
- Phase 18: the erasure log handed entities to the template; the suite had only
  ever rendered that page with an *empty* log.

Both are fixed and both now have regression tests.

**Still not covered by any automated run:** speech recognition (Playwright's
Chromium cannot reach the Web Speech backend, so answers are typed), audible
speech synthesis (the unit tests assert that every `speak` is preceded by a
`cancel`, but nothing listens to the audio), and detector accuracy against a
real face.

## Security verification status

- Two filter chains behave as designed: `/api/**` returns `401`/`403` as JSON;
  everything else redirects an anonymous visitor to the login page.
- A signed-in candidate is refused `/admin/**`, `/recruiter/**`, `/interviews/**`
  and `/scheduling/**` with `403`.
- Another candidate's session returns `403`; another candidate's **invite link
  returns `404`**, so it does not confirm the interview exists.
- **Login throttling** (Phase 14) covers both chains through one mechanism, and a
  locked account fails identically to a wrong password. It deliberately does not
  reach an already-issued JWT, so nobody can eject a candidate mid-interview by
  failing logins against their address.
- **Password reset** stores only the SHA-256 of a single-use token; requesting
  always reports the same thing whatever the address.
- **A candidate can never reach the decision facility** on their own report, even
  when their scores are visible to them — refused with 404, not 403.
- **Erasure is admin-only and candidates-only**, requires the address typed to
  confirm, and its log deliberately retains no name or email.
- No API key or credential appears in rendered HTML, API responses or error
  messages; `/config/` is correctly gitignored.
- No raw exception or stack trace reaches the candidate.

**The CSP does not stop injected script from executing** — `'unsafe-inline'` and
`'unsafe-eval'`/`'wasm-unsafe-eval'` are load-bearing for the inline handlers,
the ~150 inline styles, TensorFlow.js and the MediaPipe runtime. What it does is
pin every origin to `'self'`. Stated plainly in `SecurityConfig` rather than
left implied.

## Automated test results

Run at this commit, with no test modified or skipped:

| Suite | Command | Result |
|---|---|---|
| Backend | `.\mvnw.cmd -o clean test` | **591 tests — 0 failures, 0 errors, 0 skipped** |
| Frontend | `npx vitest run` | **140 tests across 9 files — all passing** |
| Production build | `npm run build` | **Success** |

Up from 386 + 69 at the previous pass. The build still emits one known,
pre-existing warning: the exam bundle exceeds Vite's 500 kB chunk hint, because
MediaPipe and COCO-SSD are bundled so an interview needs no internet.

**The suites do not verify migrations.** The test profile uses H2 with Flyway
disabled, so V7–V11 were each verified by actually starting the application
against MySQL and watching Flyway migrate with `ddl-auto: validate` passing.

## Database cleanup

Every phase that created verification data removed it afterwards and checked for
orphans. At this commit: **48 interviews, 31 sessions, 31 reports, 21 users, 0
report reviews, 0 data deletions, and zero orphan rows** across sessions,
questions, answers and reports.

Deliberately preserved: the three seeded demo accounts, all earlier demo and
manual-test data, and the proctor events from real-camera testing.

## Known limitations

Carried forward and re-checked against the current code, not merely copied:

1. **No human has sat an interview end to end on any recent build.** Every
   verification above is automated, and Phases 11–19 all changed code in the
   candidate's path. This is the single largest open item.
2. **Speech recognition is not automated.** Chrome/Edge only; the typing
   fallback is always available.
3. **The offline question bank is a fallback, not a curriculum.** It grew to 230
   questions across 17 technical domains in Phase 22, but its difficulty labels
   are a drafting judgement rather than a calibrated measure, and a domain
   outside those 17 falls to a general engineering set. The scheduling form says
   so before an interview is created.
4. **The face-count gate is reasoned, not measured.** Phase 12 fixed the false
   `MULTIPLE_FACES`, but its two thresholds have not been checked against a real
   webcam. A user reported phone and person detection looking better afterwards —
   recorded in `LIMITATIONS.md` together with the fact that **neither detector
   changed**, so the likely cause is less noise around them rather than better
   detection.
5. **The head-pose yaw sign is unconfirmed.** The axes are settled by tests;
   whether a positive yaw means left or right needs a camera. Nothing in scoring
   reads the direction.
6. **Monitoring coverage cannot catch a pipeline that loads and then dies
   silently.** Failure to start and failure to deliver are covered; not every
   failure to observe.
7. **ADAPTIVE can make two sequential Gemini calls inside one `POST /answers`**,
   so the 8 s ceiling can apply twice — up to 15.26 s measured. Accepted.
8. **In ADAPTIVE mode the report divisor is the questions generated**, not the
   number requested.
9. **DEMO-mode rendering is lightly tested** — the exam screen tests render
   `mode="NORMAL"` only. `objectDetector.js` and `useProctoring.js` also remain
   untested; both bind to a model or a timer, which is why they were left.
   `browserEvents.js` and `useFullscreen.js` were covered in Phase 20.
10. **The login throttle and bulk staging store are in memory**, so a restart
   clears them and a second instance would not share them.
11. **Erasure does not reach backups**, because the prototype takes none.
12. **`ARCHITECTURE.md` and `API.md` lag the newest migrations.**
    `SYSTEM-OVERVIEW.md` is authoritative where they disagree, and `SCORING.md`
    is authoritative on how a mark is produced.

## Pilot recommendation

**Suitable for a controlled pilot, with a human present** — unchanged in
substance from the previous pass, and now resting on more evidence.

What has improved since: a report can no longer imply it watched an interview it
did not; the most misleading detector failure is fixed; idempotent ingest is
enforced by the constraint rather than a hopeful read; guessing a password is no
longer unlimited; a candidate's data can be erased on request; and the human
decision the system always deferred to is finally recorded.

What has not: **nobody has sat a full interview on this build**, and the
detector thresholds remain unmeasured. Those are the conditions worth honouring:

- Run the first pilot interviews with someone available to help.
- Keep the defaults — `NORMAL`, `FIXED`, and `GEMINI` only once someone is
  watching `/admin/ai-health`.
- Treat proctoring output as **observations, not conclusions**. A human decides,
  and that decision now has somewhere to go.
- Expect Chrome or Edge, and tell candidates the typing fallback exists.
- Work through `docs/system/quality/TEST-PLAN.md`, "Camera review", before relying on any
  head-direction label or face-count threshold.

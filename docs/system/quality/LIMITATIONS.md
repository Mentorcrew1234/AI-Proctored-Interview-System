# Known limitations and future scope

This document is deliberately blunt. A proctoring system that overstates what it
can do is worse than one that admits its limits, because people act on its
output.

---

## The central limitation

**This system cannot detect cheating.** It detects a small set of visual and
browser conditions, imperfectly, and reports them as observations. Every design
decision follows from that:

- observations never lower a score;
- they can at most hold a result for human review;
- they can never produce `NOT_RECOMMENDED`;
- the report says so in plain words on the page.

A determined candidate can defeat all of it — a second device off-camera, a
printed page below the desk, a person out of frame speaking quietly. It is not a
security control. It documents obvious environmental changes so a human can
decide whether any of them matter.

---

## Measured detector accuracy

Tested on a real webcam in Chrome, 15 August 2026.

| Behaviour | Result |
|---|---|
| `NO_FACE` when the camera is covered | reliable |
| Face presence and counting, normal conditions | good |
| `TAB_SWITCH`, `WINDOW_BLUR`, `FULLSCREEN_EXIT` | reliable |
| `MULTIPLE_PERSONS` with a second person in frame | works |
| `PHONE_DETECTED` | **only when the whole phone is visible** |
| Phone held in front of the face | **false `MULTIPLE_FACES`** |

### Why the phone must be fully visible

COCO-SSD is trained on whole-object bounding boxes. A half-visible or edge-on
phone scores below the 0.55 confidence floor and is ignored. Lowering the floor
raises false positives on other dark rectangles — a wallet, a remote, a card —
so the floor stays and the limitation is documented instead.

### Why a phone in front of the face reads as two faces

The face landmarker treats the partially-occluded facial region as a separate
face.

**Practical consequence:** the most likely false positive is exactly the gesture
that looks most suspicious. This is precisely why proctoring can only ever
escalate to human review.

**Addressed in Phase 12, but not yet re-measured.** `acceptedFaceIndices` in
`faceDetector.js` now requires a candidate face to cover at least 1.5% of the
frame and to sit at least 0.15 (normalised) from every face already accepted,
before it counts as a second person. A phantom sits almost on top of the face
that produced it; two real people are well apart.

Three things about this are worth stating plainly:

- **It can only ever remove a face, never invent one.** The largest face is
  always kept, so the gate only decides whether to believe the *additional*
  ones. The failure mode is therefore a missed `MULTIPLE_FACES`, not a candidate
  wrongly flagged - the right direction for a system whose observations are
  advisory.
- **The two thresholds are reasoned, not measured.** They are covered by unit
  tests, which pin the *rules*; no real webcam has confirmed the *numbers*. A
  candidate sitting far back, or two people close together, could still be
  misjudged.
- **Head pose now comes from the largest accepted face**, not from whichever the
  model returned first, so a rejected artefact can no longer supply the yaw.

### Head-pose direction

The maths was confirmed in Phase 12 and the axes are right: rotation about Y
reads as yaw and rotation about X as pitch, with no bleed between them, verified
against rotation matrices built in the tests rather than against the
implementation.

**The sign convention is still unconfirmed**, and the 28 August run did not
settle it: no `HEAD_TURN` fired at all, so there was no LEFT or RIGHT label to
check (see the section above on why).

**The sign convention is still unconfirmed.** Whether a positive yaw means the
candidate turned left or right depends on MediaPipe's own coordinate frame, and
no amount of reading settles it - it needs one session in front of a real
camera. Until then `HEAD_TURN` is trustworthy as "the head turned"; the `LEFT`
and `RIGHT` labels on it may be swapped. Nothing in the scoring or the
recommendation reads the direction, so the consequence is confined to the
wording of one detail field on the observation timeline.

### Observed after Phase 12, on a real webcam (27 August 2026)

The user ran the application in DEMO mode and reported that **phone and person
detection were noticeably better** than in the previous build.

Recorded carefully, because the obvious explanation is the wrong one:

- **Neither detector changed.** `objectDetector.js` - which is phone and person
  detection - was last modified long before Phase 12, and the confidence floors
  are still 0.55 for a phone and 0.60 for a person. Nothing in the COCO-SSD path
  was touched.
- **The most likely cause is less noise around them.** The false second face is
  suppressed now, so a phone near the face no longer raises a spurious
  `MULTIPLE_FACES` alongside `PHONE_DETECTED`; and the DEMO overlay draws only
  *accepted* faces, so a phantom box no longer flickers over the candidate while
  the phone box is being drawn.
- **This is an impression, not a measurement.** No counts were recorded, and the
  specific gesture the Phase 12 gate exists for - a phone held directly in front
  of the face - was not tested in this run. So it is evidence that the change did
  no harm, and not yet evidence that the gate works.

The face-gate thresholds therefore remain **reasoned and unit-tested, not
measured**, exactly as stated above.

### Head pose is unreliable when more than one face is visible

Found during a real-camera run on 28 August 2026 (session 48), and worth stating
plainly because it is not obvious from the code.

Head pose is read from **the largest accepted face**. The system has no concept
of *which* face belongs to the candidate — identity verification is explicitly
out of scope — so when two or more people are in frame, the yaw and pitch being
measured may belong to somebody else entirely.

In that run, `MULTIPLE_PERSONS` was active for **112 of 115 seconds**: other
people were in frame for essentially the whole interview. The candidate turned
their head deliberately and **no `HEAD_TURN` was recorded at all**, which is
consistent with the pose being taken from a bystander who was facing forward.

Three things follow:

- **`HEAD_TURN` should be read as meaningful only when one face is in frame.**
  The report does not currently say this, and a recruiter reading a timeline has
  no way to know it.
- **A head-pose test is only interpretable alone in frame.** Any camera review of
  the yaw sign or the turn thresholds has to be run with nobody else visible.
- It is not a regression. Before the face gate the pose came from whichever face
  the model happened to return first, which is arbitrary rather than better.

Also unresolved from that run: `HEAD_TURN` requires the turn to be held for
**4 continuous seconds** beyond **20°**, so a brief glance never registers. Which
of these explains the absent events is not yet known.

### Conditions that degrade detection

- Poor lighting (the device check warns about this before the interview starts)
- Backlighting, a window behind the candidate
- Low-resolution or wide-angle webcams
- Multiple monitors — completely invisible to the system
- Anything outside the camera's field of view

### Monitoring coverage — "not observed" is not "observed and clean"

Until Phase 11 this document could not have said what follows, because the
system could not tell the difference. A session with no recorded observations
read as a clean session, and there are several ways to reach zero observations
without the session being clean: the detection models failing to load, the
browser abandoning a batch of events after repeated upload failures, or the tab
closing before the queue drained.

The session now records what the **observer** managed to do — whether the
detectors ever loaded, and how many observations were generated but never
reached the server — and the report derives a coverage verdict from it:

| Verdict | Meaning |
|---|---|
| Observations recorded | Monitoring ran and recorded something |
| Monitored, nothing observed | Monitoring ran throughout and saw nothing worth recording |
| Monitoring incomplete | Monitoring did not run, stopped, or is known to have lost events |
| Coverage not recorded | The session predates coverage recording; genuinely unknown |

Four things about this are deliberate and worth stating:

- **It never changes a score or a recommendation.** A monitoring failure is the
  observer's, not the candidate's. Penalising someone for a GPU fault or a
  dropped connection would be exactly the kind of unfairness the rest of this
  document exists to avoid. Observations still only ever cap at
  `FURTHER_REVIEW`.
- **The caveat appears even when observations exist.** Five recorded
  observations with three dropped is still an incomplete record, and a reader
  who is not told that will read the list as the whole story.
- **"Coverage not recorded" is kept distinct from "incomplete".** Every session
  from before this change reads as unknown, not as a failure. Relabelling
  history as broken would be as dishonest as calling it clean.
- **It still cannot detect a monitoring failure that leaves no trace.** If the
  detectors load and the pipeline then dies silently, coverage will read as
  complete. What is caught is a failure to start and a failure to deliver, not
  every possible failure to observe.

---

## Speech recognition

Uses the Web Speech API, which means:

- **Chrome and Edge only.** Firefox has no implementation. The device check
  detects this and the candidate types instead.
- **Chrome sends audio to Google's servers.** Despite running "in the browser",
  it is not on-device and needs internet.
- Accuracy drops with accents, background noise and technical vocabulary.
  "Kubernetes", "PostgreSQL" and "async" are frequently mis-transcribed.
- Recognition stops after a pause and is restarted automatically, which can drop
  a word at the boundary.
- No speaker identification — it transcribes whoever is loudest.

**Mitigation:** the transcript is shown and stays editable, and typing is always
available. A candidate is never graded on a mis-transcription they could not
see, and is never blocked by their browser.

### Speech synthesis — questions read aloud

The other direction, and a different API with different limits. Each question is
spoken through `window.speechSynthesis` as it appears.

- **The voice is whatever the operating system provides.** Quality ranges from
  good to robotic, and the system does not choose or bundle a voice — it asks
  for `en-US` at rate 0.95 and takes what the platform gives it.
- **A browser without speech synthesis is silent**, and nothing else changes:
  the question has always been on screen as text, no control is offered that
  cannot be honoured, and answering is untouched.
- **Some platforms need a user gesture before audio plays**, so the very first
  question may stay silent until the candidate presses **Repeat question**.
- **Technical terms are mispronounced** for the same reason they are
  mis-transcribed on the way in. The text is authoritative; the audio is a
  convenience.
- **It is not a substitute for reading.** No claim is made about accessibility
  compliance; this has not been tested with a screen reader, and a screen reader
  and the synthesiser may talk over each other.
- **It cannot be turned off from the interview screen** — only stopped for the
  current question. There is no per-candidate preference and no server setting.

**Deliberate interference guard:** starting the microphone cancels playback
first. Without it the recogniser hears the question through the speakers and
transcribes it into the candidate's own answer — which would then be graded.

### Filler removal, and why the list is short

Spoken answers are cleaned before evaluation: hesitation is stripped, stutters
are collapsed, and the result is what the evaluator scores. The raw transcript
is always kept alongside it, so nothing is lost.

**The word list is deliberately conservative, and was made shorter in Phase 21,
not longer.** A listed word is removed *wherever* it appears, so any word with a
common technical meaning corrupts the answer being marked. Four words were
removed from the list for that reason:

| Answer | Reached the evaluator as |
|---|---|
| "that's the **right** approach" | "that's the approach" |
| "the performance was **okay**" | "the performance was" |
| "an interface works **like** a contract" | "an interface works a contract" |
| "it **actually** returns null" | "it returns null" |

Words that are only hesitation in one *position* — a trailing "…, right?" — are
handled as phrases instead, so the meaning-bearing use survives and the
hesitation does not.

What this cannot do: a flat list cannot tell "so the query runs twice" from a
sentence-initial "so…", and no attempt is made to. The bias is deliberately
towards leaving a word in: an un-removed filler costs a little polish in the
communication sub-score, whereas an over-removed word changes what the candidate
is judged to have said.

`fillerCount` is reported on the report but **feeds no score** — it is context
for a reader, not a penalty.

---

## LLM evaluation

- **Scores are not calibrated against human graders.** No study was done. They
  are internally consistent, not externally validated.
- The same answer may score slightly differently on repeat runs — the model is
  not deterministic.
- Free-tier limits (~10 requests/minute, ~250/day) constrain throughput to
  roughly 35 interviews a day.
- **Free-tier prompts may be used by Google to improve their products.** Answers
  are sent to a third party. This matters for real candidate data and would need
  addressing before any genuine use.
- Prompt injection is not defended against: a candidate could in principle
  include instructions in a spoken answer. The impact is bounded — the model
  only returns sub-scores, and the overall score and recommendation are computed
  in Java — but it is untested.

### The offline fallback

When the LLM is unavailable, a heuristic scorer runs instead. It measures
**keyword coverage, answer length and sentence structure** — it does not
understand the answer. A well-argued response using different vocabulary scores
poorly; a keyword-stuffed one scores well.

It exists so an interview always completes, not because it is a good grader.
Every evaluation records `evaluator = LLM | FALLBACK`, and the report states
which was used.

---

## Domains and the question bank

**Any topic or stack can be scheduled** (Phase 22). The domain is free text with
around 65 suggestions offered; combinations like "Spring Boot + Kafka" are fine.
The AI generator takes the domain straight into its prompt, so it was only ever
the offline bank that needed a familiar name, and constraining the whole system
to the bank's vocabulary was the wrong way round.

**What an off-bank domain costs.** If the AI is unavailable - or the server is
in `MOCK` mode, which is the default - questions come from the offline bank.
That bank holds curated questions for 17 technical domains; anything else falls
to a general engineering set that names no language or framework. The
scheduling form says so before the interview is created, rather than leaving it
to be discovered afterwards.

Before Phase 22 an unknown domain fell through to the *Software Engineering*
questions, which is a domain in its own right rather than a neutral one - so a
Rust candidate was quietly asked about estimation and code review as though that
had been the choice.

**Bank size:** 230 questions - 17 technical domains plus the general set, each
with 4 easy, 4 medium and 4 hard, and 14 HR questions. Up from 70. Every one is
scenario-based with an explicit list of expected points used as the grading
rubric; none are "what is X?" definition questions.

**What the bank still is not:** it is a fallback, not a curriculum. It has no
coverage of any specific framework version, no company-specific material, and
its difficulty labels are a drafting judgement rather than a calibrated
measure - the same caveat that applies to the scoring generally.

---

## Scope and scale

Explicitly **not implemented**, and not intended to be:

eye/iris/gaze tracking · lip tracking · emotion recognition · deepfake detection ·
anti-spoofing and liveness · voice emotion · speaker identification ·
psychological profiling · multi-camera · screen recording · identity verification ·
custom model training · enterprise SSO · calendar integration ·
payments · mobile app · automatic hiring decisions

Outbound **email** was on that list until it was added at the user's request
after Phase 10. What exists is narrow: an interview invitation, plus credentials
for an account the bulk upload just created. It is off by default, it never
blocks or fails scheduling, and there is no reminder, threading, retry queue or
rate limiting. See `docs/system/features/EMAIL-NOTIFICATIONS.md`.

Prototype-scale constraints:

- Single instance, no clustering. In-memory sessions.
- **Login throttling and password reset exist as of Phase 14**, with limits
  worth knowing:
  - The throttle is **in memory**, so a restart clears it and a second instance
    would count separately. Same trade-off as `AiHealthRegistry`, and acceptable
    for the same reason: it only has to remember minutes.
  - It is keyed **by account, not by IP**. That means an attacker can deny one
    person their login for ten minutes by failing five attempts against their
    address. The lock expiring on its own, rather than needing an administrator,
    is what keeps that a nuisance instead of an outage - and an IP key would be
    both trivially rotated and shared by everyone behind one address.
  - It does **not** touch an already-issued JWT, deliberately, so nobody can
    knock a candidate out of a live interview by failing logins against their
    email. Revoking a live session is what disabling an account is for.
  - Password reset depends on **email being configured**. With mail off the page
    says so plainly rather than pretending, but an administrator then has to set
    passwords by hand.
- **The Content Security Policy does not stop injected script from executing.**
  It keeps `'unsafe-inline'` because the Thymeleaf pages carry inline scripts and
  around 150 inline `style` attributes, and `'unsafe-eval'`/`'wasm-unsafe-eval'`
  because TensorFlow.js and the MediaPipe runtime do not work without them. What
  it does do is pin every origin to `'self'`: injected script cannot load code
  from elsewhere, post data elsewhere, be framed, or retarget a form. Tightening
  it means rewriting every inline handler and style first.
- Adaptive follow-up questions exist (generating one question at a time from the
  previous answer's Java-computed score) and are now **chosen per interview**
  (Phase 19), not server-wide. `app.interview.question-mode` remains as the
  default for interviews that do not choose one, which is what `null` in
  `interviews.question_mode` means — "inherit", not "unknown". `FIXED` is still
  the default and still the more heavily verified path. Verified against live
  Gemini and the offline fallback (Phase 6.5), and driven end to end through the
  rendered candidate UI in a real browser (Phase 9.1). **No human candidate has
  sat an adaptive interview yet** — every run has been automated.
- English only. `language` exists in the model but only `ENGLISH` is selectable.
- No accessibility audit. A candidate who cannot speak can type, but the
  proctoring assumptions — face visible, looking forward — are not suitable for
  every candidate and would need rethinking before real use.

---

## The human decision

Added in Phase 17, and worth stating because its absence was a real
inconsistency: every report ends with "advisory input for a human decision, not
a hiring decision", and until now there was nowhere to record what that human
decided. The system deferred to a person and then discarded their answer.

A recruiter or admin can now record **Advanced / On hold / Declined** with an
optional reason, on the report itself.

- **Deliberately different words from the model's** `RECOMMENDED` /
  `FURTHER_REVIEW` / `NOT_RECOMMENDED`. Reusing one vocabulary for both would
  make "the system said RECOMMENDED" and "the recruiter said RECOMMENDED"
  indistinguishable at a glance, in a design that rests on keeping them apart.
- **A decision never alters the report.** Scores, recommendation and explanation
  stay exactly as computed. A review sits beside them.
- **Never shown to the candidate**, even when the recruiter has made the
  *scores* visible. There is no flag to enable it — the absence of the option is
  the design. A half-formed internal judgement delivered to its subject with no
  context would be worse than no feedback.
- **Append-only.** Revising a decision keeps the earlier one, because a
  judgement about a person that silently changed is the wrong thing to lose.

What this does **not** claim to be: a workflow. There is no assignment, no
notification, no approval chain and no second reviewer requirement. It records a
decision; it does not manage one.

It also quietly accumulates something the scoring lacks. Whether a human agreed
with the model is now recorded per decision, and enough of those would be the
beginning of the calibration study this document says was never done. Having the
data is not the same as having done the study, and nothing in the system claims
otherwise.

---

## Data retention and erasure

Added in Phase 18. Until then this document had to admit there was **no data
retention policy or deletion endpoint** - for a system that stores what a
candidate said word for word, what was observed of them through their own
camera, and how they were scored, that was the largest remaining gap.

An administrator can now erase a candidate's data, either the interview data
alone or that plus the account.

- **It is not anonymisation.** The rows go. Blanking a name while keeping the
  transcript, the observations and the scores would leave data that is still
  about a person and still re-identifiable from the interview it sits under - a
  weaker guarantee dressed as a stronger one.
- **The size is shown before anything is destroyed**, and the administrator
  types the candidate's address to confirm. A second click is a reflex; naming
  what you are erasing is not.
- **One transaction.** It all goes or none of it does. A partially erased
  candidate - transcripts gone, observations left behind - would be worse than
  either outcome.
- **The erasure log keeps no identity.** It records the account id, the scope,
  who performed it, when, and row counts. No name, no email. Keeping those would
  mean an erasure request left the identity behind in the record of the erasure.
- **Admin only, candidates only.** A recruiter or administrator owns interviews
  rather than being their subject, so erasing one would orphan other people's
  records. That is a different operation and is not offered.

What it deliberately does **not** do:

- **Nothing runs on a schedule.** There is no cron that quietly destroys old
  interviews. The retention window (`app.retention.months`, default 24) drives a
  *report* on `/admin/data-retention`, and a named person acts on it. An
  automatic purge is the kind of feature that works perfectly until the morning
  of a demonstration, and its failure mode is unrecoverable.
- **There is no candidate-facing request flow.** A candidate cannot ask for
  erasure through the system; they ask a human, who performs it. Building a
  request queue would be a workflow, and this is not one.
- **It does not reach backups**, because the prototype takes none. On a real
  deployment that would be the next thing to think about, and
  `docs/system/deployment/DEPLOYMENT.md` says so.

---

## Fairness

Worth stating explicitly for a system that scores people:

- Face detection accuracy varies with skin tone and lighting; this was **not**
  measured here, and it is a known weakness of the field generally.
- Speech recognition is measurably worse for non-native and regionally accented
  English, which affects the communication sub-score.
- Head-pose assumptions penalise anyone who naturally looks away while thinking.
- The proctoring rules assume a quiet private room with a good camera — an
  assumption that correlates with circumstance rather than ability.

None of these are solved. They are reasons the output is advisory and a human
makes the decision.

---

## Interface

- **Desktop and laptop first.** This is an interview management tool used at a
  desk. The sidebar collapses to a drawer below 900 px and tables scroll
  horizontally rather than reflowing, so a tablet works; a phone is usable but
  was not optimised for, and desktop usability was never traded away for it.
- **The candidate exam screen is Chrome/Edge only**, for speech recognition —
  unchanged, and independent of the rest of the interface.
- **Search is a `LIKE` scan** behind every search box. Fine at prototype scale.
- **Sorting is by column choice in the filter panel, not by clicking a header.**
  The query service accepts a fixed set of sort properties; clickable headers
  would be presentation over the same API, not new capability.
- **Candidates have their own page** (Phase 23), scoped to the viewer: a
  recruiter sees the candidates they scheduled and their own interviews with
  them, an administrator sees everyone. The counts shown are the *viewer's*, not
  the candidate's whole history — otherwise the page would quietly disclose that
  another recruiter is also interviewing this person, which nothing else in the
  system does. A candidate the viewer has never interviewed returns **404, not
  403**, for the same reason a stranger's invite link does.
- **Whoever schedules an interview becomes its recruiter of record.** Both staff
  roles can now schedule one interview, several candidates at once, or a bulk
  batch, so an admin who schedules is stored as that interview's recruiter. That
  is honest, but the recruiter *filter* on the management page lists only
  RECRUITER accounts — so admin-created interviews are not selectable through it.
- **Details can be corrected, identity cannot.** `/admin/users/{id}/edit`
  corrects a full name and the profile fields — phone, college name, candidate
  type, years, primary domain, department, designation. It deliberately cannot
  touch **email, role, password or enabled**: `UpdateUserRequest` has no field
  for any of them, so a crafted POST cannot set them either. Each is a different
  operation, and a mistyped sign-in email in particular is not a detail — it is
  the identity every invite and reset link was issued against, so fixing one
  still means a database change or a fresh account.
- **Editing is administrator-only.** A recruiter who spots a wrong college on
  the candidates page cannot fix it; the Edit link is not shown to them and
  `/admin/**` refuses them anyway. User management has always been the
  administrator's, and widening it was not part of adding the field.
- **A bulk upload never corrects an existing account.** It sets a profile only
  on the account it creates, so re-uploading a spreadsheet with a fixed college
  changes nothing — use the edit screen.
- **The candidate filters are exact-match, not fuzzy.** College, location,
  skill and domain match a whole value, chosen from a dropdown of what is
  actually recorded. "ABC College" and "A.B.C. College" are two options, not
  one - nothing reconciles spellings, and a typo on a profile becomes its own
  filter entry. The free-text search box is the substring match.
- **A candidate with a field unrecorded is excluded when that field is filtered
  on.** Filtering by college hides everyone whose college was never asked, which
  is the honest reading of the question but does mean a filter can hide people
  for a reason that is about the data rather than about them.
- **Filtering happens in Java over the scoped rows, not in SQL.** Fine at
  prototype scale - the candidate list is one row per candidate and fits on a
  screen - but it reads every candidate in scope before narrowing, so it is not
  the shape to keep at ten thousand.
- **`skills` is a comma-separated column.** It cannot be indexed, sorted or
  counted as a set by the database, and a skill renamed on one profile is not
  renamed on any other. That is the deliberate trade for not adding a table.
- **The scheduling pool filter needs JavaScript.** Without it the full
  unfiltered list is offered, which is exactly what happened before the filter
  existed - degraded, never broken.
- **Tab state is client-side** on the detail, report and candidate screens. It
  is mirrored into the URL hash so a refresh or a bookmark returns to the same
  tab, but with JavaScript disabled every section renders stacked instead —
  degraded, never broken.

---

## Interview execution mode

- **The interview is timed, and the limit is real.** Duration is configured per
  interview (1–180 minutes, default 30), the deadline is computed server-side
  from the session's own start time, and work past it is refused with a `409`.
  The countdown on screen is a display of the server's figure, so a refresh
  resumes rather than restarts and tampering with the client buys no extra time.
- **An abandoned interview is closed within about a minute, not instantly.**
  `InterviewExpirySweeper` runs every 60 seconds and finalises sessions whose
  time ran out while nobody was looking — a candidate who simply closes the
  laptop triggers neither the countdown nor a further request. So a recruiter
  may briefly see an interview as still running after its deadline. The window is
  bounded by the sweep interval, and the interview cannot be *used* in that
  window regardless, because the request-time check already refuses it.
- **The countdown is not a hard stop mid-answer.** Expiry is detected when the
  clock reaches zero or when the next request is made, so an answer being typed
  as time runs out is submitted and then refused rather than blocked in advance.
  Answers already submitted are always kept.
- **"Microphone: Active" would be misleading**, so it is not shown. The audio
  track opened for the device check is stopped before the interview begins,
  because a second live audio track competes with Chrome's speech recognition.
  DEMO reports granted / listening / idle instead.
- **Fullscreen cannot be forced.** Browsers grant it only from a user gesture
  and Escape always exits. The interview asks for it, records every exit, and
  prompts the candidate to return — it never claims to be in fullscreen when it
  is not, and never terminates the interview over it.
- **A browser that forbids fullscreen outright** (policy, or an embedded frame)
  is allowed through with the limitation stated, because the alternative is a
  retry button that can never succeed. `FULLSCREEN_EXIT` is recorded throughout,
  which is the honest record.
- **DEMO mode is a presentation setting, not a permission.** Everything it shows
  is computed locally in the candidate's browser, so forcing the panel on by
  tampering with the client reveals nothing privileged. Reports and proctoring
  history remain behind the existing service-level checks.
- **Face bounding boxes are landmark extents**, not a dedicated face-detection
  box. They are derived from points the landmarker already produced, so they can
  sit slightly tighter than a conventional detector box.

---

## Future scope

Roughly in order of value for effort. The full audit and the phased plan behind
this list are in `docs/system/planning/IMPROVEMENT-PLAN.md`.

1. **Calibrate the scoring** against human graders on a set of real answers, and
   report the agreement rate. Every decision recorded on a report accumulates a
   little of this data — having the data is not the same as having done the
   study.
2. **Whisper for speech-to-text**, self-hosted — removes the Chrome-only limit,
   the internet dependency, and sending audio to a third party.
3. **Confidence bands on the report** rather than single numbers, making the
   uncertainty visible.
4. Multi-language support once speech and evaluation are reliable in one.
5. Fairness evaluation across demographic groups before any real deployment.
6. **Sortable table headers and saved filter views** — both are presentation
   over the existing query service; neither needs new data.

**Since delivered, and no longer on this list:** the false second face (gated on
minimum box area and separation — see "Why a phone in front of the face reads as
two faces" above), adaptive follow-up questions, the recruiter decision on a
report, and the recruiter-scoped candidate view.

- **AI health is in-memory, not monitoring.** `/admin/ai-health` reports what the
  language model has done since this server started. Restarting clears it, and
  each instance would report only itself. It is a signal for a person running a
  demonstration, not an uptime record — what each interview actually used is
  stored permanently on the question and answer rows instead.
- **The 8-second AI budget is marginal for the current model.** Answer evaluation
  is synchronous inside answer submission, so the timeout is a candidate's
  waiting time and is deliberately short. Measured through the running
  application, live question generation took 4.0–8.6 s, and calls crossing 8 s
  fell back to the offline bank — about half of one test run. The interview is
  unaffected (the fallback answers, and provenance records it), but on a slow
  connection a candidate may be interviewed largely by the offline bank without
  noticing. **Re-examined in Phase 4.1** and deliberately left unchanged: the
  timeout is a candidate's live wait, not a background retry, so raising it
  would trade "some answers use the fallback" for "the candidate waits longer
  before the fallback kicks in" — a worse trade for the same underlying model
  latency. No faster call shape or model was introduced in this phase, so
  there was no new evidence to justify moving the number.
- **A timed-out AI call used to be reported misleadingly** — the read timeout
  surfaced as "Error while extracting response … content type
  [application/octet-stream]" rather than as a timeout, so the health page's
  failure reason named the wrong cause. **Fixed in Phase 4.1**:
  `AiHealthRegistry.classify` walks the exception's cause chain — a real
  timeout is a `RestClientException` at the top with a `SocketTimeoutException`
  one or two levels into `getCause()`, confirmed against a real slow socket
  rather than assumed — so the health page now reports "Timed out" with the
  measured wait time instead of the misleading extraction message. Connection
  refusals and HTTP error responses were verified not to be misclassified as
  timeouts by the same change.
- **Only English is supported for AI question generation.** The language is now
  carried end to end and named in the prompt, but `InterviewLanguage` holds one
  value and the generator claims no others. An unsupported language would fall
  back to English with a warning rather than failing — no interview is ever
  generated in a language the system cannot actually write.
- **Question archetypes can repeat across interviews.** Within one interview the
  questions are distinct, scenario-based and correctly ordered by difficulty;
  across interviews the same scenario shapes recur with the domain swapped
  (measured: "legacy code with no tests" and "large file / memory pressure" each
  appeared in three separate sets). Two candidates in the same drive may get
  recognisably parallel interviews. Deliberately not addressed yet — it belongs
  with question storage and reuse rather than with a prompt tweak.

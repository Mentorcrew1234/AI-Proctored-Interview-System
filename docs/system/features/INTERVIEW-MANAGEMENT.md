# Interview management

The interview list is a management page: named interviews, search, filters,
quick tabs, sorting, paging and summary counts.

Both `/admin/interviews` and `/recruiter/interviews` render the **same page**.
The only difference is scope: an admin sees every interview, a recruiter sees
their own. That difference is decided server-side from the signed-in user.

---

## Interview naming

An interview now carries a **name** — what the recruiter calls the drive or
assessment, e.g. *Java Developer - Campus Drive 2026*. It is not the candidate's
name, and it is deliberately **not unique**: a whole drive shares one name, which
is what makes it useful to search by.

- **Required** on the single-schedule form and in the bulk template.
- **Nullable in the database.** Interviews created before naming existed keep
  their rows and display as `Interview #id`. Backfilling a made-up name would
  invent data nobody entered.

## Summary insights

Six counts — Total, Today, Upcoming, In Progress, Completed, Cancelled — each a
database `count()` under the caller's own scope. Never computed in the browser,
and never from the current page of results. Each card links to its tab.

## Quick tabs

`All · Today · Upcoming · In Progress · Completed · Cancelled`

These are shorthand for filters, not separate pages. **Upcoming** means in the
future *and* still open, so a completed interview with a future timestamp is
excluded.

There is no `NO_SHOW` status in this system and none was invented — see
*Needs attention* below.

## Search

One box matching **interview name, candidate name or candidate email**,
case-insensitive and partial. Backend `LIKE`, not client-side filtering.

## Filters

Date range (inclusive both ends; From, To, both or neither), Status, Domain,
Experience Type, Language, Interview Type, and — for admins only — Recruiter.

All filters combine with **AND**. Active filters are listed as chips with a
**Clear filters** action.

`From` after `To` produces a clear message rather than an empty table.

## Sorting and paging

Sort by scheduled date, interview name, candidate name, status or created date.
Defaults are chosen per tab: **Upcoming reads oldest-first**, everything else
newest-first. Page sizes 20 / 50 / 100. Sorting is tie-broken by id so paging
cannot repeat or skip a row.

Only a whitelist of sort fields is accepted; anything else falls back to the
scheduled date rather than reaching the query.

## Needs attention

A short list derived **only from data already recorded**. No status was invented
and no model infers anything:

| Shown as | Determined by |
|---|---|
| Starting soon | SCHEDULED and within the next hour |
| Possible no show | SCHEDULED, more than 2 h past its time, and no session ever started |
| Incomplete | IN_PROGRESS well past its scheduled time |
| Missing report | COMPLETED but no report row exists |

## Interview details

`/interviews/{id}` shows the interview, candidate, recruiter, invite link,
session, result summary and proctoring observation counts, with **View full
report** linking to the existing report. It reads existing data only — it never
regenerates a report or alters the interview.

It also shows the **resolved question mode**, never the raw `null` that means
"inherit the server default", so nobody has to interpret a blank.

### The candidate's own page

`/{admin\|recruiter}/candidates` lists candidates, and
`/{admin\|recruiter}/candidates/{id}` shows one candidate with their interviews
and results. It is the same data seen from the other end, and it follows the same
authorization rule with one sharper edge:

- **Scope is in the query, not applied afterwards.** A recruiter's list is built
  from their own interviews.
- **The counts are the viewer's, not the candidate's whole history.** Reporting a
  true total would disclose that another recruiter is also interviewing them.
- **A candidate the viewer has never interviewed is a 404, not a 403** — the same
  reasoning as a stranger's invite link.

## Edit and delete

| Action | Route (both role prefixes) |
|---|---|
| Edit form | `GET /{admin\|recruiter}/interviews/{id}/edit` |
| Apply edit | `POST /{admin\|recruiter}/interviews/{id}/edit` |
| Delete | `POST /{admin\|recruiter}/interviews/{id}/delete` |
| Bulk edit | `POST /{admin\|recruiter}/interviews/bulk-edit` |
| Bulk delete | `POST /{admin\|recruiter}/interviews/bulk-delete` |

Both prefixes delegate to the **same methods** on
`InterviewManagementController`, which take their action URLs as parameters, so
the admin and recruiter routes cannot drift apart.

**Only a `SCHEDULED` interview can be edited or deleted.** Once a session exists,
its questions were already generated against the old domain, question count and
candidate type — editing would silently invalidate them, and deleting would
orphan real answers, proctoring observations and a report. That restriction is
also what makes delete a safe plain hard delete: a `SCHEDULED` interview with no
session has no dependent rows at all. `InterviewService.delete` still re-checks
for a session before deleting, as defence in depth.

**The candidate, recruiter, invite token and status are never edited.**
Reassigning a candidate is a reschedule, not an edit; only *cancel* and the
candidate's own exam flow move status. Because the invite link never changes,
editing an interview sends the candidate no new email.

**The question mode is editable on the single form, and deliberately not in bulk.**
The scheduling and edit forms offer three choices — *use the server default*,
*fixed set*, *adaptive* — where the first stores `null`, meaning "inherit". Bulk
edit and the Excel upload do not set it at all, so those interviews inherit.
Bulk doing *less* than the single form is safe; the rule that must never break is
bulk doing *more*.

**Bulk edit is a field mask.** `BulkEditFields` carries an `applyX` flag beside
each field; only flagged fields change, everything else on each targeted
interview is left as it was. Each row runs the same validation a single edit
would, so a bulk action can never produce a state the single form would have
rejected.

**Rows are applied independently.** `BulkInterviewMutationService` loops, and
`BulkInterviewRowMutator` applies one row per `REQUIRES_NEW` transaction — the
same reason `BulkRowScheduler` exists in bulk scheduling: Spring's
`@Transactional` is proxy-based, so a self-invoked method would join the caller's
transaction and let one bad row roll back every row that already succeeded.
Failures are collected and reported by name, never swallowed.

**"Select all matching filter" is recomputed server-side** by
`InterviewQueryService.idsMatching(filter, userId, role)`, from the filter plus
the caller's own authorization scope. The browser only ever sends a filter
description, never the resulting id list, so there is nothing to tamper with to
widen the target set.

## Authorization

| Role | Access |
|---|---|
| Admin | All interviews; may filter by recruiter |
| Recruiter | Their own interviews only |
| **Candidate** | **No access — 403** |

The ownership predicate is ANDed onto every query and every count. A recruiter
who searches for another recruiter's candidate by exact name, email or drive
name gets **nothing** — and passing `recruiterId` for someone else does not
widen the scope either. Both are covered by tests.

---

## Implementation notes

**Filtering** uses `JpaSpecificationExecutor` with one `InterviewSpecifications`
builder. Eight independent optional filters would otherwise mean a combinatorial
explosion of repository finders.

**Admin scope is an explicit always-true predicate**, not `null`, because Spring
Data rejects a null `Specification` — and because a scope that is always a real
object cannot be silently dropped.

**The detail view is assembled inside the service transaction** into a plain
record. `open-in-view` is off, so passing entities to the template throws
`LazyInitializationException` when Thymeleaf touches a lazy association.

**Indexes** added in V4: `(status, scheduled_at)` covers the tabs and the default
ordering; `interview_name` supports name search.

## Reporting: filtered Excel export and detailed PDF

Two exports, both reusing what already existed rather than adding a second
reporting system.

### Filtered results → Excel

`GET /admin/interviews/export.xlsx` · `GET /recruiter/interviews/export.xlsx`

Conceptually the mirror of bulk scheduling: that reads a spreadsheet to create
interviews, this writes one from interviews that already exist.

- **Exports the whole filtered set, not the visible page.** The browser sends
  only the filter, exactly as bulk edit/delete do; the server re-runs it under
  the caller's own scope. Paging is ignored entirely.
- **Scope is applied server-side**, so a recruiter cannot widen the export by
  editing the query string — including by naming another recruiter in
  `recruiterId`. There is a test for precisely that.
- **Rows above 1,000 need `confirmLarge=true`.** The page shows the count and
  asks first; the server enforces it regardless of what the page rendered.
  `50,000` is an absolute runaway guard. A refused export returns `413`.
- Built with **SXSSF** (streaming), so memory stays flat regardless of size.
  `close()` is what removes its temp files — the one way the writer could leak.
- An empty result still produces a valid, openable workbook that says no rows
  matched, rather than a zero-byte file.
- A second **"Filters Applied"** sheet records who exported, when, and exactly
  which filters produced the file. Without it an extract is indistinguishable
  from the full set months later.
- **A missing report leaves score cells blank, never `0`** — an interview
  without a report has not scored zero, it has not been scored.

**Assembled in four queries, not four per row.** `InterviewService.toSummary`
costs a session lookup plus a report check *per interview*, which is fine for a
20-row page and ruinous for thousands. `InterviewQueryService.exportRows` instead
fetches the interviews, then their sessions (`findByInterviewIdIn`), reports
(`findBySessionIdIn`) and answer counts (`countBySessionIdIn`) in one query each.

### Detailed report → PDF

`GET /reports/{sessionId}/export.pdf`

- Renders the **same `ReportView`** the HTML report page uses, so the two cannot
  disagree about a candidate's scores. The read model is the single source of
  truth; `ReportPdfService` only lays it out.
- Authorization is **`ReportService.assertCanView`**, not the URL prefix. That
  matters because `/reports/**` is reachable by a candidate: the service is what
  restricts them to their own report, and only when the recruiter made the result
  visible.
- The report's wording rules carry over: proctoring appears as **observations**
  with its disclaimer intact, and the recommendation is stated as advisory input
  to a human decision.

## Test duration

Every interview carries a `duration_minutes` (V5, default 30, bounds 1–180).
It is a rule of the interview, not a label on the schedule form.

**The clock starts when the candidate starts**, not at `scheduled_at`. Arriving
late costs a candidate nothing; the deadline is
`interview_sessions.started_at + duration_minutes`.

**Nothing about the deadline is stored.** It is derived, so editing an
interview's duration cannot leave a stale deadline behind, and the actual
duration is always `ended_at - started_at` rather than a second copy that could
disagree with the timestamps.

**The server is the authority.** `ExamService.requireActiveSession` — which
every write into a session already passed through — refuses work past the
deadline. The browser countdown is a convenience: it is seeded and resynced from
a server-computed `remainingSeconds` (on load, and after every answer), never
from an absolute deadline compared against the browser's own clock. That is what
makes a refresh resume rather than restart, and why a wrong or shifted client
clock changes nothing.

**Why the expiry runs in its own transaction.** `requireActiveSession` marks an
expired session and then *throws* to refuse the request. That exception rolls its
transaction back — and would have rolled the expiry back with it, leaving the
session ACTIVE for ever while every request it made was refused. `SessionExpiryService`
is a separate bean with `REQUIRES_NEW` so the expiry commits regardless; a
self-invoked private method would have silently run in the caller's transaction,
the same proxy trap that `BulkRowScheduler` exists to avoid. **A test caught
this** — the fix is not theoretical.

**Three ways an interview ends, one mechanism.** The candidate presses Finish;
their countdown reaches zero and finishes it for them without any click; or
`InterviewExpirySweeper` closes it because they abandoned it. All three land in
`ReportService.completeSession`, which decides the reason itself by re-checking
the deadline — so there is no second finalisation path to keep in step, and the
report and evaluation run identically whichever way it ended.

**The sweep is the answer to the abandoned case.** Every other expiry path needs
the browser. A candidate who closes the laptop at the deadline makes none of them
happen, and without the sweep the session would sit `ACTIVE` and the interview
`IN_PROGRESS` for ever — never reported, and still showing as running to the
recruiter. `@Scheduled(fixedDelay = 60s)` is the only scheduled work in the app.

**Status and reason are separate.** A timed-out interview is still
`COMPLETED`; `interview_sessions.completion_reason` records *why*
(`CANDIDATE_FINISHED` / `TIME_EXPIRED`). Folding expiry into the status enum
would have made every existing query for `COMPLETED` silently miss them. The
column is nullable: sessions that finished before it existed have a genuinely
unknown reason, and guessing `CANDIDATE_FINISHED` would invent data.

An expired session is ended **at its deadline**, not at the moment expiry was
noticed — a candidate who shut their laptop at the 30-minute mark and returned
an hour later did not sit a 90-minute interview.

Duration is validated identically on all four write paths (single, multi, bulk
upload, bulk edit) via `InterviewService.validDuration`, so bulk can never store
a duration the single form would reject. The Excel column is **optional**, so an
older template still uploads and a blank cell takes the same default the form
uses.

The report gains a timing section: allocated vs actual, completion reason,
answered/unanswered, and average/longest/shortest per answer. Per-answer figures
come from the durations the browser reports with each answer, so they measure
time on the answering screen — that caveat is printed alongside them. Nothing
here is scored or inferred, and an overrun is stated as a fact rather than
flagged.

## Limitations

- Search is a `LIKE` scan; fine at prototype scale, but a large deployment would
  want a proper text index.
- The recruiter filter is admin-only by design.
- No saved views.
- The Excel export is generated synchronously on the request thread. At the
  50,000-row ceiling that is a slow response, not a background job.
- The PDF layout is built in Java and is therefore a second rendering of the
  report; it shares the read model with the HTML page but not the markup, so a
  change to one may need mirroring in the other.
- The expiry sweep runs every 60 seconds, so an abandoned interview can remain
  `ACTIVE` for up to a minute past its deadline before being finalised. It
  cannot be *used* in that window — every write is already refused — but a
  recruiter refreshing at exactly the wrong moment may briefly still see it as
  in progress.
- The sweep is per-instance. A clustered deployment would run it on every node;
  the unique constraint on `reports.session_id` still guarantees one report, but
  the work would be duplicated. Single-instance is the documented deployment.
- **Per-question timing is client-reported.** `answers.duration_seconds` is what
  the browser measured on the answering screen; there is no server-side
  `questionStartedAt`. It does not survive a refresh mid-question and it is not
  thinking time. Recording it honestly server-side would need a round trip when
  each question is shown, which is more machinery than this earns.
- **Multiple tabs share one session and one deadline**, so they agree on the
  time remaining. Two tabs answering the same question at once is still bounded
  by the existing one-answer-per-question constraint rather than by any locking.
- Editing and deleting are restricted to `SCHEDULED` interviews. There is no way
  to correct an interview once the candidate has started it; that history is kept
  permanently rather than reconciled.
- No cross-interview conflict check — a candidate can be double-booked by a
  single edit or a bulk reschedule.
- No `NO_SHOW` status; "possible no show" is inferred and may be wrong if a
  candidate simply arrives late. It is a prompt for a human, not a verdict.

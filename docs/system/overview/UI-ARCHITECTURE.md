# UI Architecture

How the interface is organised, why it is organised that way, and the rules to
follow when adding a screen. Companion to `SYSTEM-OVERVIEW.md`, which describes
what the system *does*; this describes how a person reaches it.

---

> **Scope:** this document describes the **staff UI** — the Thymeleaf shell used
> by admins and recruiters. The candidate's exam screen is a separate React
> application with its own structure; it is documented in `SYSTEM-OVERVIEW.md`
> (§4, “The React exam SPA”, and §8 for the question panel and its audio).

## 1. The problem this structure solves

The first version of the UI was a flat top bar over long, single-column pages.
Everything a page could do was on the page at once, stacked vertically:

```
Interview management (old)
  6 stat cards
  "needs attention" table
  tab strip
  11-field filter form            ← always open
  active filter chips
  results table
  7-field bulk edit panel         ← always open
  export card
  pagination
  action buttons
≈ 6 screens of scrolling to reach the pagination
```

Three consequences, and the restructure targets each one:

| Problem | Fix |
|---|---|
| Features found only by scrolling | Collapse what is optional; put primary actions in the page header |
| No grouping — every link at one level | A role-generated sidebar with sections |
| Capabilities the backend had but the UI barely exposed | Give each one a home (see §6) |

---

## 2. The shell

Every server-rendered page is built from the same three fragments in
`templates/fragments/layout.html`:

```html
<body class="app">
  <div th:replace="~{fragments/layout :: sidebar('interviews')}"></div>
  <div class="app-main">
    <div th:replace="~{fragments/layout :: topbar('Interviews')}"></div>
    <div class="page"> … page content … </div>
  </div>
</body>
```

- **`head(pageTitle)`** — title, `/css/app.css`, deferred `/js/app.js`.
- **`sidebar(active)`** — navigation, generated from the authenticated role.
  `active` is the key of the current section, so exactly one entry carries
  `aria-current="page"`.
- **`topbar(title)`** — the mobile navigation toggle and the page name.
- **`flash`** — the `message` / `error` flash pair, so success and failure look
  the same on every screen.
- **`scheduleTabs(active)`** — the single ↔ bulk scheduling switch.

There is no layout dialect and no extra dependency: the shell is three fragment
includes per page. `page-narrow` caps the column for forms and single-purpose
pages so a six-field form does not stretch to 1300 px.

**The sidebar is generated from the role so nobody is offered a link they would
only be denied at. That is a usability measure, not a security one.**
`SecurityConfig` and the service layer remain the authority — hiding a link has
never been what stops anyone reaching a page, and the access-control checks are
unchanged.

---

## 3. Information architecture

```
ADMIN                      RECRUITER                  CANDIDATE
──────────────────         ──────────────────         ──────────────────
Dashboard                  Dashboard                  My Interviews
                                                        next-up hero
MANAGE                     MANAGE                       tabs: Today ·
  Interviews                 Interviews                   Upcoming ·
  Reports                    Reports                      In Progress ·
  Candidates                 Candidates                   Completed ·
  Users                                                   Cancelled
  AI health                SCHEDULING                 My Results
  Data retention             Schedule Interview           (same page,
                             Bulk Schedule                 Completed tab)
SCHEDULING
  Schedule Interview
  Bulk Schedule
```

**AI health** and **Data retention** are admin-only and deliberately sit under
MANAGE rather than in a settings area of their own: one reports whether the
language model is actually answering, the other reports what is past the
retention window and what has been erased. Both are things an administrator
*reads*; neither changes how an interview runs.

Both staff roles now schedule the same three ways, chosen with a segmented
control on the scheduling pages:

| Mode | Route | What it does |
|---|---|---|
| One interview | `/{role}/interviews/new` | One candidate, one interview |
| Several candidates | `/{role}/interviews/new-multi` | The same form with a candidate multi-select; one interview each |
| Bulk from Excel | `/scheduling/bulk` | Four-step file upload, creates missing candidate accounts |

The sidebar carries only two of them. "Several candidates" is a mode of
scheduling, not a fourth feature group, so it lives in the segmented control at
the top of the scheduling pages and as a secondary action on the Interviews
page - the same reasoning that keeps a status out of the sidebar.

Derived from the backend, not from a template:

- **Both staff roles schedule the same three ways.** The admin originally had
  no single-interview route at all, so the sidebar offered only bulk; the routes
  now exist, so the entry does too. Whoever schedules becomes the interview's
  recruiter of record - for an admin that is the admin, which is the honest
  record of who created it.
- **Both staff roles now have a Candidates entry** (Phase 23), and it is
  deliberately *not* the admin Users page. Users is account management — create,
  enable, disable, erase. Candidates is a view of people and their interviews,
  scoped to the viewer: a recruiter sees the candidates they scheduled and their
  own interviews with them, an administrator sees everyone.

  The counts shown are the **viewer's**, not the candidate's whole history. A
  recruiter looking at someone two recruiters have interviewed sees one
  interview, not two — reporting the true total would quietly disclose that a
  competitor for that hire exists, which the interview list has never done, and
  the detail page says so rather than leaving it to be assumed.

  A candidate the viewer has never interviewed returns **404, not 403** — the
  same reasoning as a stranger's invite link.
- **The candidate has two entries over one page.** Both views are already loaded
  by the single dashboard query, so switching costs no request. A candidate
  never sees scheduling, other candidates, users or system configuration.

### Sidebar rule

The sidebar carries **feature groups only** — never a status, a filter or a CRUD
operation. "Upcoming interviews" is a tab inside Interviews, not a sidebar
entry. Sections (`MANAGE`, `SCHEDULING`) group entries; they are not links.

---

## 4. Tabs versus pages

| Use a tab when | Use a page when |
|---|---|
| Same data, different filter or view state | Different workflow |
| Same permissions | Different permissions |
| The data is already loaded | A complex form or a multi-step operation |

Applied:

| Screen | Tabs | Why |
|---|---|---|
| Interviews | All · Today · Upcoming · In Progress · Completed · Cancelled | One dataset, six filters — these were never separate pages and must not become them |
| **Several candidates** | *not a tab* | A form with different validation and a different result shape; a mode of the scheduling page, reached by its segmented control |
| Users | All · Recruiters · Candidates | One table, one `role` query parameter |
| Interview details | Overview · Invite link · Session · Result · Proctoring | One record, five aspects |
| Report | Summary · Proctoring · Answers | One document, three reading modes |
| Candidate dashboard | Today · Upcoming · In Progress · Completed · Cancelled | One query, already in memory |
| **Bulk schedule** | *not a tab* | A four-step file workflow does not fit inside a tab on the list page |
| **Schedule interview** | *not a tab* | A complete form with its own validation and error re-render |

Interview and Users tabs are **server-side links** (they change the query and the
result set). Detail, report and candidate tabs are **client-side panels** — the
data is already on the page, so switching is instant and costs no request.

---

## 5. How scrolling was reduced

Not by shrinking things. By deciding what is on screen at rest:

| Element | Before | After |
|---|---|---|
| Filters (11 fields) | Always open | Collapsed behind a **Filters** button; opens automatically when a filter is applied, so nothing narrows results out of sight |
| Bulk edit (7 fields) | Always open under the table | Hidden; opened from the selection bar |
| Bulk actions | Buttons always present | A **selection bar** that appears only once rows are ticked |
| Needs attention | Full table | One line with a count, expandable |
| Export | A card at the bottom of the page | A button in the table toolbar |
| Summary counts | Six large cards | A compact tile strip, each tile a link to its tab |
| Interview detail | 8 stacked cards | 5 tabs, one visible |
| Report | Scores → explanation → proctoring → timeline → N answer cards | 3 tabs; the outcome is always visible above them |
| Interview table | 10 columns | 6–7; experience, type and question count moved to the detail page |
| Row actions | Up to 5 competing links | `View` + a "more" menu |

**Printing overrides the tabs.** The `@media print` block un-hides every
`.tabpanel`, so a printed or PDF-saved report is still the complete document —
tabs are a reading aid, never a way of omitting something from the record.

---

## 6. Backend capabilities that gained a home

Found by auditing controllers against templates:

| Capability | Route | Was | Now |
|---|---|---|---|
| Cancel an interview | `POST /recruiter/interviews/{id}/cancel` | **unreachable** — its only link was in a dead template | Row menu on the interviews table (recruiter only, since only that controller has the route) |
| Reports | `GET /reports/{sessionId}` | No section; reachable only by drilling into a row | **Reports** sidebar entry, a searchable table of completed interviews with score and recommendation |
| Report PDF | `GET /reports/{id}/export.pdf` | Only on the report page | Also in the Reports row menu and on the interview Result tab |
| Filtered Excel export | `GET /{role}/interviews/export.xlsx` | Card at the bottom of a 491-line page | Toolbar button on Interviews and header action on Reports |
| `needsAttention` | computed per management page load | Shown only on that page | Also on both staff dashboards |

Two dead templates were removed: `admin/interviews.html` and
`recruiter/interviews.html`. Neither was referenced — both list routes render
`interview/manage` through `InterviewManagementController.renderManagement`.

### The Reports read model

`InterviewQueryService.reportPage(filter, userId, role)` is the only backend
addition. It is built **on top of** `search()` rather than beside it: search
already applies the caller's authorization scope, validates the filter, sorts
and pages, so reusing it means the Reports page cannot drift from the interview
list and there is no second place where scope could be got wrong. Cost is the
page's own query plus two batched lookups, never one per row.

A completed interview with no report is kept and shown as **Report pending**
rather than hidden — that state is real (it is what the "Missing report"
attention item refers to), and hiding it would make a page called Reports
quietly disagree with the interview list.

---

## 7. Design system

`static/css/app.css`, organised as tokens → base → shell → page header →
surfaces → tabs → tables → forms → feedback → page-specific → responsive →
print.

Two rules keep it consistent as pages are added:

1. **Colour is only ever referenced through a token.** No literal hex below the
   token block, apart from the handful of tinted status backgrounds.
2. **Status colour is semantic and identical everywhere.** A `COMPLETED`
   interview is the same green on a dashboard, in a table and on a report.

| Meaning | Token | Used by |
|---|---|---|
| Primary | `--primary` `#2f5bd8` (unchanged brand) | primary buttons, active nav, links |
| Success | `--success` | `COMPLETED`, `RECOMMENDED`, enabled account |
| Warning | `--warning` | `IN_PROGRESS`, `ACTIVE`, `FURTHER_REVIEW`, proctoring observation |
| Danger | `--danger` | `NOT_RECOMMENDED`, delete, validation error |
| Info | `--info` | `SCHEDULED`, explanatory notices |
| Neutral | `--muted` | `CANCELLED`, disabled, secondary text |

Spacing comes from a `--space-*` scale; radii and shadows each have three steps.
`frontend/src/styles.css` (the exam bundle) carries the **same token values** so
the two halves look like one product. Only tokens are shared, never layout — the
exam is a single-task screen, not an app shell, and its markup and state
handling were deliberately left alone.

**Decorative tokens** (`--accent`, `--brand-gradient`, `--shadow-xl`,
`--shadow-glow`, `--ease`) are a second, smaller set, added for visual polish
without touching the semantic palette above. They are never used for status or
meaning — only for gradients, elevation and motion on brand elements (buttons,
the sidebar mark, the avatar, the login hero). `--primary` itself is unchanged;
the "grand" feel comes from pairing it with `--accent` in gradients, not from
retuning the brand colour.

Every interactive primitive (`button`/`.btn`, `.stat`, table rows, tabs) now
transitions on hover — a small lift and a deepened shadow via `--ease`, driven
entirely by CSS on existing selectors, so no template markup changed to get it.
`@media (prefers-reduced-motion: reduce)` (already global) freezes all of it to
an instant state change.

### The login page

`login.html` is the one screen every person who ever uses this system sees, so
it is the one exception to "enhance styles, don't restructure": it now renders
`.auth-frame-split` — a single elevated surface pairing a branded `.auth-hero`
panel with the sign-in `.login-card`, rather than a bare centred form.

- **Forgot/reset password and the 404 page are untouched and unaffected.**
  They still render a plain `.login-card` inside `.login-wrap`; they simply
  inherit the richer backdrop and deeper shadow now defined on those shared
  classes, without a single line of their own markup changing.
- **The demo-account buttons fill the form; they never submit it.** Each one
  carries the same email/password already printed as plain text beside it
  (`data-fill-account`, wired in `app.js`), so clicking one grants nothing a
  reader could not already type themselves — signing in stays a deliberate,
  separate click.
- **The password show/hide toggle only ever flips the input's own `type`
  attribute** (`data-toggle-password`). With JavaScript disabled the field is
  still an ordinary, working password input, just without the toggle.
- The floating background washes on `.login-wrap` are pure decoration behind
  `z-index: 0`; removing them would change nothing else on the page.

### The auth palette — deliberately not the app's palette

The four auth screens (login, forgot/reset password, 404) use a second, dark
palette — `--auth-*` tokens, near-black surfaces with a single red accent —
kept **entirely separate** from `--primary`/`--brand-gradient`, which the rest
of the application still uses unchanged. Every dark-theme rule is written as
`.login-wrap <selector>`, so it can only ever match inside the auth shell;
nothing outside it can be recoloured by these tokens existing.

- **Red is spent deliberately, not painted broadly.** On a dark surface red
  reads faster than any other hue, so it is reserved for the things meant to
  draw the eye — the primary button, links, the focus ring, the hero gradient —
  while body text, labels and borders stay neutral greys. This follows the
  same restraint current login-page design guidance calls for: a tight colour
  system and motion that confirms state rather than decorates for its own sake.
- **Only the primary action gets the shine-sweep and glow**
  (`.login-wrap button:not([class])`, `.login-wrap a.btn`) — precise enough to
  exclude the demo-account rows and the password toggle, which are deliberately
  quieter controls, not primary calls to action.
- **The entrance animation (`auth-rise`) needs no `prefers-reduced-motion`
  override.** It is applied entirely through the `animation` shorthand with no
  static `opacity: 0` declared anywhere outside the keyframes; when the
  existing global rule forces `animation: none`, the element simply renders at
  its ordinary default (fully visible, untransformed) rather than getting
  stuck mid-animation.
- **The brand mark's pulse** (`auth-pulse`) is the one continuous animation on
  the page — a slow ring-width shift, nothing that moves position — meant to
  read as "still watching" rather than as decoration.

### The theme toggle — "Bold" vs "Classic"

Every rule in the auth section reads colour only through `--auth-*` custom
properties — that discipline is what makes the toggle a ~90-line CSS block
instead of a second copy of every component rule. `.login-wrap[data-theme=
"light"]` overrides the token *values* once; every component that already
consumes those tokens re-colours itself automatically.

- **"Classic" is not a second hand-picked palette.** Every one of its values
  is `var(--primary)`, `var(--brand-gradient)`, `var(--surface)` and so on —
  the same tokens the rest of the application already uses — so it cannot
  visually drift from the main app no matter how either palette changes later.
- **The switch (`.theme-switch`) is built from the same `--auth-*` tokens it
  controls**, so it re-colours along with the page rather than needing its own
  light/dark variant rules.
- **Login-only, on purpose.** The toggle markup (`data-theme-toggle`,
  `data-set-theme`) exists solely in `login.html`; `initThemeToggle` in
  `app.js` no-ops everywhere else, including the other three screens that
  share `.login-wrap`'s base styling. They keep the signature theme.
- **A presentation choice, not a setting.** The chosen theme is remembered in
  `localStorage` — per browser, never sent to the server — so it survives a
  refresh but is not part of any account, and a fresh browser always meets the
  signature theme first.
- **Audited, not assumed:** every `--auth-*` token actually referenced
  anywhere in the auth CSS has a corresponding line inside the light override
  block — checked by a small script during development, since a missed token
  would silently leak the red theme through "Classic" rather than failing
  loudly.

---

## 8. Behaviour (`static/js/app.js`)

Small, dependency-free and **progressive**: every page still shows all of its
content with JavaScript disabled. The file only collapses, groups and focuses
what is already there. Everything is wired by data attribute, so a new page
picks up the behaviour from the markup — there is nothing per-page to register.

| Attribute | Behaviour |
|---|---|
| `data-nav-toggle` | Sidebar drawer below 900 px |
| `data-tabs` + `role="tab"` | Panel switching, arrow-key movement, tab mirrored into the URL hash |
| `data-menu` | Row "more actions" menu; closes on outside click and Escape |
| `data-toggle="#panel"` | Show/hide with honest `aria-expanded` |
| `data-open="#panel"` | Open a panel from elsewhere and scroll to it |
| `data-selection` | Selection bar, page/all checkboxes, bulk confirmations |
| `data-select-only` | Narrow the selection to one row before a row-level delete |
| `data-confirm` / `data-confirm-bulk` | Confirmation text (`{scope}` is substituted) |
| `data-submit-once` | Double-click guard on a slow submit |
| `data-copy="#input"` | Copy an invite link |
| `data-toggle-password="#input"` | Show/hide a password field by flipping its `type` |
| `data-fill-account` + `data-email`/`data-password` | Login page only: fills the sign-in fields, never submits |

**A nested `<form>` is invalid HTML**, which is why a row-level delete is a
`formaction` submit inside the bulk form that first narrows the selection to
itself — not its own form. The server re-validates every target regardless.

---

## 9. Accessibility

- Landmarks: `<aside class="sidebar">`, `<header class="topbar">`,
  `<main class="page">`, `<nav>` on every navigation group.
- `aria-current="page"` on the active sidebar entry and tab; `aria-current="true"`
  on the active summary tile; `aria-current="step"` on the bulk step.
- Full `role="tablist"/"tab"/"tabpanel"` wiring with `aria-controls`,
  `aria-selected`, roving `tabindex` and arrow/Home/End keys.
- Every control has a label — `visually-hidden` where the design shows none
  (search boxes, per-row checkboxes, page-size select, invite-link inputs).
- Focus is never removed, only restyled: a 2 px `--primary` ring via
  `:focus-visible`, plus a 3 px ring on focused inputs.
- Required fields are marked in the label (`*`) *and* stated in the page
  description, rather than relying on colour.
- Flash messages carry `role="status"` / `role="alert"`.
- Tables have `<caption class="visually-hidden">` and `scope="col"` headers.
- `prefers-reduced-motion` disables all transitions.

---

## 10. Performance

The restructure removed more queries than it added.

| Screen | Before | After |
|---|---|---|
| Admin dashboard | `userService.listAll()` + `interviewService.listAll()`, mapping **every** interview through `toSummary` (a session lookup **and** a report check per interview) to display five | 2 count queries + 6 scoped counts + one 20-row page |
| Recruiter dashboard | Every interview this recruiter owns, to count them and show five | Same three shared reads |
| Reports (new) | — | The page query + 2 batched lookups, never per row |
| Candidate dashboard | One query | Unchanged — tabs switch in the browser |

`UserService.countByRole` and `UserRepository.countByRole(AndEnabled)` were added
so a dashboard tile is a count, not a fetch. Both staff dashboards read from
`InterviewManagementController.insights / attention / recentInterviews`, so they
cannot drift apart.

---

## 11. Rules for adding a screen

1. Use the shell fragments. Do not hand-roll a page chrome.
2. Pass the correct `active` key so the sidebar highlights one entry.
3. A new *status* or *filter* is a tab, not a sidebar entry.
4. Anything optional and large starts collapsed (`data-toggle`).
5. Give every data-driven region three states: **loading is not applicable**
   here (pages are server-rendered and arrive complete), but **empty** and
   **error** are mandatory, and an empty state says what to do next, never
   "No data".
6. Colour comes from a token; status colour comes from the table in §7.
7. **`th:each` and `th:replace` must not sit on the same element** — see the
   trap below.
8. If a role cannot reach a route, do not render its link — and do not rely on
   that for access control.

---

## 11a. Scheduling for several candidates

`/{role}/interviews/new-multi` is the single-schedule form with one field
changed: a `<select multiple>` of candidates instead of one. Everything else -
name, date, domain, type, question count, visibility - is shared by every
interview it creates, which is what makes it useful for a drive.

Three rules it inherits rather than reinvents:

- **Every candidate goes through `InterviewService.create`**, the same method
  the single form uses. A multi-scheduled interview is an ordinary interview,
  with its own invite token, and this path can never produce a record the single
  form would have rejected.
- **Each candidate is committed in its own transaction**
  (`MultiScheduleRowScheduler`, `REQUIRES_NEW`). One refusal - a disabled
  account, an experienced candidate with no years - is reported and skipped
  rather than discarding the interviews already created for everyone else.
  Separate bean for the usual proxy reason.
- **The dropdown is a convenience, not the control.** The server validates every
  submitted id, so a hand-crafted request naming a recruiter or an admin is
  refused per row ("The selected user is not a candidate") while the valid rows
  still go through.

A duplicate selection creates one interview, not two, and `MAX_CANDIDATES`
(100) caps a single submission - past that, bulk-from-Excel is the right tool.
On success the caller lands on the interview list filtered to that interview
name, which is where the new records and their invite links already are.

---

## 11b. Error pages

`templates/error/404.html` is picked up by Spring Boot's
`DefaultErrorViewResolver`, which looks for `error/{status}` before
`error/{series}` — no controller and no configuration. It replaces the
Whitelabel page for any unmapped URL.

It is **standalone, not inside the shell**: the page is reachable while signed
out, and the sidebar fragment reads the authenticated principal, so rendering
the shell for an anonymous visitor would turn a tidy 404 into a 500.

Two behaviours worth knowing:

- **An anonymous visitor to an unknown protected path gets `/login`, not the
  404.** `anyRequest().authenticated()` runs first. That is the desired
  outcome — the response does not reveal whether the path exists. The 404 page
  is reached anonymously only on `permitAll` paths (e.g. a missing file under
  `/css/**`), which is why it still carries a "Go to sign in" variant.
- **Content is negotiated.** A browser (`Accept: text/html`) gets the page; an
  API client gets the usual JSON error body. The React exam app is unaffected.

Its single action points at `/`, which already routes correctly for every role.

---

## 12. Traps found while building this

- **`th:each` + `th:replace` on one element silently renders once with the loop
  variable unbound.** `th:replace` has precedence 100 and `th:each` 200, so the
  fragment is included *before* the loop exists; the first `row.*` call then
  fails on null and the response is already committed, so the browser gets a
  half-written page rather than an error page. Wrap the loop in a `<th:block
  th:each>` and put `th:replace` on an inner element. This pre-dated the
  restructure: it broke the candidate dashboard for any candidate who actually
  had an interview, and survived because the only test covered the empty
  dashboard. `CandidateDashboardTest.candidateWithInterviewsSeesThemRendered`
  now covers the populated case.
- **A duplicated form field wins over the visible control.** The management page
  briefly carried hidden `sortBy`/`sortDirection` inputs *and* the selects in
  the filter panel; the hidden copies submitted first and the panel's selection
  was silently ignored. A `hidden` panel still submits its inputs, so the hidden
  copies were simply deleted.
- **`th:attr` separates assignments with a comma**, so a comma inside an
  attribute value splits it. Confirmation strings are written without commas.
- **`/recruiter/**` is closed to an admin.** The bulk-scheduling result page
  linked there unconditionally, so an admin finishing a bulk upload landed on a
  403. Return links now follow the role.

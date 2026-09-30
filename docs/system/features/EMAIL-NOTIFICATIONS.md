# Email notifications

Sends a candidate their interview invitation — the link and the details — when
an interview is scheduled. Works on every scheduling path, and is **off by
default**.

This reverses an earlier scope decision. Email integration was listed as out of
scope through Phase 10, and `LIMITATIONS.md` said so; it was added afterwards at
the user's request. Nothing else about scheduling changed to accommodate it.

---

## Setup (Gmail)

Gmail will **not** accept your normal account password over SMTP. You need a
16-character **App Password**, which requires 2-Step Verification on the account.

1. Turn on 2-Step Verification: <https://myaccount.google.com/signinoptions/two-step-verification>
2. Create an App Password: <https://myaccount.google.com/apppasswords>
   — pick "Mail" / "Other", name it anything, and copy the 16 characters.
3. Put it in the **gitignored** `config/application.yml` at the repo root:

```yaml
spring:
  mail:
    username: aswinsenthilkumarcse@gmail.com
    password: "abcdefghijklmnop"   # the 16-char app password, no spaces

app:
  mail:
    enabled: true
    from: aswinsenthilkumarcse@gmail.com
    base-url: http://localhost:8080   # must be what candidates can reach
```

Or as environment variables, which override the file:

```bash
MAIL_ENABLED=true
MAIL_FROM=aswinsenthilkumarcse@gmail.com
MAIL_USERNAME=aswinsenthilkumarcse@gmail.com
MAIL_PASSWORD=<16-char app password>
MAIL_BASE_URL=https://test.triospark.in
```

**The password never goes in a tracked file.** `config/` is gitignored (with a
leading slash — see the trap in `CLAUDE.md`), which is the same place the Gemini
key and the database password already live.

### `base-url` matters

The invite link has to be absolute to be clickable in a mail client, and only
the deployment knows its own public address. Left at the default,
candidates receive `http://localhost:8080/exam/...`, which works only on the
server itself. Set it to the real hostname before inviting anyone.

A trailing slash is tolerated and stripped.

---

## What gets sent

**Three emails, and only three.**

| Email | When | Contains | How it is sent |
|---|---|---|---|
| **Interview invitation** | every interview scheduled, all paths | interview name, date and time, duration, question count, type and domain, the invite link, what to expect, and the monitoring notice | `InterviewScheduled` event, after commit, on a background thread |
| **Account credentials** | only when a bulk upload creates a new candidate account | email, the generated password, and a link to sign in | `CandidateAccountCreated` event, same path |
| **Password reset** | somebody submits `/forgot-password` for a known, enabled account | a single-use link and how long it lasts (`app.password-reset.expiry-minutes`, default 30) | called **directly** by `PasswordResetPageController` — see below |

The invitation and the credentials are separate on purpose. The password is
delivered once, when the account is created; an existing candidate scheduled for
a second interview gets the invitation only. The stored hash cannot be read back,
so it could not be re-sent even if that were wanted.

### The password-reset email takes a different path

It is **not** an application event and **not** on the async listener. The page
controller calls `InterviewMailService.sendPasswordReset` inline, because the
request that triggers it is the one a person is waiting on, and there is no
transaction whose commit it should follow.

Two consequences worth knowing:

- **The page's answer never depends on whether mail was sent.** Submitting
  `/forgot-password` reports the same thing for a real address, an unknown one
  and a disabled account — the same no-account-enumeration rule as login. It says
  so even when mail is switched off entirely, because branching there would leak
  exactly the fact the wording protects. The form page states plainly, up front,
  when mail is off.
- **Only the SHA-256 of the token is stored** (`password_reset_tokens`), so the
  emailed link is the only copy that ever exists. Requesting again supersedes the
  earlier link; redeeming one clears the account's login throttle.

The invitation repeats the project's wording rule: proctoring produces
**observations, not accusations**, and video never leaves the browser.

---

## Which scheduling paths are covered

All of them, and by construction rather than by remembering:

| Path | Route |
|---|---|
| One interview | `/{admin\|recruiter}/interviews/new` |
| Several candidates | `/{admin\|recruiter}/interviews/new-multi` |
| Bulk from Excel | `/scheduling/bulk` |

Every one of these calls `InterviewService.create`, which is where the
`InterviewScheduled` event is published — so a fourth scheduling path added
later inherits the invitation automatically. (Editing an interview does **not**
re-send anything: the invite link never changes, so there is nothing new to
tell the candidate.)
`SchedulingEmailCoverageTest` asserts each path independently rather than
trusting the shared call.

---

## Design notes

**Sending happens after the transaction commits.** `SchedulingMailListener` uses
`@TransactionalEventListener(AFTER_COMMIT)`. Sending from inside the transaction
would mean a row that later rolled back had already told a candidate their
interview exists, and email cannot be recalled. For the bulk upload — where each
row commits in its own `REQUIRES_NEW` transaction — this also means one
candidate's invitation goes out as soon as their row succeeds, and a later row
failing neither suppresses it nor re-sends it.

**Sending can never break scheduling.** Every failure is caught and logged, never
rethrown. An unreachable SMTP server, a rejected app password or a malformed
address leaves the interview committed and usable through the invite link on its
own page — exactly the behaviour that existed before email. The recruiter loses a
convenience, not an interview.

**Sending is off the request thread.** The listener is `@Async`. An SMTP round
trip is roughly a second, so confirming a fifty-row bulk batch would otherwise
hold the page open while the server talked to Gmail fifty times, for work that
was already finished and already on screen.

**The events carry snapshots, not entities.** `open-in-view` is off, and the
listener runs after the transaction closes, so passing an entity would throw
`LazyInitializationException` the moment the template read the candidate's name.
The handful of needed fields are copied while the entities are still attached.

**Off by default.** With `app.mail.enabled=false` nothing is sent, no SMTP
connection is attempted, and no message is even constructed. Enabling it without
`app.mail.from` is treated as still-off and logged, rather than failing at
startup — the same "fall back, do not fail" rule the interview modes follow.

---

## Troubleshooting

Sending is deliberately quiet — it never interrupts the recruiter — so the log
is where to look. Search for `InterviewMailService`.

| Log line | Meaning |
|---|---|
| `Sent mail/interview-invite email for interview 12 to a***@example.com` | worked |
| `app.mail.enabled is true but app.mail.from is not set` | set `app.mail.from` |
| `Could not send ... 535-5.7.8 Username and Password not accepted` | not an App Password, or 2-Step Verification is off |
| `Could not send ... Couldn't connect to host` | firewall, or port 587 blocked |
| nothing at all | `app.mail.enabled` is still `false` |

Addresses are partly redacted in the log (`a***@example.com`) so a full mailbox
list is not written out for every interview scheduled.

Gmail's free tier allows roughly 500 recipients per day, which a bulk drive can
reach. There is no rate limiting or retry queue in the prototype.

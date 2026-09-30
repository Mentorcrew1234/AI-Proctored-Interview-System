# Hosting this on a VPS from scratch — a walkthrough, not a reference

This is the "I have a blank Ubuntu server and nothing else" version. It exists
because `DEPLOYMENT.md` is a **reference** — organised by topic, assumes Java
and Node are already installed, and jumps around depending on what you're
looking for. This document is the opposite: one path, top to bottom, from an
empty VPS to a working HTTPS deployment, explaining *why* at each step so you
can actually debug it later instead of just re-running commands.

Read `docs/system/deployment/DEPLOYMENT.md` for the full reference (every
config key, the nginx caching detail, upgrade procedure). Read this one first,
once, the first time you do this.

---

## What you need before you touch the terminal

- **A VPS running Ubuntu 22.04 or 24.04 LTS**, with root or sudo SSH access.
  1 vCPU / 1–2 GB RAM is enough for prototype-scale use (see the Memory
  section below for why).
- **A domain name** (or subdomain) you can point at the VPS's IP address —
  e.g. `interviews.yourdomain.com`. **This is not optional.** The proctoring
  camera will not work without HTTPS, and you cannot get a real TLS
  certificate for a bare IP address. Buy or reuse any domain, add an A record
  pointing at the VPS's IP, and wait for it to resolve (`ping
  interviews.yourdomain.com` from your own machine) before starting Part 4.
- **A Gemini API key**, optional. Get one free at https://ai.google.dev if you
  want live AI-generated questions; skip it and the app runs entirely on its
  offline question bank.

---

## Part 1 — Basic server setup (10 minutes)

Do this on **any** fresh VPS regardless of what you're deploying to it. Skipping
it is the single most common way a first server gets compromised within days.

```bash
# SSH in as root the first time
ssh root@YOUR_SERVER_IP

# Create a non-root user with sudo rights — never run the app as root
adduser deploy
usermod -aG sudo deploy

# Copy your SSH key so you can log in as `deploy` without a password
rsync --archive --chown=deploy:deploy ~/.ssh /home/deploy

# Basic firewall: only SSH, HTTP and HTTPS get in from the internet
apt update && apt install -y ufw
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw enable

# Log out and back in as `deploy` from here on
exit
ssh deploy@YOUR_SERVER_IP
```

**Common mistake:** leaving port 8080 (the app's own port) open to the
internet. It should only ever be reachable from `localhost`, because nginx is
what terminates TLS and forwards to it. `ufw` above never opens 8080, and
nothing later in this guide should either.

---

## Part 2 — Install the software stack

Four things: Java 25, Node.js (build-time only), a database, and nginx.
Ubuntu's own package repositories lag upstream releases by months to years —
for a project pinned to Java 25 and Node 24, use each project's own apt
repository rather than `apt install openjdk-25-jdk`, which will not exist yet
on a stable Ubuntu release.

### Java 25 (Eclipse Temurin)

```bash
sudo apt update && sudo apt install -y wget apt-transport-https gpg
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update
sudo apt install -y temurin-25-jdk

java -version   # confirm: openjdk version "25..."
```

### Node.js 24 (for building the frontend — the JAR is all the server needs afterwards)

```bash
curl -fsSL https://deb.nodesource.com/setup_24.x | sudo -E bash -
sudo apt install -y nodejs
node -v && npm -v   # confirm: v24.x / 11.x
```

### MySQL 8

```bash
sudo apt install -y mysql-server
sudo mysql_secure_installation   # set a root password, answer yes to the rest
```

(MariaDB 10.6+ works too — the project's migrations are written against
MySQL 8 but tested compatible; see `DEPLOYMENT.md`'s MariaDB note if that's
what your VPS provider images by default.)

### nginx + certbot (for HTTPS)

```bash
sudo apt install -y nginx certbot python3-certbot-nginx
```

---

## Part 3 — Get the code on, build it

```bash
sudo mkdir -p /opt/proctor/config
sudo chown deploy:deploy /opt/proctor

cd /opt/proctor
git clone <your-repository-url> src-checkout
cd src-checkout

# Frontend FIRST, always. Maven packages whatever is already sitting in
# src/main/resources/static/exam/ - build the JAR before the frontend
# and it silently ships the OLD exam screen with no error.
cd frontend && npm install && npm run build && cd ..

./mvnw clean package
# -> target/proctor-interview-0.0.1-SNAPSHOT.jar

cp target/proctor-interview-0.0.1-SNAPSHOT.jar /opt/proctor/proctor-interview.jar
```

**Common mistake:** running `./mvnw package` before `npm run build`. The
build succeeds either way — nothing errors — so you get a JAR that *looks*
fine and serves a stale or blank exam screen. If the candidate-facing side
ever looks wrong after a rebuild, this is the first thing to check.

No `git` on the server, or you'd rather not clone the repo there? Build on
your own laptop instead and `scp` just the finished JAR up — the server never
actually needs Node or the source tree, only the JAR. That's why `DEPLOYMENT.md`
calls Node "build machine only."

---

## Part 4 — Database

```bash
mysql -u root -p -e "
CREATE DATABASE proctor_interview CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'proctor'@'localhost' IDENTIFIED BY 'choose-a-real-password-here';
GRANT ALL PRIVILEGES ON proctor_interview.* TO 'proctor'@'localhost';
"
```

Don't create any tables yourself — Flyway does that automatically on the
application's first start, and it's the only thing allowed to.

---

## Part 5 — Secrets

**Never put a secret in a systemd `Environment=` line** (see Part 7 — this is
the single most consequential mistake in this whole guide, not a minor one).
Use a file instead:

```bash
sudo tee /etc/proctor-interview.env > /dev/null <<'EOF'
DB_HOST=localhost
DB_PORT=3306
DB_NAME=proctor_interview
DB_USER=proctor
DB_PASSWORD=the-password-you-set-in-part-4

JWT_SECRET=generate-32-plus-random-characters-here-never-use-the-default

INTERVIEW_MODE=NORMAL
INTERVIEW_AI_MODE=MOCK
GEMINI_API_KEY=

MAIL_ENABLED=false
MAIL_BASE_URL=https://interviews.yourdomain.com
EOF

sudo chmod 600 /etc/proctor-interview.env
sudo chown root:root /etc/proctor-interview.env
```

Generate a real `JWT_SECRET` rather than typing one:

```bash
openssl rand -base64 48
```

**Common mistake, and the one this project has already been burned by once:**
putting these values directly in the systemd unit as `Environment=DB_PASSWORD=...`
lines. Systemd exposes every `Environment=` value through its D-Bus API —
`systemctl show proctor-interview -p Environment` prints them to **any** local
user on the box, regardless of the unit file's own permissions. On a shared
VPS running other sites under other service accounts, that's every one of
them able to read your database password. `EnvironmentFile=` (used in Part 7)
avoids this entirely — the same `systemctl show` command reveals nothing.

---

## Part 6 — First run, in the foreground

Do **not** install the systemd service yet. Run it by hand first and watch
what happens:

```bash
cd /opt/proctor
set -a; source /etc/proctor-interview.env; set +a
java -Xms256m -Xmx512m -jar proctor-interview.jar
```

Watch for, in order:

```
Migrating schema `proctor_interview` to version "1 - baseline"
...
Successfully applied 13 migrations
Started ProctorInterviewApplication
```

If you see that, `Ctrl+C` it and move on to Part 7. If you don't:

- **`SchemaManagementException`** — a migration didn't run, or you pointed it
  at a database that already has tables in it from somewhere else. Don't
  "fix" this by changing `ddl-auto` — start from an empty database.
- **Connection refused to the database** — `DB_HOST`/`DB_PORT` wrong, or MySQL
  isn't actually running (`sudo systemctl status mysql`).
- **It starts, but slowly** — on a small VPS (under ~1 GB RAM), first startup
  can take a minute or two. Wait for the `Started` line before concluding
  anything is wrong; a 502 from nginx in that window just means the app
  hasn't finished booting.

This step is not optional, and it's not just about catching startup errors: it's
the only place the JWT-secret and mode warnings print. A service that "starts
successfully" with the packaged default secret looks identical, from the
outside, to one configured correctly.

---

## Part 7 — Run it as a service

```bash
sudo tee /etc/systemd/system/proctor-interview.service > /dev/null <<'EOF'
[Unit]
Description=AI-Based Proctored Interview System
After=network.target mysql.service

[Service]
User=deploy
WorkingDirectory=/opt/proctor
ExecStart=/usr/bin/java -Xms256m -Xmx512m -jar /opt/proctor/proctor-interview.jar
EnvironmentFile=/etc/proctor-interview.env
SuccessExitStatus=143
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now proctor-interview
sudo journalctl -u proctor-interview -f   # Ctrl+C once you see "Started ProctorInterviewApplication"
```

Note `EnvironmentFile=`, not a list of `Environment=` lines — see Part 5.

---

## Part 8 — nginx and HTTPS

This is the step that actually matters most: **the interview does not work at
all over plain HTTP**, on any hostname except `localhost`. `getUserMedia` (the
camera) refuses to run outside a secure context, so without this step the
device-check screen fails for every real candidate, silently, with no camera
prompt at all.

```bash
sudo tee /etc/nginx/sites-available/proctor-interview > /dev/null <<'EOF'
server {
    listen 80;
    server_name interviews.yourdomain.com;

    location / {
        proxy_pass         http://127.0.0.1:8080;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        client_max_body_size 20m;
        proxy_read_timeout   120s;
    }
}
EOF

sudo ln -s /etc/nginx/sites-available/proctor-interview /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx

# Now get a real certificate - certbot edits the config above to add the
# listen 443 block and the certificate paths automatically.
sudo certbot --nginx -d interviews.yourdomain.com
```

Then tell Spring Boot to trust the forwarded headers, or the redirect after
sign-in will loop back to `http://` and break:

```bash
echo "SERVER_FORWARD_HEADERS_STRATEGY=framework" | sudo tee -a /etc/proctor-interview.env
sudo systemctl restart proctor-interview
```

**Common mistake:** forgetting this last step. Everything else works, TLS is
correctly set up, and then every sign-in redirects to a broken `http://` URL.
If that happens, this is what you missed.

---

## Part 9 — Prove it actually works

Don't stop at "the login page loads." In order:

1. Open `https://interviews.yourdomain.com/login` — padlock, not a browser
   warning.
2. Sign in as each seeded demo role (`admin@demo.local` / `Admin@123`, etc. —
   passwords are in the repo's `README.md`, which is why the next line
   matters) and **rotate all three demo passwords immediately** — they're
   public.
3. Open an interview's invite link in **Chrome or Edge** and reach the device
   check — this is the step that actually exercises the camera, and the one
   most likely to expose a missed HTTPS step.
4. Sit one complete interview end to end and open its report. If the
   Proctoring tab says "monitoring incomplete," the vision models aren't
   loading — check that nginx is actually serving `/exam/models/` and
   `/exam/wasm/` (they're large; a proxy timeout on a slow connection can look
   like this too).
5. If you set `INTERVIEW_AI_MODE=GEMINI`, check `/admin/ai-health` — it's the
   only place a bad API key becomes visible, because the offline fallback
   quietly answers instead and every interview still completes.

---

## The mistakes that actually happen, ranked

1. **Secrets in `Environment=` instead of `EnvironmentFile=`.** Covered in
   Part 5. Do this wrong on a shared host and every other tenant's compromised
   process can read your database password.
2. **No HTTPS, or a proxy that doesn't forward `X-Forwarded-Proto`.** The
   camera silently fails; the login redirect silently breaks. Both look like
   application bugs and are actually nginx configuration.
3. **Building the JAR before the frontend.** No error, just a stale or blank
   exam screen. Always `npm run build` first.
4. **Leaving `JWT_SECRET` at its packaged default.** It's in the public
   repository. Anyone can mint a valid session token for any account until
   you change it.
5. **Not watching the first startup in the foreground.** A systemd service
   that "starts" tells you nothing about which secrets it actually picked up.
   Run it by hand once, read the log, then install the service.
6. **Assuming a 502 right after starting the service means it failed.** On a
   small VPS, give it a minute or two and check `journalctl` for `Started
   ProctorInterviewApplication` before concluding anything.
7. **Forgetting the demo account passwords are public.** They're printed in
   this repository's own documentation. Rotate them the moment the app is
   reachable from the internet.

---

## Where to look when something's wrong

| What broke | Where to look |
|---|---|
| The app itself (won't start, 500 errors) | `sudo journalctl -u proctor-interview -f` |
| nginx / the proxy (502, 504, redirect loops) | `sudo tail -f /var/log/nginx/error.log` |
| The database | `sudo journalctl -u mysql -f`, or `mysql -u proctor -p proctor_interview` and look for the tables Flyway should have created |
| Certificate renewal | `sudo certbot renew --dry-run` |
| "Is my secret actually loaded correctly" | `sudo systemctl show proctor-interview -p Environment` — with `EnvironmentFile=` this correctly prints nothing; if it prints your actual values, you made the Part 5 mistake |

For anything not covered above, `docs/system/deployment/DEPLOYMENT.md` has a
full troubleshooting table (symptom → cause → fix) and `docs/system/quality/LIMITATIONS.md`
lists what this deployment is not built to do — worth reading both before
assuming something is broken rather than working as designed.

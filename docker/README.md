# Docker — build and run locally

This is not how the project's own VPS deployment runs (that's bare-metal +
systemd; see `docs/system/deployment/DEPLOYMENT.md`). This exists so the
application can be built and tested as a container image. Docker was
deliberately kept out of the main deployment path — see the note in the
project's `CLAUDE.md` scope guardrails — so treat this folder as optional
tooling, not the recommended way to run the app.

All commands below run from the **repo root**, not from inside `docker/`,
because the build needs both `frontend/` and `src/` in its context.

## 1. Build the image

```bash
docker build -f docker/Dockerfile -t proctor-interview:latest .
```

Takes a few minutes the first time (downloads Maven, npm packages, base
images); later builds reuse Docker's layer cache. Requires network access
during the build — the Maven wrapper downloads Maven itself on first use, and
npm installs frontend dependencies.

## 2. Run it locally to test

You need a MySQL 8 instance reachable from the container. Easiest path is
`docker compose`, which starts one for you:

```bash
cp docker/.env.example docker/.env
# edit docker/.env: set DB_PASSWORD and JWT_SECRET at minimum

docker compose --env-file docker/.env -f docker/docker-compose.yml up --build
```

Then open **http://localhost:8080** and sign in with the seeded accounts
(`admin@demo.local` / `Admin@123`, etc. — see the main `README.md`).

To run just the image you built in step 1, against a MySQL you already have
running elsewhere:

```bash
docker run --rm -p 8080:8080 \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=3306 \
  -e DB_NAME=proctor_interview \
  -e DB_USER=root \
  -e DB_PASSWORD=your-password \
  -e JWT_SECRET=at-least-32-random-characters \
  proctor-interview:latest
```

(`host.docker.internal` reaches the host machine from inside the container on
Docker Desktop for Windows/Mac; on Linux use `--network host` instead, or the
host's real LAN IP.)

## Stopping and cleaning up

```bash
docker compose -f docker/docker-compose.yml down          # stop, keep the DB volume
docker compose -f docker/docker-compose.yml down -v       # stop and delete the DB volume too
```

## Things worth knowing before you rely on this

- **Camera access needs HTTPS.** `getUserMedia` refuses to run outside a
  secure context. `localhost` is exempted by browsers, so this works fine for
  local testing on your own machine; a real deployment still needs TLS in
  front of the container (a reverse proxy — see `DEPLOYMENT.md`'s nginx
  section), which this Dockerfile does not attempt to solve.
- **`INTERVIEW_AI_MODE` defaults to `MOCK`** in `docker/.env.example`, so the
  container runs fully offline (no Gemini calls) until you set a real
  `GEMINI_API_KEY` and switch it to `GEMINI`.
- **`docker/.env` holds real secrets once you fill it in.** Don't commit it.
- The healthcheck hits `/login`, which is public — a `200` there means the
  Spring context actually started, not just that the JVM is alive.
- Tests are *not* run as part of `docker build`. Run `mvnw test` and
  `cd frontend && npx vitest run` yourself before you trust an image enough
  to ship it.

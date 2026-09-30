@echo off
REM Sequential commands to run the AI Proctored Interview System locally.
REM Prereqs: MySQL 8 running on port 3360, schema proctor_interview,
REM          config\application.yml has your DB password + Gemini key.

echo === 1/2: building frontend (only needed after frontend changes) ===
cd frontend
call npm install
call npm run build
cd ..

echo === 2/2: starting Spring Boot (http://localhost:8080) ===
call mvnw.cmd spring-boot:run

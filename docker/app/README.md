# Application images

The application already has its Dockerfiles next to its code, and docker-compose.yml builds them from there:

| Service | Dockerfile | What it is |
|---|---|---|
| `backend` | [backend/Dockerfile](../../backend/Dockerfile) | Spring Boot API: Maven build on JDK 21, runs on a JRE 21 image as a non-root user, port 8080, `JAVA_TOOL_OPTIONS` sets the JVM memory limit |
| `web` | [web/Dockerfile](../../web/Dockerfile) | React app built with Vite, served by nginx |

Each has its own `.dockerignore` in the same folder (the build context). There is deliberately no second
Dockerfile in the project root: two definitions of the same image drift apart.

Build: `scripts\docker-build.bat` (or `docker compose build backend`).

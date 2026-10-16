#!/usr/bin/env python3
"""Exercise packaged Gateway/User/Channel apps against disposable Docker infrastructure."""

import base64
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[2]


def command(arguments, stdin=None, check=True):
    result = subprocess.run(arguments, input=stdin, text=True, capture_output=True)
    if check and result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or "Local test command failed")
    return result


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def wait_port(port, process=None, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise RuntimeError(f"Application exited before port {port} became ready")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                return
        except OSError:
            time.sleep(0.25)
    raise RuntimeError(f"Timed out waiting for local port {port}")


def main():
    logs = Path(tempfile.mkdtemp(prefix="reallive-auth-e2e-"))
    containers = []
    applications = []
    log_handles = []
    redis_paused = False
    redis_name = f"reallive-auth-e2e-redis-{os.getpid()}"
    postgres_name = f"reallive-auth-e2e-postgres-{os.getpid()}"
    ports = []
    while len(ports) < 5:
        candidate = free_port()
        if candidate not in ports:
            ports.append(candidate)
    postgres_port, redis_port, user_port, channel_port, gateway_port = ports
    password = secrets.token_hex(16)
    signer_key = secrets.token_hex(48)
    user_id = str(uuid.uuid4())
    username = "auth_e2e_user"
    login_password = "AuthE2ePassword123!"
    jar = http.cookiejar.CookieJar()
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    anonymous = urllib.request.build_opener()
    gateway = f"http://127.0.0.1:{gateway_port}"

    def sql(database, statement):
        return command(["docker", "exec", "-i", postgres_name, "psql", "-qAt", "-v", "ON_ERROR_STOP=1",
                        "-U", "auth_e2e", "-d", database], stdin=statement).stdout.strip()

    def request(method, path, body=None, content_type=None, authenticated=True):
        if isinstance(body, dict):
            body = json.dumps(body).encode()
            content_type = "application/json"
        headers = {"Content-Type": content_type} if content_type else {}
        outgoing = urllib.request.Request(gateway + path, data=body, headers=headers, method=method)
        try:
            response = (client if authenticated else anonymous).open(outgoing, timeout=10)
        except urllib.error.HTTPError as response:
            return response.code, response.read().decode(), response.headers
        with response:
            return response.status, response.read().decode(), response.headers

    def expect(method, path, status, **kwargs):
        actual, body, headers = request(method, path, **kwargs)
        if actual != status:
            raise AssertionError(f"{method} {path}: expected {status}, got {actual}: {body[:500]}")
        return body, headers

    def eventually(method, path, expected, **kwargs):
        deadline = time.monotonic() + 20
        actual, body = None, ""
        while time.monotonic() < deadline:
            actual, body, _ = request(method, path, **kwargs)
            if actual == expected:
                return
            time.sleep(0.25)
        raise AssertionError(f"{method} {path}: expected eventual {expected}, got {actual}: {body[:500]}")

    def access_cookie():
        return next(cookie.value for cookie in jar if cookie.name == "access_token")

    def enqueue(mutation):
        sql("auth_user", f"""BEGIN;
{mutation}
UPDATE users SET auth_revision = auth_revision + 1 WHERE id = '{user_id}';
INSERT INTO auth_state_sync_tasks (user_id, revision, attempts, next_attempt_at)
SELECT id, auth_revision, 0, timezone('UTC', CURRENT_TIMESTAMP) FROM users WHERE id = '{user_id}'
ON CONFLICT (user_id) DO UPDATE SET revision = EXCLUDED.revision, attempts = 0, next_attempt_at = EXCLUDED.next_attempt_at;
COMMIT;
""")

    def start(module, port, extra):
        jar_path = ROOT / module / "target" / f"{module}-0.0.1-SNAPSHOT.jar"
        if not jar_path.exists():
            raise RuntimeError(f"Build {module} with ./mvnw clean verify before running this check")
        logfile = open(logs / f"{module}.log", "w")
        log_handles.append(logfile)
        arguments = ["java", "-Xmx384m", "-jar", str(jar_path), "--spring.profiles.active=dev",
                     f"--server.port={port}", "--server.address=127.0.0.1",
                     "--spring.data.redis.host=127.0.0.1", f"--spring.data.redis.port={redis_port}",
                     "--spring.data.redis.database=13", "--spring.data.redis.username=", "--spring.data.redis.password=",
                     "--spring.data.redis.connect-timeout=1s", "--spring.data.redis.timeout=1s"] + extra
        process = subprocess.Popen(arguments, cwd=ROOT / module, stdout=logfile, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        applications.append(process)
        wait_port(port, process)
        print(f"Started {module} on 127.0.0.1:{port}", flush=True)

    try:
        for name, image, port_mapping, extra in [
            (postgres_name, "postgres:17-alpine", f"127.0.0.1:{postgres_port}:5432",
             ["-e", "POSTGRES_USER=auth_e2e", "-e", f"POSTGRES_PASSWORD={password}"]),
            (redis_name, "redis:7.4-alpine", f"127.0.0.1:{redis_port}:6379", []),
        ]:
            command(["docker", "run", "--rm", "-d", "--name", name, "-p", port_mapping] + extra + [image])
            containers.append(name)
        wait_port(postgres_port, timeout=45)
        wait_port(redis_port, timeout=20)
        deadline = time.monotonic() + 45
        while command(["docker", "exec", postgres_name, "pg_isready", "-q", "-h", "127.0.0.1",
                       "-U", "auth_e2e", "-d", "postgres"], check=False).returncode:
            if time.monotonic() >= deadline:
                raise RuntimeError("Disposable PostgreSQL did not finish initialization")
            time.sleep(0.25)
        sql("postgres", "CREATE DATABASE auth_user;\nCREATE DATABASE auth_channel;\n")
        storage = ["--storage.r2.endpoint=http://127.0.0.1:9", "--storage.r2.access-key-id=e2e",
                   "--storage.r2.secret-access-key=e2e", "--storage.r2.bucket-name=auth-e2e",
                   "--storage.r2.public-base-url=https://assets.example.invalid"]
        database = ["--spring.datasource.username=auth_e2e", f"--spring.datasource.password={password}"]
        start("user-service", user_port, database + storage + [
            f"--spring.datasource.url=jdbc:postgresql://127.0.0.1:{postgres_port}/auth_user",
            f"--jwt.signer-key={signer_key}", "--jwt.ttl_accessToken=120", "--jwt.ttl_refreshToken=600",
            f"--app.service.channel=http://127.0.0.1:{channel_port}/channel", "--app.service.payment=http://127.0.0.1:9",
            "--spring.kafka.bootstrap-servers=127.0.0.1:9", "--auth.state.retry-enabled=true",
            "--auth.state.retry-delay=1s", "--auth.state.backfill-enabled=false",
            "--auth.cookie.access-token.name=access_token", "--auth.cookie.refresh-token.name=refresh_token",
            "--auth.cookie.access-token.secure=false", "--auth.cookie.refresh-token.secure=false",
            "--auth.cookie.access-token.path=/", "--auth.cookie.refresh-token.path=/user/api/auth"])
        start("channel-service", channel_port, database + storage + [
            f"--spring.datasource.url=jdbc:postgresql://127.0.0.1:{postgres_port}/auth_channel",
            f"--app.service.user=http://127.0.0.1:{user_port}/user", "--auth.cookie.access-token.name=access_token"])
        start("api-gateway", gateway_port, [f"--gateway.security.signer-key={signer_key}",
            "--gateway.security.csrf-enabled=false", "--gateway.security.blacklist-timeout=1s",
            "--gateway.security.access-cookie.name=access_token", "--gateway.security.refresh-cookie.name=refresh_token",
            "--spring.cloud.gateway.server.webflux.routes[0].id=auth-e2e-user",
            "--spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/user/api/**",
            f"--spring.cloud.gateway.server.webflux.routes[0].uri=http://127.0.0.1:{user_port}",
            "--spring.cloud.gateway.server.webflux.routes[1].id=auth-e2e-channel",
            "--spring.cloud.gateway.server.webflux.routes[1].predicates[0]=Path=/channel/api/**",
            f"--spring.cloud.gateway.server.webflux.routes[1].uri=http://127.0.0.1:{channel_port}"])
        eventually("GET", "/channel/api/categories", 200, authenticated=False)
        sql("auth_user", f"""CREATE EXTENSION IF NOT EXISTS pgcrypto;
INSERT INTO roles (id, name) VALUES (gen_random_uuid(), 'USER') ON CONFLICT (name) DO NOTHING;
INSERT INTO permissions (id, name) VALUES (gen_random_uuid(), 'CHANNEL_UPDATE') ON CONFLICT (name) DO NOTHING;
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.name = 'STREAMER' AND p.name = 'CHANNEL_UPDATE'
ON CONFLICT DO NOTHING;
INSERT INTO users (id, username, email, password_hash, status, email_verified)
VALUES ('{user_id}', '{username}', 'auth-e2e@example.invalid', crypt('{login_password}', gen_salt('bf')), 'ACTIVE', true);
INSERT INTO user_roles (user_id, role_id) SELECT '{user_id}', id FROM roles WHERE name = 'USER';
INSERT INTO profiles (id, user_id, display_name) VALUES (gen_random_uuid(), '{user_id}', 'Auth E2E');
""")
        expect("POST", "/user/api/auth/login", 200, body={"username": username, "password": login_password})
        original_access = access_cookie()
        claims = json.loads(base64.urlsafe_b64decode(original_access.split('.')[1] + '==='))
        assert "scope" not in claims
        boundary = "AuthStateE2eBoundary"
        multipart = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"description\"\r\n\r\n"
                     f"Updated through gateway\r\n--{boundary}--\r\n").encode()
        update = {"body": multipart, "content_type": f"multipart/form-data; boundary={boundary}"}
        expect("PATCH", "/channel/api/channels/me", 403, **update)
        expect("POST", "/user/api/users/me/become-streamer", 200)
        assert access_cookie() == original_access
        expect("PATCH", "/channel/api/channels/me", 200, **update)
        print("PASS: become-streamer grants permission through gateway with the unchanged unscoped JWT", flush=True)
        enqueue(f"UPDATE users SET status = 'BANNED' WHERE id = '{user_id}';")
        eventually("GET", "/user/api/users/me", 403)
        expect("POST", "/user/api/auth/login", 403, body={"username": username, "password": login_password})
        assert access_cookie() == original_access
        enqueue(f"UPDATE users SET status = 'ACTIVE' WHERE id = '{user_id}';")
        eventually("GET", "/user/api/users/me", 200)
        print("PASS: durable worker publishes ban/unban and gateway blocks/restores the same JWT", flush=True)
        enqueue("DELETE FROM role_permissions WHERE role_id = (SELECT id FROM roles WHERE name = 'STREAMER');")
        eventually("PATCH", "/channel/api/channels/me", 403, **update)
        enqueue("INSERT INTO role_permissions (role_id, permission_id) SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.name = 'STREAMER' AND p.name = 'CHANNEL_UPDATE';")
        eventually("PATCH", "/channel/api/channels/me", 200, **update)
        assert access_cookie() == original_access
        print("PASS: permission removal and restoration take effect without token replacement", flush=True)
        command(["docker", "pause", redis_name])
        redis_paused = True
        _, headers = expect("GET", "/user/api/users/me", 503)
        assert not headers.get_all("Set-Cookie")
        command(["docker", "unpause", redis_name])
        redis_paused = False
        eventually("GET", "/user/api/users/me", 200)
        assert access_cookie() == original_access
        expect("POST", "/user/api/auth/refresh", 200)
        assert access_cookie() != original_access
        print("PASS: Redis timeout returns 503, retains cookies and recovers; refresh republishes auth state", flush=True)
        print(f"PASS: live PostgreSQL/Redis HTTP contract; logs: {logs}", flush=True)
    finally:
        if redis_paused:
            command(["docker", "unpause", redis_name], check=False)
        for application in reversed(applications):
            if application.poll() is None:
                os.killpg(application.pid, signal.SIGTERM)
                try:
                    application.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(application.pid, signal.SIGKILL)
                    application.wait(timeout=5)
        for name in reversed(containers):
            command(["docker", "stop", "-t", "5", name], check=False)
        for logfile in log_handles:
            logfile.close()
        print(f"Temporary applications and containers stopped; logs retained at {logs}", flush=True)


if __name__ == "__main__":
    main()

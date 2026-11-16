"""Deploy the complete stack with Docker Compose and Python 3 (standard library only)."""
import argparse
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def run(command, env, timeout):
    try:
        subprocess.run(command, cwd=ROOT, env=env, check=True, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        raise SystemExit("Command timed out. Check containers with: python scripts/deploy.py status") from error
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from error


def initialize(path):
    if path.exists():
        return
    template = (ROOT / ".env.deploy.example").read_text(encoding="utf-8")
    content = template.replace("replace-with-a-strong-database-password", secrets.token_urlsafe(30))
    content = content.replace("replace-with-a-strong-admin-password", secrets.token_urlsafe(24))
    # Exclusive creation prevents concurrent invocations from replacing credentials.
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        return
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
        stream.write(content)
    print(f"Created {path.name}; generated passwords are stored there.", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["up", "down", "status", "logs", "password"])
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env.deploy")
    parser.add_argument("--local-source", action="store_true", help="Build backend/ and frontend/ from this full local workspace")
    args = parser.parse_args()
    config = args.env_file.resolve()
    if args.action == "password":
        if not config.is_file():
            parser.error("Deployment is not initialized. Run the up command first.")
        for line in config.read_text(encoding="utf-8-sig").splitlines():
            if line.startswith("BOOTSTRAP_ADMIN_PASSWORD="):
                print(line.split("=", 1)[1])
                return
        parser.error("BOOTSTRAP_ADMIN_PASSWORD is missing from the environment file.")
    docker = shutil.which("docker")
    if not docker:
        parser.error("Install Docker Desktop (Linux containers) or Docker Engine with Compose v2 first.")
    env = os.environ.copy()
    if args.local_source:
        for component in ["backend", "frontend"]:
            directory = ROOT / component
            if not (directory / "Dockerfile").is_file():
                parser.error(f"Missing {component}/Dockerfile; omit --local-source to build from GitHub branches.")
            env[component.upper() + "_BUILD_CONTEXT"] = str(directory)
    run([docker, "compose", "version"], env, 30)
    if args.action == "up":
        run([docker, "info", "--format", "{{.ServerVersion}}"], env, 30)
        initialize(config)
    elif not config.is_file():
        parser.error("Deployment is not initialized. Run the up command first.")
    command = [docker, "compose", "--env-file", str(config), "-f", str(ROOT / "compose.deploy.yml")]
    if args.action == "up":
        run([*command, "config", "--quiet"], env, 30)
        run([*command, "up", "-d", "--build", "--wait", "--wait-timeout", "1800"], env, 3600)
        run([*command, "ps"], env, 30)
        print("Ready. Default URL: http://127.0.0.1:8088 (WEB_BIND / WEB_PORT can change it).")
        print("Login: admin. Read the initial password with: python scripts/deploy.py password")
        print("Configure your chat API key in Settings > Chat model after login.")
    else:
        arguments = {"down": ["down"], "status": ["ps", "-a"], "logs": ["logs", "--tail", "100"]}[args.action]
        run([*command, *arguments], env, 120)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit("Interrupted. Docker may still be working; inspect the deployment with the status command.")

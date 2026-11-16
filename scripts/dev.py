"""Portable local development runner. Does not change the system Java installation."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('action', choices=['backend-test','backend-build','backend-run','frontend-install','frontend-check','frontend-run'])
args = parser.parse_args()
env = os.environ.copy()
env['PYTHONIOENCODING'] = 'utf-8'
if args.action == 'backend-run':
    config=ROOT/'.env'
    if config.exists():
        for line in config.read_text(encoding='utf-8-sig').splitlines():
            line=line.strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            key,value=line.split('=',1)
            env.setdefault(key.strip(),value.strip().strip('"').strip("'"))
java = os.environ.get('KB_JAVA_HOME') or os.environ.get('JAVA_HOME', '')
local = Path.home()/'.jdks'/'oracle_open_jdk-21'
if local.exists() and 'KB_JAVA_HOME' not in env:
    java = str(local)
if java:
    env['JAVA_HOME'] = java
    env['PATH'] = str(Path(java)/'bin')+os.pathsep+env['PATH']

def run(command, cwd, timeout=600):
    print('Running:', ' '.join(command), flush=True)
    process = subprocess.Popen(command, cwd=cwd, env=env, start_new_session=(os.name != 'nt'))
    try:
        return process.wait(timeout=timeout)
    except (KeyboardInterrupt, subprocess.TimeoutExpired):
        if os.name == 'nt':
            subprocess.run(['taskkill','/PID',str(process.pid),'/T','/F'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=15)
        else:
            import signal
            os.killpg(process.pid,signal.SIGTERM)
        return 130

if args.action.startswith('backend-'):
    mvn = shutil.which('mvn.cmd' if os.name == 'nt' else 'mvn')
    if not mvn:
        raise SystemExit('Maven 3.9+ is required. Install Maven and add it to PATH.')
    command = {'backend-test':['test'], 'backend-build':['package','-DskipTests'], 'backend-run':['spring-boot:run']}[args.action]
    raise SystemExit(run([mvn,'-B','-ntp',*command],ROOT/'backend', None if args.action.endswith('run') else 600))
else:
    npm = shutil.which('npm.cmd' if os.name == 'nt' else 'npm')
    command = {'frontend-install':['install','--no-audit','--no-fund'], 'frontend-check':['run','build'], 'frontend-run':['run','dev','--','--host','127.0.0.1']}[args.action]
    raise SystemExit(run([npm,*command],ROOT/'frontend',None if args.action.endswith('run') else 600))

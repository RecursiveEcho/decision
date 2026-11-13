#!/usr/bin/env python3
"""Run the backend suite against a disposable loopback PostgreSQL cluster."""
import argparse
import os
from pathlib import Path
import socket
import subprocess
import tempfile

p=argparse.ArgumentParser()
p.add_argument('--postgres-bin', type=Path, required=True)
p.add_argument('--tests',default='')
a=p.parse_args()
root=Path(__file__).resolve().parent
with tempfile.TemporaryDirectory(prefix='decision-backend-test-') as tmp:
    directory=Path(tmp)
    data=directory/'pgdata'
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0)); port=sock.getsockname()[1]
    env=os.environ.copy()
    env['PGPASSWORD']='synthetic-test-only'
    subprocess.run([a.postgres_bin/'initdb','-D',data,'--username=decision_test','--pwfile=/dev/stdin','--auth=scram-sha-256','--encoding=UTF8','--locale=C'],input=b'synthetic-test-only\n',env=env,stdout=subprocess.DEVNULL,check=True)
    subprocess.run([a.postgres_bin/'pg_ctl','-D',data,'-l',directory/'postgres.log','-o',f'-h 127.0.0.1 -p {port} -c unix_socket_directories=','-w','start'],env=env,stdout=subprocess.DEVNULL,check=True)
    try:
        subprocess.run([a.postgres_bin/'createdb','-h','127.0.0.1','-p',str(port),'-U','decision_test','decision_backend_test'],env=env,check=True)
        env['TEST_DATABASE_URL']=f'jdbc:postgresql://127.0.0.1:{port}/decision_backend_test'
        command=['mvn','-q','test']+([f'-Dtest={a.tests}'] if a.tests else [])
        result=subprocess.run(command,cwd=root,env=env)
    finally:
        subprocess.run([a.postgres_bin/'pg_ctl','-D',data,'-m','fast','-w','stop'],env=env,stdout=subprocess.DEVNULL,check=True)
    raise SystemExit(result.returncode)

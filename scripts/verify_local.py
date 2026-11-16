"""Verify live local dependencies and semantic search without using a chat API key."""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.request

ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--username',default='admin')
parser.add_argument('--password-env',default='KB_VERIFY_PASSWORD')
args=parser.parse_args()
config={}
for line in (ROOT/'.env').read_text(encoding='utf-8-sig').splitlines():
    if line.strip() and not line.lstrip().startswith('#') and '=' in line:
        key,value=line.split('=',1);config[key.strip()]=value.strip()

def request(url,body=None,opener=None,headers=None):
    data=None if body is None else json.dumps(body,ensure_ascii=False).encode('utf-8')
    req=urllib.request.Request(url,data=data,headers={'Content-Type':'application/json',**(headers or {})})
    response=opener.open(req,timeout=180) if opener else urllib.request.urlopen(req,timeout=180)
    with response:
        return json.load(response)

embedding_url=config['EMBEDDING_BASE_URL'].rstrip('/')+'/v1/embeddings'
vectors=request(embedding_url,{'model':config['AI_EMBEDDING_MODEL'],'input':['外地出差住酒店能报销多少钱？','出差住宿费用上限每晚400元。','企鹅生活在南极。']})['data']
expected=int(config['EMBEDDING_DIMENSIONS'])
assert len(vectors)==3 and all(len(item['embedding'])==expected for item in vectors)
print('Local embedding: OK,',len(vectors),'vectors,',expected,'dimensions')

sql="SELECT extversion FROM pg_extension WHERE extname='vector'; SELECT format_type(atttypid,atttypmod) FROM pg_attribute WHERE attrelid='document_chunk'::regclass AND attname='embedding'; SELECT status,count(*) FROM document GROUP BY status; SELECT count(*) FROM document_chunk WHERE embedding IS NOT NULL;"
result=subprocess.run(['docker','compose','exec','-T','postgres','psql','-U','knowledge','-d','knowledge_base','-At','-c',sql],cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=20)
if result.returncode: raise SystemExit(result.stderr)
assert 'vector('+str(expected)+')' in result.stdout
print('PostgreSQL extension / dimensions / document status / indexed chunks:')
print(result.stdout.strip())

jar=http.cookiejar.CookieJar()
opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
base='http://127.0.0.1:8080/api'
token=request(base+'/auth/csrf',opener=opener)['token']
password=os.environ.get(args.password_env)
if not password: raise SystemExit('Set '+args.password_env+' for the existing administrator, then rerun. Passwords are never printed.')
request(base+'/auth/login',{'username':args.username,'password':password},opener,{'X-CSRF-TOKEN':token})
token=request(base+'/auth/csrf',opener=opener)['token']
state=request(base+'/system',opener=opener)
assert state['demo'] is False and state['embeddingModel']==config['AI_EMBEDDING_MODEL']
print('Application mode: live; chat configured:',state['chatConfigured'],'model:',state['model'])
bases=request(base+'/knowledge-bases',opener=opener)
assert bases,'No knowledge bases available for the verification user'
kb=next((item for item in bases if item['name']=='企业制度与员工指南'),bases[0])
cases=[('外地出差住酒店能报销多少钱？','住宿'),('想休年假应该走什么流程？','年假'),('星河项目代码提交合并前有哪些要求？','两名工程师')]
for question,expected in cases:
    sources=request(base+'/knowledge-bases/'+kb['id']+'/search',{'question':question},opener,{'X-CSRF-TOKEN':token})
    assert sources,'No sources returned for: '+question
    assert any(expected in source['excerpt'] for source in sources),'Expected source missing: '+question
    print(json.dumps({'question':question,'topDocument':sources[0]['documentName'],'topScore':sources[0]['score'],'results':len(sources)},ensure_ascii=False))
unrelated=request(base+'/knowledge-bases/'+kb['id']+'/search',{'question':'南极企鹅为什么不会飞？'},opener,{'X-CSRF-TOKEN':token})
assert unrelated==[], 'Unrelated query should not return these company documents'
print('Unrelated query: correctly returned no results')
print('Real semantic retrieval: PASS')

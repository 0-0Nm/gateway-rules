#!/usr/bin/env python3
"""Publish an audited file manifest as ONE commit. Requires a fresh environment token.
No secrets in args/logs; no working-tree reset; protected branch updates are non-force.
"""
import argparse,base64,hashlib,json,os,re,urllib.request,urllib.error
from pathlib import Path
parser=argparse.ArgumentParser()
parser.add_argument('--manifest',required=True)
parser.add_argument('--message',default='fix(security): phase 1 control and data plane protection')
parser.add_argument('--publish',action='store_true')
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
m=json.loads(Path(args.manifest).read_text())
repo=m['repository'];branch=m['branch'];expected=m['baseCommit']
assert re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+',repo)
assert re.fullmatch(r'[A-Za-z0-9_.-]+',branch)
files=[]
secret=re.compile(rb'(?:github_pat_|ghp_|sk-)[A-Za-z0-9_-]{16,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----')
for item in m['files']:
 p=root/item['path'];assert p.resolve().is_relative_to(root.resolve())
 raw=p.read_bytes();assert hashlib.sha256(raw).hexdigest()==item['sha256'],item['path']+' changed'
 assert not secret.search(raw),item['path']+' contains a credential-like literal'
 files.append((item['path'],raw))
print(json.dumps({'files':len(files),'bytes':sum(len(b) for _,b in files),'expectedParent':expected,'publish':args.publish}))
if not args.publish:raise SystemExit(0)
token=os.environ.get('GITHUB_TOKEN') or os.environ.get('GH_TOKEN')
if not token:raise SystemExit('BLOCKED: provide a rotated GITHUB_TOKEN or GH_TOKEN through the private runtime, not chat or source')
def api(path,method='GET',payload=None):
 req=urllib.request.Request('https://api.github.com/repos/'+repo+'/'+path,
   data=json.dumps(payload).encode() if payload is not None else None,method=method,
   headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json','User-Agent':'gateway-rules-atomic-publisher','Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=40) as response:return json.load(response)
 except urllib.error.HTTPError as e:raise RuntimeError(f'GitHub {method} {path}: HTTP {e.code}; no retry of ambiguous writes') from None
head=api('git/ref/heads/'+branch)['object']['sha']
if head!=expected:raise SystemExit('BLOCKED: remote branch moved; rebase/review rather than overwrite')
baseTree=api('git/commits/'+head)['tree']['sha']
entries=[]
for name,raw in files:
 blob=api('git/blobs','POST',{'content':base64.b64encode(raw).decode(),'encoding':'base64'})
 entries.append({'path':name,'mode':'100644','type':'blob','sha':blob['sha']})
tree=api('git/trees','POST',{'base_tree':baseTree,'tree':entries})
commit=api('git/commits','POST',{'message':args.message,'tree':tree['sha'],'parents':[head]})
# Divergent concurrent commits cannot be overwritten: force=false.
if api('git/ref/heads/'+branch)['object']['sha']!=head:raise SystemExit('BLOCKED: branch changed before publish; commit object left unattached')
api('git/refs/heads/'+branch,'PATCH',{'sha':commit['sha'],'force':False})
back=api('git/ref/heads/'+branch)['object']['sha']
if back!=commit['sha']:raise SystemExit('NEEDS_REVIEW: remote ref changed after publish; inspect actions rather than retry')
print(json.dumps({'published':True,'commit':back,'files':len(entries),'workflow':'push-triggered build-apk','branch':branch}))

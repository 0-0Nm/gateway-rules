#!/usr/bin/env python3
"""Project-specific four-part build identity. Does not change the global toolbox version state."""
import argparse,datetime,hashlib,json,os,re,subprocess,urllib.request,zipfile,tempfile
from pathlib import Path
MAX_ANDROID_CODE=2100000000
class IdentityError(Exception):pass
def integer(value,name,minimum=0,maximum=MAX_ANDROID_CODE):
    if isinstance(value,bool) or not isinstance(value,int) or not minimum<=value<=maximum:raise IdentityError('INVALID_'+name.upper())
    return value
def policy_read(path):
    data=json.loads(Path(path).read_text());validate_policy(data);return data
def validate_policy(p):
    if p.get('schema')!=1 or p.get('project')!='gateway-rules':raise IdentityError('POLICY_SCHEMA_INVALID')
    for key in ['major','minor','validationRound']:integer(p.get(key),key,maximum=100000)
    integer(p.get('versionCodeOffset'),'code_offset',minimum=2,maximum=MAX_ANDROID_CODE-1)
    if p['versionCodeOffset']!=1000000:raise IdentityError('CODE_EPOCH_MIGRATION_REVIEW_REQUIRED')
    integer(p.get('buildSequenceOrigin'),'build_origin',maximum=MAX_ANDROID_CODE-1)
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+',p.get('repository','')):raise IdentityError('REPOSITORY_INVALID')
    if p.get('workflowPath')!='.github/workflows/build.yml' or p.get('branch')!='main':raise IdentityError('COUNTER_LINEAGE_REVIEW_REQUIRED')
def sequence(value,label):
    if not isinstance(value,str) or not re.fullmatch(r'[1-9][0-9]*',value):raise IdentityError('INVALID_'+label)
    return int(value)
def allocate(p,env,checkout_sha,current_main_sha):
    validate_policy(p)
    if env.get('GITHUB_ACTIONS')!='true':raise IdentityError('OFFICIAL_BUILD_REQUIRES_GITHUB_ACTIONS')
    if env.get('GITHUB_REPOSITORY')!=p['repository']:raise IdentityError('WRONG_REPOSITORY')
    ref='refs/heads/'+p['branch']
    if env.get('GITHUB_REF')!=ref:raise IdentityError('OFFICIAL_BUILD_REQUIRES_MAIN')
    if env.get('GITHUB_WORKFLOW_REF')!=p['repository']+'/'+p['workflowPath']+'@'+ref:raise IdentityError('WORKFLOW_COUNTER_LINEAGE_MISMATCH')
    number=sequence(env.get('GITHUB_RUN_NUMBER'),'BUILD_SEQUENCE');run_id=sequence(env.get('GITHUB_RUN_ID'),'RUN_ID');attempt=sequence(env.get('GITHUB_RUN_ATTEMPT'),'RUN_ATTEMPT')
    if attempt!=1:raise IdentityError('REBUILD_REQUIRES_NEW_WORKFLOW_DISPATCH_NO_ID_REUSE')
    sha=env.get('GITHUB_SHA','')
    if not re.fullmatch('[0-9a-f]{40}',sha) or sha!=checkout_sha:raise IdentityError('CHECKOUT_COMMIT_MISMATCH')
    if sha!=current_main_sha:raise IdentityError('STALE_SOURCE_NOT_CURRENT_MAIN')
    code=p['versionCodeOffset']+number;integer(code,'version_code',minimum=2)
    display=number-p['buildSequenceOrigin']
    if display<=0:raise IdentityError('BUILD_SEQUENCE_ORIGIN_NOT_BEFORE_THIS_RUN')
    version='.'.join(str(x) for x in [p['major'],p['minor'],p['validationRound'],display])
    return {'schema':1,'project':p['project'],'versionName':version,'versionCode':code,'major':p['major'],'minor':p['minor'],'validationRound':p['validationRound'],'buildSequence':display,'ciSequence':number,'buildSequenceOrigin':p['buildSequenceOrigin'],'sourceCommit':sha,'workflowRunId':str(run_id),'workflowRunAttempt':attempt,'repository':p['repository'],'workflowPath':p['workflowPath'],'buildKind':'ci-debug','sourceCurrentAtAllocation':True}
def publish_new_json(path,data):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True);encoded=(json.dumps(data,sort_keys=True,indent=2)+'\n').encode()
    if path.exists():
        if path.read_bytes()!=encoded:raise IdentityError('IDENTITY_ALREADY_ALLOCATED_DIFFERENT_CONTENT')
        return
    fd,name=tempfile.mkstemp(prefix='.build-identity-',dir=path.parent)
    try:
        with os.fdopen(fd,'wb') as f:f.write(encoded);f.flush();os.fsync(f.fileno())
        if path.exists():raise IdentityError('IDENTITY_CONCURRENT_WRITE_DETECTED')
        os.replace(name,path)
        if path.read_bytes()!=encoded:raise IdentityError('IDENTITY_READBACK_MISMATCH')
    finally:
        if os.path.exists(name):os.unlink(name)
def current_main(p):
    url='https://api.github.com/repos/'+p['repository']+'/git/ref/heads/'+p['branch']
    headers={'Accept':'application/vnd.github+json','User-Agent':'gateway-build-identity','Cache-Control':'no-cache'}
    token=os.environ.get('GITHUB_TOKEN','')
    if token:headers['Authorization']='Bearer '+token
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,*args):return None
    opener=urllib.request.build_opener(NoRedirect())
    # Official API only; ephemeral CI contents:read credential never follows a redirect.
    with opener.open(urllib.request.Request(url,headers=headers),timeout=20) as r:data=json.load(r)
    return data['object']['sha']
def verify_apk(apk,expected,aapt):
    apk=Path(apk)
    with zipfile.ZipFile(apk) as z:
        info=z.getinfo('assets/build-identity.json')
        if info.file_size>16384:raise IdentityError('BUILD_IDENTITY_ASSET_TOO_LARGE')
        actual=json.loads(z.read(info))
    for key in ['versionName','versionCode','major','minor','validationRound','buildSequence','ciSequence','sourceCommit','workflowRunId','workflowRunAttempt','buildKind']:
        if actual.get(key)!=expected.get(key):raise IdentityError('APK_IDENTITY_ASSET_MISMATCH_'+key)
    r=subprocess.run([str(aapt),'dump','badging',str(apk)],capture_output=True,text=True,timeout=30)
    if r.returncode:raise IdentityError('APK_MANIFEST_READ_FAILED')
    line=next((l for l in r.stdout.splitlines() if l.startswith('package: ')),'')
    fields=dict(re.findall(r"([A-Za-z]+)='([^']*)'",line))
    if fields.get('name')!='com.aqi.ccb' or fields.get('versionName')!=expected['versionName'] or fields.get('versionCode')!=str(expected['versionCode']):raise IdentityError('APK_MANIFEST_IDENTITY_MISMATCH')
    return {'ok':True,'versionName':expected['versionName'],'versionCode':expected['versionCode'],'sourceCommit':expected['sourceCommit'],'workflowRunId':expected['workflowRunId'],'buildSequence':expected['buildSequence'],'ciSequence':expected['ciSequence'],'assetAndManifestMatched':True,'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'apkBytes':apk.stat().st_size,'scope':'build identity, not signature verification or runtime acceptance'}
def main():
    p=argparse.ArgumentParser();p.add_argument('action',choices=['allocate','verify-apk']);p.add_argument('--policy',default='version-policy.json');p.add_argument('--output',required=True);p.add_argument('--identity');p.add_argument('--apk');p.add_argument('--aapt');args=p.parse_args()
    try:
        if args.action=='allocate':
            policy=policy_read(args.policy);checkout=subprocess.run(['git','rev-parse','HEAD'],check=True,capture_output=True,text=True).stdout.strip()
            result=allocate(policy,dict(os.environ),checkout,current_main(policy));publish_new_json(args.output,result);result=dict(result,ok=True,globalToolboxCounterModified=False)
        else:
            if not all([args.identity,args.apk,args.aapt]):raise IdentityError('VERIFY_ARGUMENTS_REQUIRED')
            result=verify_apk(args.apk,json.loads(Path(args.identity).read_text()),args.aapt);publish_new_json(args.output,result)
        print(json.dumps(result));return 0
    except IdentityError as e:print(json.dumps({'ok':False,'code':str(e),'privateValuesPrinted':False}));return 1
    except Exception as e:print(json.dumps({'ok':False,'code':'IDENTITY_OPERATION_FAILED','errorType':type(e).__name__}));return 1
if __name__=='__main__':raise SystemExit(main())

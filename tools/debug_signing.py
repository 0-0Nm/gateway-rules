#!/usr/bin/env python3
"""Fixed DEVELOPMENT/debug signing only. Private material must never enter source or output."""
import argparse,base64,hashlib,json,os,re,subprocess,tempfile
from pathlib import Path

EXPECTED=Path(__file__).with_name('debug-signing-cert.sha256')
ALIAS='androiddebugkey'
SECRET='GATEWAY_DEBUG_KEYSTORE_B64'
class SigningError(Exception):
    def __init__(self,code):self.code=code;super().__init__(code)
def expected_cert(path=EXPECTED):
    try:value=Path(path).read_text().strip().lower()
    except OSError:raise SigningError('CERTIFICATE_PIN_UNREADABLE') from None
    if not re.fullmatch('[0-9a-f]{64}',value):raise SigningError('CERTIFICATE_PIN_INVALID')
    return value
def key_certificate(path):
    env=dict(os.environ,ANDROID_FIXED_DEBUG_PASS='android')
    try:r=subprocess.run(['keytool','-exportcert','-alias',ALIAS,'-keystore',str(path),'-storepass:env','ANDROID_FIXED_DEBUG_PASS'],env=env,capture_output=True,timeout=20)
    except (OSError,subprocess.TimeoutExpired):raise SigningError('KEYTOOL_UNAVAILABLE_OR_TIMEOUT') from None
    if r.returncode or not r.stdout:raise SigningError('DEBUG_KEYSTORE_INVALID')
    return hashlib.sha256(r.stdout).hexdigest()
def decode_secret(value):
    if not value:raise SigningError('DEBUG_SECRET_MISSING_NO_RANDOM_FALLBACK')
    if len(value)>32768:raise SigningError('DEBUG_SECRET_TOO_LARGE')
    try:raw=base64.b64decode(value,validate=True)
    except (ValueError,base64.binascii.Error):raise SigningError('DEBUG_SECRET_NOT_BASE64') from None
    if not raw or len(raw)>16384:raise SigningError('DEBUG_KEYSTORE_SIZE_INVALID')
    return raw
def restore(value,target,pin):
    raw=decode_secret(value);target=Path(target)
    if target.is_symlink() or (target.exists() and not target.is_file()):raise SigningError('DEBUG_TARGET_NOT_REGULAR')
    target.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.debug-signing-',dir=target.parent) as td:
        staged=Path(td)/'debug.keystore'
        with staged.open('xb') as f:f.write(raw)
        staged.chmod(0o600)
        actual=key_certificate(staged)
        if actual!=pin:raise SigningError('DEBUG_KEYSTORE_CERTIFICATE_MISMATCH')
        same=target.is_file() and target.read_bytes()==raw
        if not same:os.replace(staged,target)
        target.chmod(0o600)
    return {'ok':True,'mode':'restore','certificateSha256':actual,'sameKeyAlreadyPresent':same,'privateKeyPrinted':False,'releaseSigningModified':False}
def file_sha(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()
def verify(apk,jar,pin):
    apk=Path(apk)
    if not apk.is_file():raise SigningError('DEBUG_APK_MISSING')
    try:r=subprocess.run(['java','-jar',str(jar),'verify','--verbose','--print-certs',str(apk)],capture_output=True,text=True,timeout=40)
    except (OSError,subprocess.TimeoutExpired):raise SigningError('APKSIGNER_UNAVAILABLE_OR_TIMEOUT') from None
    if r.returncode:raise SigningError('APK_SIGNATURE_INVALID')
    certs=re.findall(r'Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)',r.stdout)
    if len(certs)!=1 or certs[0].lower()!=pin:raise SigningError('APK_CERTIFICATE_PIN_MISMATCH')
    if not re.search(r'Verified using v2 scheme[^\n]*: true',r.stdout):raise SigningError('APK_V2_SIGNATURE_REQUIRED')
    return {'ok':True,'mode':'verify','apkPath':str(apk),'apkBytes':apk.stat().st_size,'apkSha256':file_sha(apk),'certificateSha256':certs[0].lower(),'v2Verified':True,'fixedDebugIdentityConfirmed':True,'releaseSigningModified':False}
def find_signer(sdk):
    root=Path(sdk)/'build-tools'
    def version(p):return tuple(int(x) if x.isdigit() else -1 for x in p.parent.parent.name.split('.'))
    jars=sorted(root.glob('*/lib/apksigner.jar'),key=version,reverse=True)
    if not jars:raise SigningError('APKSIGNER_JAR_MISSING')
    return jars[0]
def main():
    ap=argparse.ArgumentParser();ap.add_argument('mode',choices=['restore','verify']);ap.add_argument('--target',default=str(Path.home()/'.android/debug.keystore'));ap.add_argument('--apk');ap.add_argument('--sdk',default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT',''));ap.add_argument('--output');args=ap.parse_args()
    try:
        pin=expected_cert()
        if args.mode=='restore':result=restore(os.environ.get(SECRET,''),args.target,pin)
        else:
            if not args.apk:raise SigningError('APK_ARGUMENT_REQUIRED')
            result=verify(args.apk,find_signer(args.sdk),pin)
        code=0
    except SigningError as e:result={'ok':False,'code':e.code,'privateMaterialPrinted':False};code=1
    except Exception:result={'ok':False,'code':'SIGNING_OPERATION_FAILED','privateMaterialPrinted':False};code=1
    if args.output:
        p=Path(args.output);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result));return code
if __name__=='__main__':raise SystemExit(main())

#!/usr/bin/env python3
"""Fixed DEVELOPMENT/debug signing only. Private material must never enter source or output."""
import argparse,base64,hashlib,json,os,re,subprocess,tempfile
from pathlib import Path

EXPECTED=Path(__file__).with_name('debug-signing-cert.sha256')
ALIAS='androiddebugkey'
SECRET='GATEWAY_DEBUG_KEYSTORE_B64'
STABLE_BUILD_TOOLS='36.0.0'
class SigningError(Exception):
    def __init__(self,code,evidence=None):
        self.code=code;self.evidence=evidence or {};super().__init__(code)
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
def parse_signature_output(text,pin):
    """Read actual public certificate DER, not SDK-specific human-readable signer labels.

    SDK36 prints 'Signer #1', SDK37 prints 'V2 Signer'. The public PEM certificate
    remains stable. Repeated identical certificates across schemes are acceptable
    only with ONE signer; multiple signers or distinct identities fail closed.
    """
    counts=re.findall(r'^Number of signers: ([0-9]+)\s*$',text,re.MULTILINE)
    if len(counts)!=1:raise SigningError('APK_SIGNER_COUNT_OUTPUT_INVALID')
    count=int(counts[0])
    if count!=1:raise SigningError('APK_SINGLE_SIGNER_REQUIRED',{'signerCount':count})
    v2=re.findall(r'^Verified using v2 scheme[^\r\n]*: (true|false)\s*$',text,re.MULTILINE)
    if v2!=['true']:raise SigningError('APK_V2_SIGNATURE_REQUIRED')
    markers=text.count('-----BEGIN CERTIFICATE-----')
    blocks=re.findall(r'-----BEGIN CERTIFICATE-----\s*([A-Za-z0-9+/=\s]+?)\s*-----END CERTIFICATE-----',text)
    if not markers:raise SigningError('APK_CERTIFICATE_OUTPUT_MISSING',{'signerCount':count})
    if len(blocks)!=markers or text.count('-----END CERTIFICATE-----')!=markers:
        raise SigningError('APK_CERTIFICATE_PEM_INVALID')
    hashes=[]
    try:
        for b in blocks:
            der=base64.b64decode(''.join(b.split()),validate=True)
            if not der:raise ValueError('empty DER')
            hashes.append(hashlib.sha256(der).hexdigest())
    except (ValueError,base64.binascii.Error):raise SigningError('APK_CERTIFICATE_PEM_INVALID') from None
    identities=sorted(set(hashes))
    if len(identities)!=1:raise SigningError('APK_CERTIFICATE_SET_UNSUPPORTED',{'actualCertificateSha256':identities})
    actual=identities[0]
    if actual!=pin:raise SigningError('APK_CERTIFICATE_PIN_MISMATCH',{'expectedCertificateSha256':pin,'actualCertificateSha256':actual})
    return {'certificateSha256':actual,'certificateEncoding':'X.509 DER decoded from verified PEM',
        'signerCount':count,'certificatePemBlocks':len(blocks),'v2Verified':True}
def verify(apk,jar,pin):
    apk=Path(apk)
    if not apk.is_file():raise SigningError('DEBUG_APK_MISSING')
    jar=Path(jar)
    if not jar.is_file():raise SigningError('APKSIGNER_JAR_MISSING')
    # Locale only controls the signer-count/scheme summary; identity is computed from DER bytes.
    env=dict(os.environ,LC_ALL='C',LANG='C')
    try:r=subprocess.run(['java','-jar',str(jar),'verify','--verbose','--print-certs','--print-certs-pem',str(apk)],env=env,capture_output=True,text=True,timeout=40)
    except (OSError,subprocess.TimeoutExpired):raise SigningError('APKSIGNER_UNAVAILABLE_OR_TIMEOUT') from None
    if r.returncode:raise SigningError('APK_SIGNATURE_INVALID',{'apksignerExitCode':r.returncode})
    identity=parse_signature_output(r.stdout,pin)
    return dict({'ok':True,'mode':'verify','apkPath':str(apk),'apkBytes':apk.stat().st_size,
        'apkSha256':file_sha(apk),'apksignerJarSha256':file_sha(jar),'apksignerPath':str(jar),
        'fixedDebugIdentityConfirmed':True,'releaseSigningModified':False},**identity)
def find_signer(sdk,version=STABLE_BUILD_TOOLS):
    # Never choose newest installed SDK silently: a runner update must not alter the parser/tool contract.
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+',version):raise SigningError('APKSIGNER_VERSION_INVALID')
    path=Path(sdk)/'build-tools'/version/'lib/apksigner.jar'
    if not path.is_file():raise SigningError('APKSIGNER_JAR_MISSING',{'requiredBuildToolsVersion':version})
    return path
def main():
    ap=argparse.ArgumentParser();ap.add_argument('mode',choices=['restore','verify']);ap.add_argument('--target',default=str(Path.home()/'.android/debug.keystore'));ap.add_argument('--apk');ap.add_argument('--sdk',default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT',''));ap.add_argument('--output');ap.add_argument('--build-tools-version',default=STABLE_BUILD_TOOLS);args=ap.parse_args()
    try:
        pin=expected_cert()
        if args.mode=='restore':result=restore(os.environ.get(SECRET,''),args.target,pin)
        else:
            if not args.apk:raise SigningError('APK_ARGUMENT_REQUIRED')
            result=verify(args.apk,find_signer(args.sdk,args.build_tools_version),pin)
            result['buildToolsVersion']=args.build_tools_version
        code=0
    except SigningError as e:result=dict({'ok':False,'code':e.code,'privateMaterialPrinted':False},**e.evidence);code=1
    except Exception:result={'ok':False,'code':'SIGNING_OPERATION_FAILED','privateMaterialPrinted':False};code=1
    if args.output:
        p=Path(args.output);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result));return code
if __name__=='__main__':raise SystemExit(main())

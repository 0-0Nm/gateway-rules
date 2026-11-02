#!/usr/bin/env python3
import base64,hashlib,importlib.util,os,subprocess,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('debug_signing',ROOT/'tools/debug_signing.py');d=importlib.util.module_from_spec(spec);spec.loader.exec_module(d)
class DebugSigningTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory();cls.root=Path(cls.temp.name);cls.key=cls.root/'test.jks'
        env=dict(os.environ,ANDROID_FIXED_DEBUG_PASS='android')
        r=subprocess.run(['keytool','-genkeypair','-keystore',str(cls.key),'-storetype','JKS','-alias','androiddebugkey','-storepass:env','ANDROID_FIXED_DEBUG_PASS','-keypass:env','ANDROID_FIXED_DEBUG_PASS','-keyalg','RSA','-keysize','2048','-validity','7','-dname','CN=Disposable Unit Test,O=Test,C=US','-noprompt'],capture_output=True,env=env,timeout=30)
        if r.returncode:raise RuntimeError('Cannot create disposable test keystore')
        cls.raw=cls.key.read_bytes();cls.encoded=base64.b64encode(cls.raw).decode();cls.pin=d.key_certificate(cls.key)
    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()
    def fresh(self):return Path(tempfile.mkdtemp(dir=self.root))/'debug.keystore'
    def test_missing_secret_fails_without_random_generation(self):
        p=self.fresh()
        with self.assertRaisesRegex(d.SigningError,'MISSING'):d.restore('',p,self.pin)
        self.assertFalse(p.exists())
    def test_invalid_base64_rejected(self):
        with self.assertRaisesRegex(d.SigningError,'NOT_BASE64'):d.decode_secret('###')
    def test_oversize_secret_rejected(self):
        with self.assertRaisesRegex(d.SigningError,'TOO_LARGE'):d.decode_secret('A'*32769)
    def test_empty_decoded_data_rejected(self):
        with self.assertRaises(d.SigningError):d.decode_secret('====')
    def test_invalid_keystore_rejected(self):
        with self.assertRaisesRegex(d.SigningError,'KEYSTORE_INVALID'):d.restore(base64.b64encode(b'not-a-store').decode(),self.fresh(),self.pin)
    def test_wrong_certificate_preserves_existing_file(self):
        p=self.fresh();p.write_bytes(b'previous')
        with self.assertRaisesRegex(d.SigningError,'CERTIFICATE_MISMATCH'):d.restore(self.encoded,p,'0'*64)
        self.assertEqual(p.read_bytes(),b'previous')
    def test_correct_key_restored_exactly(self):
        p=self.fresh();result=d.restore(self.encoded,p,self.pin)
        self.assertTrue(result['ok']);self.assertEqual(p.read_bytes(),self.raw);self.assertEqual(d.key_certificate(p),self.pin)
    def test_private_mode_0600(self):
        p=self.fresh();d.restore(self.encoded,p,self.pin);self.assertEqual(p.stat().st_mode&0o777,0o600)
    def test_restore_twice_same_identity(self):
        p=self.fresh();a=d.restore(self.encoded,p,self.pin);b=d.restore(self.encoded,p,self.pin)
        self.assertFalse(a['sameKeyAlreadyPresent']);self.assertTrue(b['sameKeyAlreadyPresent']);self.assertEqual(a['certificateSha256'],b['certificateSha256'])
    def test_symlink_target_rejected(self):
        p=self.fresh();target=p.parent/'other';target.write_bytes(b'untouched');p.symlink_to(target)
        with self.assertRaisesRegex(d.SigningError,'TARGET_NOT_REGULAR'):d.restore(self.encoded,p,self.pin)
        self.assertEqual(target.read_bytes(),b'untouched')
    def test_directory_target_rejected(self):
        p=self.fresh();p.mkdir()
        with self.assertRaisesRegex(d.SigningError,'TARGET_NOT_REGULAR'):d.restore(self.encoded,p,self.pin)
    def test_pin_format_enforced(self):
        p=self.root/'pin';p.write_text('not-a-fingerprint')
        with self.assertRaisesRegex(d.SigningError,'PIN_INVALID'):d.expected_cert(p)
    def test_pin_missing_rejected(self):
        with self.assertRaisesRegex(d.SigningError,'PIN_UNREADABLE'):d.expected_cert(self.root/'missing')
    def test_committed_pin_is_public_fingerprint(self):self.assertEqual(len(d.expected_cert()),64)
    def test_key_material_not_in_result(self):
        result=str(d.restore(self.encoded,self.fresh(),self.pin));self.assertNotIn(self.encoded,result);self.assertNotIn('PRIVATE KEY',result)
    def test_missing_apk_rejected(self):
        with self.assertRaisesRegex(d.SigningError,'APK_MISSING'):d.verify(self.root/'missing.apk','missing.jar',self.pin)
    def test_missing_sdk_signer_rejected(self):
        with self.assertRaisesRegex(d.SigningError,'JAR_MISSING'):d.find_signer(self.root/'missing-sdk')
    def test_release_config_untouched(self):
        txt=(ROOT/'app/build.gradle.kts').read_text();self.assertNotIn('signingConfigs.getByName("debug")',txt)
if __name__=='__main__':unittest.main(verbosity=2)

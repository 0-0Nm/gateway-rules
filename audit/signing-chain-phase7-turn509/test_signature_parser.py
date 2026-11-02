#!/usr/bin/env python3
"""Regression against captured genuine apksigner36/37 output; no signing material."""
import importlib.util,tempfile,unittest,re,hashlib,base64
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('debug_signing',ROOT/'tools/debug_signing.py');d=importlib.util.module_from_spec(spec);spec.loader.exec_module(d)
class CertificateParserTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.old=(HERE/'fixtures/apksigner-36-valid.txt').read_text()
        cls.new=(HERE/'fixtures/apksigner-37-valid.txt').read_text()
        cls.pin=d.expected_cert()
        cls.pem=re.search(r'-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----',cls.new).group(0)
    def expect(self,text,code,pin=None):
        with self.assertRaises(d.SigningError) as cm:d.parse_signature_output(text,pin or self.pin)
        self.assertEqual(cm.exception.code,code);return cm.exception
    def test_sdk36_real_output(self):self.assertEqual(d.parse_signature_output(self.old,self.pin)['certificateSha256'],self.pin)
    def test_sdk37_real_output(self):self.assertEqual(d.parse_signature_output(self.new,self.pin)['certificateSha256'],self.pin)
    def test_different_prose_same_der_same_identity(self):
        self.assertEqual(d.parse_signature_output(self.old,self.pin)['certificateSha256'],d.parse_signature_output(self.new,self.pin)['certificateSha256'])
    def test_old_parser_false_negative_reproduced(self):
        pattern=r'Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)'
        self.assertEqual(len(re.findall(pattern,self.old)),1);self.assertEqual(len(re.findall(pattern,self.new)),0)
    def test_hash_computed_from_der_not_prose(self):
        text=re.sub(r'(certificate SHA-256 digest: )[0-9a-f]+',r'\g<1>'+'a'*64,self.new)
        self.assertEqual(d.parse_signature_output(text,self.pin)['certificateSha256'],self.pin)
    def test_no_certificate_output_is_not_pin_mismatch(self):
        text=re.sub(r'-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----','',self.new)
        self.expect(text,'APK_CERTIFICATE_OUTPUT_MISSING')
    def test_public_key_digest_cannot_substitute_certificate(self):
        text=re.sub(r'-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----','',self.new)
        text=re.sub(r'^V2 Signer: certificate[^\n]+\n','',text,flags=re.M)
        self.expect(text,'APK_CERTIFICATE_OUTPUT_MISSING')
    def test_true_wrong_pin_rejected_with_actual_evidence(self):
        e=self.expect(self.new,'APK_CERTIFICATE_PIN_MISMATCH','0'*64)
        self.assertEqual(e.evidence['actualCertificateSha256'],self.pin)
        self.assertEqual(e.evidence['expectedCertificateSha256'],'0'*64)
    def test_multiple_signers_rejected_even_with_matching_certificate(self):self.expect(self.new.replace('Number of signers: 1','Number of signers: 2'),'APK_SINGLE_SIGNER_REQUIRED')
    def test_signer_count_missing_rejected(self):self.expect(self.new.replace('Number of signers: 1',''),'APK_SIGNER_COUNT_OUTPUT_INVALID')
    def test_duplicate_signer_counts_rejected(self):self.expect(self.new+'\nNumber of signers: 1\n','APK_SIGNER_COUNT_OUTPUT_INVALID')
    def test_repeated_identical_cert_across_schemes_is_same_identity(self):
        r=d.parse_signature_output(self.new+'\n'+self.pem,self.pin);self.assertEqual(r['certificatePemBlocks'],2);self.assertEqual(r['signerCount'],1)
    def test_distinct_extra_certificate_fails_closed(self):
        fake='-----BEGIN CERTIFICATE-----\n'+base64.b64encode(b'different public DER').decode()+'\n-----END CERTIFICATE-----'
        self.expect(self.new+'\n'+fake,'APK_CERTIFICATE_SET_UNSUPPORTED')
    def test_missing_pem_end_is_parse_error(self):self.expect(self.new.replace('-----END CERTIFICATE-----',''),'APK_CERTIFICATE_PEM_INVALID')
    def test_invalid_pem_is_parse_error(self):
        self.expect(self.new.replace(self.pem,'-----BEGIN CERTIFICATE-----\n!!!!\n-----END CERTIFICATE-----'),'APK_CERTIFICATE_PEM_INVALID')
    def test_v2_false_rejected(self):self.expect(self.new.replace('v2 scheme (APK Signature Scheme v2): true','v2 scheme (APK Signature Scheme v2): false'),'APK_V2_SIGNATURE_REQUIRED')
    def test_v2_missing_rejected(self):self.expect(re.sub(r'^Verified using v2 scheme[^\n]*\n','',self.new,flags=re.M),'APK_V2_SIGNATURE_REQUIRED')
    def test_crlf_output(self):self.assertEqual(d.parse_signature_output(self.new.replace('\n','\r\n'),self.pin)['certificateSha256'],self.pin)
    def test_newest_sdk_is_not_selected_implicitly(self):
        with tempfile.TemporaryDirectory() as tmp:
            for v in ['36.0.0','37.0.0']:
                p=Path(tmp)/'build-tools'/v/'lib/apksigner.jar';p.parent.mkdir(parents=True);p.touch()
            self.assertIn('/36.0.0/',str(d.find_signer(tmp)))
            self.assertIn('/37.0.0/',str(d.find_signer(tmp,'37.0.0')))
    def test_required_version_absence_not_fallback(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'build-tools/37.0.0/lib/apksigner.jar';p.parent.mkdir(parents=True);p.touch()
            with self.assertRaises(d.SigningError) as cm:d.find_signer(tmp)
            self.assertEqual(cm.exception.code,'APKSIGNER_JAR_MISSING')
    def test_version_path_traversal_rejected(self):
        with self.assertRaises(d.SigningError) as cm:d.find_signer('/sdk','../../other')
        self.assertEqual(cm.exception.code,'APKSIGNER_VERSION_INVALID')
    def test_ci_declares_exact_verifier_version(self):self.assertIn('--build-tools-version 36.0.0',(ROOT/'.github/workflows/build.yml').read_text())
if __name__=='__main__':unittest.main(verbosity=2)

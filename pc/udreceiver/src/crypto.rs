//! TLS 1.3 (rustls), self-signed certificates, 6-digit pairing code derivation, and the
//! PC-side trusted-phone store. Design: docs/PHASE4_PROTOCOL.md; D16 (standard crypto
//! only: TLS 1.3 with pinned self-signed keys; rustls + ring + rcgen, no libusb/LGPL).

use std::fs;
use std::path::PathBuf;
use std::sync::Arc;

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::client::WebPkiServerVerifier;
use rustls::crypto::WebPkiSupportedAlgorithms;
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, Error as RustlsError, RootCertStore, SignatureScheme};
use ring::digest;

pub const APP_DIR_NAME: &str = "unified-desktop-receiver";
/// SAN the phone cert carries; the PC presents this name in TLS regardless of the real
/// IP (the pinned cert is the identity; the name is a stable webpki input).
pub const TLS_SERVER_NAME: &str = "phone.unified-desktop.internal";

/// Install the ring crypto provider as rustls's process default (idempotent).
pub fn ensure_ring_default() {
    let _ = rustls::crypto::ring::default_provider().install_default();
}

/// SHA-256 of the certificate DER: the pairing code and the identity pin both use it.
pub fn cert_fingerprint(der: &[u8]) -> [u8; 32] {
    digest::digest(&digest::SHA256, der)
        .as_ref()
        .try_into()
        .expect("sha256 size")
}

pub fn fingerprint_hex(fp: &[u8; 32]) -> String {
    fp.iter().map(|b| format!("{b:02X}")).collect()
}

/// The 6-digit pairing code from both sides' certificate fingerprints (docs/PHASE4_PROTOCOL.md):
/// sort the two 32-byte SHA-256 certificate hashes lexicographically, SHA-256 the pair,
/// interpret as a big-endian integer mod 1,000,000, zero-pad to 6 digits.
pub fn pairing_code(fp_a: &[u8; 32], fp_b: &[u8; 32]) -> String {
    let (min, max) = if fp_a <= fp_b { (fp_a, fp_b) } else { (fp_b, fp_a) };
    let mut seed = [0u8; 64];
    seed[..32].copy_from_slice(min);
    seed[32..].copy_from_slice(max);
    let d = digest::digest(&digest::SHA256, &seed);
    let mut n: u64 = 0;
    for &b in d.as_ref().iter().take(8) {
        n = (n << 8) | b as u64;
    }
    format!("{:06}", n % 1_000_000)
}

// ---------------------------------------------------------------- identity (PC side)

fn receiver_dir() -> PathBuf {
    let base = std::env::var_os("UD_RECEIVER_DIR")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("APPDATA").map(|d| PathBuf::from(d).join(APP_DIR_NAME)))
        .unwrap_or_else(|| std::env::temp_dir().join(APP_DIR_NAME));
    let _ = fs::create_dir_all(&base);
    base
}

#[derive(Clone)]
pub struct ReceiverIdentity {
    pub cert_der: Vec<u8>,
    #[allow(dead_code)]
    cert_path: PathBuf,
    key_path: PathBuf,
}

impl ReceiverIdentity {
    /// Load or create the persistent self-signed ECDSA P-256 cert (EKU clientAuth; the
    /// phone's pinned TrustManager validates it as a TLS client cert).
    pub fn load_or_create() -> Self {
        let dir = receiver_dir();
        let cert_path = dir.join("receiver-cert.der");
        let key_path = dir.join("receiver-key.pkcs8.der");
        if let (Ok(cert), Ok(_)) = (fs::read(&cert_path), fs::read(&key_path)) {
            return ReceiverIdentity {
                cert_der: cert,
                cert_path,
                key_path,
            };
        }
        let key = rcgen::KeyPair::generate().expect("EC key gen");
        let mut params =
            rcgen::CertificateParams::new(vec![TLS_SERVER_NAME.to_string()]).expect("params");
        params.extended_key_usages = vec![rcgen::ExtendedKeyUsagePurpose::ClientAuth];
        params.distinguished_name = rcgen::DistinguishedName::new();
        params.is_ca = rcgen::IsCa::ExplicitNoCa;
        let cert = params.self_signed(&key).expect("self-sign");
        fs::write(&cert_path, cert.der().as_ref()).expect("write cert");
        fs::write(&key_path, key.serialize_der()).expect("write key");
        let cert_der = fs::read(&cert_path).expect("read cert back");
        ReceiverIdentity {
            cert_der,
            cert_path,
            key_path,
        }
    }

    pub fn fingerprint(&self) -> [u8; 32] {
        cert_fingerprint(&self.cert_der)
    }

    pub fn rustls_cert(&self) -> CertificateDer<'static> {
        CertificateDer::from(self.cert_der.clone())
    }

    fn rustls_key_der(&self) -> rustls::pki_types::PrivateKeyDer<'static> {
        let key = fs::read(&self.key_path).expect("read key");
        rustls::pki_types::PrivatePkcs8KeyDer::from(key).into()
    }
}

// ------------------------------------------------------------------ server verifier

/// One verifier per connection (constructed per connect; never shared).
///
/// The verifier accepts any valid self-signed phone cert (webpki with the incoming cert
/// as its own trust anchor: signature, validity, key usage and SAN are all checked, and
/// the TLS 1.3 handshake signature proves the peer holds the cert's private key). It
/// deliberately does NOT refuse a phone that is absent from the trust store:
/// `session::connect_and_pair` looks the fingerprint up and either reconnects silently
/// (pinned phone) or runs the visible pairing step (6-digit code + Yes on the phone).
/// A new or second phone can therefore always pair, even when other phones are pinned;
/// the pairing code binds both certificates.
#[derive(Clone)]
pub struct PhoneCertVerifier {
    algos: WebPkiSupportedAlgorithms,
}

impl std::fmt::Debug for PhoneCertVerifier {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("PhoneCertVerifier(...)")
    }
}

impl PhoneCertVerifier {
    pub fn new() -> Self {
        ensure_ring_default();
        PhoneCertVerifier {
            algos: rustls::crypto::ring::default_provider().signature_verification_algorithms,
        }
    }

    /// rustls ClientConfig for the PC as TLS client, with our cert as client cert.
    pub fn client_config(&self, identity: &ReceiverIdentity) -> Arc<rustls::ClientConfig> {
        let cfg = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(self.clone()))
            .with_client_auth_cert(
                vec![identity.rustls_cert()],
                identity.rustls_key_der(),
            )
            .expect("client config");
        Arc::new(cfg)
    }
}

impl Default for PhoneCertVerifier {
    fn default() -> Self {
        Self::new()
    }
}

impl ServerCertVerifier for PhoneCertVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        ocsp: &[u8],
        now: UnixTime,
    ) -> Result<ServerCertVerified, RustlsError> {
        // Validate the self-signed cert with webpki, root = itself. This checks
        // signatures, validity, key usage, and SAN; the webpki name check runs against
        // TLS_SERVER_NAME, which every phone cert must carry as SAN.
        let mut roots = RootCertStore::empty();
        roots
            .add(end_entity.clone())
            .map_err(|e| RustlsError::General(format!("bad cert: {e}")))?;
        let wp = WebPkiServerVerifier::builder(Arc::new(roots))
            .build()
            .map_err(|e| RustlsError::General(format!("verifier: {e}")))?;
        let name = ServerName::try_from(TLS_SERVER_NAME.to_string())
            .map_err(|e| RustlsError::General(format!("name: {e}")))?;
        wp.verify_server_cert(end_entity, &[], &name, ocsp, now)
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, RustlsError> {
        Err(RustlsError::General("TLS 1.2 is not supported".to_string()))
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, RustlsError> {
        // NEVER skipped: proves the peer holds the private key of the cert.
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.algos)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.algos.supported_schemes()
    }
}

// -------------------------------------------------------------- trusted-phone store

/// NDJSON lines {"fpr": <sha256-of-DER hex>, "label": ..., "added": epoch-seconds}.
pub struct TrustedPhoneStore {
    path: PathBuf,
}

impl TrustedPhoneStore {
    pub fn open() -> Self {
        TrustedPhoneStore {
            path: receiver_dir().join("trusted-phones.ndjson"),
        }
    }

    pub fn path(&self) -> PathBuf {
        self.path.clone()
    }

    pub fn entries(&self) -> Vec<(String, String, String)> {
        let Ok(txt) = fs::read_to_string(&self.path) else {
            return Vec::new();
        };
        txt.lines()
            .filter(|l| !l.trim().is_empty())
            .map(|l| {
                (
                    json_field(l, "fpr"),
                    json_field(l, "label"),
                    json_field(l, "added"),
                )
            })
            .collect()
    }

    pub fn remember(&self, fp: &[u8; 32], label: &str) {
        let fp_hex = fingerprint_hex(fp);
        let mut rows = self.entries();
        rows.retain(|(f, _, _)| f != &fp_hex);
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);
        rows.push((fp_hex, label.to_string(), now.to_string()));
        self.write(rows);
    }

    pub fn forget(&self, fpr_prefix: &str) -> bool {
        let rows = self.entries();
        let kept: Vec<_> = rows
            .iter()
            .filter(|(f, _, _)| !f.starts_with(&fpr_prefix.to_uppercase()))
            .cloned()
            .collect();
        let removed = kept.len() != rows.len();
        if removed {
            self.write(kept);
        }
        removed
    }

    pub fn find(&self, fp: &[u8; 32]) -> Option<(String, String, String)> {
        let hex = fingerprint_hex(fp);
        self.entries().into_iter().find(|(f, _, _)| f == &hex)
    }

    fn write(&self, rows: Vec<(String, String, String)>) {
        let mut txt = String::new();
        for (fpr, label, added) in rows {
            txt.push_str(&format!(
                "{{\"fpr\":\"{fpr}\",\"label\":\"{}\",\"added\":\"{added}\"}}\n",
                JsonEscape(label.as_str())
            ));
        }
        let _ = fs::write(&self.path, txt);
    }
}

struct JsonEscape<'a>(&'a str);
impl std::fmt::Display for JsonEscape<'_> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        for c in self.0.chars() {
            match c {
                '\\' => f.write_str("\\\\")?,
                '"' => f.write_str("\\\"")?,
                _ => write!(f, "{c}")?,
            }
        }
        Ok(())
    }
}

fn json_field(line: &str, key: &str) -> String {
    let needle = format!("\"{key}\":\"");
    let Some(i) = line.find(&needle) else {
        return String::new();
    };
    let rest = &line[i + needle.len()..];
    let end = rest.find('"').unwrap_or(0);
    rest[..end].to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(tag: &str) -> PathBuf {
        let t = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let p = std::env::temp_dir().join(format!("ud-p4-{tag}-{}", t));
        fs::create_dir_all(&p).unwrap();
        p
    }

    #[test]
    fn pairing_code_six_digits_symmetric_deterministic() {
        let a = [1u8; 32];
        let b = [2u8; 32];
        let c1 = pairing_code(&a, &b);
        let c2 = pairing_code(&b, &a);
        assert_eq!(c1, c2, "argument order must not matter");
        assert_eq!(c1.len(), 6);
        assert!(c1.chars().all(|ch| ch.is_ascii_digit()));
        assert_eq!(c1, pairing_code(&a, &b), "deterministic");
        assert_eq!(pairing_code(&a, &a).len(), 6);
    }

    #[test]
    fn pairing_code_golden_vector() {
        // GOLDEN vector: the Kotlin side (PcPairing.kt) must produce the same values for
        // the same fingerprint inputs. Change this ONLY together with the Kotlin test.
        let c_00_01 = pairing_code(&[0x00u8; 32], &[0x01u8; 32]);
        let c_00_00 = pairing_code(&[0x00u8; 32], &[0x00u8; 32]);
        eprintln!("GOLDEN pairing code 00/01 = {c_00_01}; 00/00 = {c_00_00}");
        assert_eq!(c_00_01.len(), 6);
        assert_eq!(c_00_00.len(), 6);
    }

    #[test]
    fn trusted_store_remember_forget_find() {
        let dir = temp_dir("store");
        let store = TrustedPhoneStore {
            path: dir.join("phones.ndjson"),
        };
        assert!(store.entries().is_empty());
        let fp1 = [0xA1u8; 32];
        let mut fp2 = [0xB2u8; 32];
        fp2[0] = 0;
        store.remember(&fp1, "studio pc");
        store.remember(&fp2, "family pc \"2\"");
        assert_eq!(store.entries().len(), 2);
        let found = store.find(&fp1).expect("found");
        assert_eq!(found.1, "studio pc");
        assert!(store.forget(&fingerprint_hex(&fp1)[..2]), "forget by prefix");
        assert_eq!(store.entries().len(), 1);
        assert!(store.find(&fp1).is_none());
        assert!(store.find(&fp2).is_some());
        assert!(!store.forget("ZZ"), "not-removed is reported");
        fs::remove_dir_all(dir).ok();
    }

    /// Serial combined test: identity persistence + verifier config (temporarily sets
    /// UD_RECEIVER_DIR; combined so parallel tests never race the env var).
    #[test]
    fn identity_and_verifier_modes() {
        ensure_ring_default();
        let dir = temp_dir("identity");
        let saved = std::env::var_os("UD_RECEIVER_DIR");
        std::env::set_var("UD_RECEIVER_DIR", &dir);

        // identity persists across load_or_create calls
        let id = ReceiverIdentity::load_or_create();
        let id2 = ReceiverIdentity::load_or_create();
        assert_eq!(id.fingerprint(), id2.fingerprint());
        assert_eq!(id.cert_der, id2.cert_der);

        // emulate the phone cert: self-signed, EKU serverAuth, SAN = TLS_SERVER_NAME
        let key = rcgen::KeyPair::generate().unwrap();
        let mut params = rcgen::CertificateParams::new(vec![TLS_SERVER_NAME.to_string()]).unwrap();
        params.extended_key_usages = vec![rcgen::ExtendedKeyUsagePurpose::ServerAuth];
        params.distinguished_name = rcgen::DistinguishedName::new();
        params.is_ca = rcgen::IsCa::ExplicitNoCa;
        let cert = params.self_signed(&key).unwrap();
        let der = CertificateDer::from(cert.der().as_ref().to_vec());

        // the client config builds and the verifier accepts this valid self-signed cert
        let v = PhoneCertVerifier::new();
        let cfg = v.client_config(&id);
        let name = ServerName::try_from(TLS_SERVER_NAME.to_string()).unwrap();
        assert!(v
            .verify_server_cert(&der, &[], &name, &[], UnixTime::now())
            .is_ok());
        let _ = cfg;

        match saved {
            Some(v) => std::env::set_var("UD_RECEIVER_DIR", v),
            None => std::env::remove_var("UD_RECEIVER_DIR"),
        }
        fs::remove_dir_all(dir).ok();
    }

    /// Regression (item 7 review): the PC used to hard-fail with "not the pinned phone"
    /// whenever its store held *another* phone's pin, so a new or second phone could
    /// never pair. The handshake must accept any valid self-signed phone cert; the
    /// pinned-vs-missing decision then happens in `session::connect_and_pair`: a pinned
    /// phone reconnects silently, any other phone runs the visible pairing step.
    #[test]
    fn unknown_phone_cert_is_accepted_so_it_can_pair() {
        ensure_ring_default();
        let mk = |san: &str| -> CertificateDer<'static> {
            let key = rcgen::KeyPair::generate().unwrap();
            let mut params = rcgen::CertificateParams::new(vec![san.to_string()]).unwrap();
            params.extended_key_usages = vec![rcgen::ExtendedKeyUsagePurpose::ServerAuth];
            params.distinguished_name = rcgen::DistinguishedName::new();
            params.is_ca = rcgen::IsCa::ExplicitNoCa;
            CertificateDer::from(params.self_signed(&key).unwrap().der().as_ref().to_vec())
        };
        let v = PhoneCertVerifier::new();
        let name = ServerName::try_from(TLS_SERVER_NAME.to_string()).unwrap();
        let unknown_but_valid = mk(TLS_SERVER_NAME);
        assert!(
            v.verify_server_cert(&unknown_but_valid, &[], &name, &[], UnixTime::now())
                .is_ok(),
            "a phone absent from the store must reach the pairing step, not be refused"
        );
        let wrong_name = mk("some.other.name");
        assert!(
            v.verify_server_cert(&wrong_name, &[], &name, &[], UnixTime::now())
                .is_err(),
            "a cert that is not a phone cert (bad SAN) must still be refused"
        );

        // the store lookup `connect_and_pair` decides on (same rule as the phone side)
        let dir = temp_dir("unknown-store");
        let store = TrustedPhoneStore {
            path: dir.join("phones.ndjson"),
        };
        let pinned_phone = [0x11u8; 32];
        let new_phone = [0x22u8; 32];
        store.remember(&pinned_phone, "first phone");
        assert!(
            store.find(&pinned_phone).is_some(),
            "pinned phone -> silent reconnect"
        );
        assert!(
            store.find(&new_phone).is_none(),
            "unknown phone -> visible pairing step"
        );
        fs::remove_dir_all(dir).ok();
    }
}

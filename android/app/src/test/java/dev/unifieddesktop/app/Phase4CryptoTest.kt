package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.cert.X509Certificate
import java.util.Calendar

/** Phase-4 crypto tests: the X.509 shape the PC's webpki pin verifies + the golden
 *  pairing-code parity with the Rust receiver (pc/udreceiver crypto.rs). */
class Phase4CryptoTest {
    private val P4 = Phase4Crypto

    @Test
    fun selfSignedCertParsesWithPlatformFields() {
        val id = P4.generatePhoneIdentity()
        val cert = id.cert
        // getBasicConstraints() returns -1 for non-CA, >=0 for CA with path length
        assertEquals(-1, cert.basicConstraints)
        // SAN carries the exact SAN name webpki will match
        val sans = cert.subjectAlternativeNames
        assertTrue(sans!!.any { it.contains(P4.SERVER_NAME) })
        // EKU contains serverAuth (2.5.29.37 oid for the leaf as checked by the PC)
        val ekuOids = cert.extendedKeyUsage
        assertTrue(ekuOids?.contains("1.3.6.1.5.5.7.3.1") == true)
        assertTrue(cert.keyUsage[0]) // digitalSignature
        // self-signed: issuer == subject
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        // signature actually validates against its own public key
        cert.verify(cert.publicKey)
        // validity spans today
        val now = System.currentTimeMillis()
        assertTrue(cert.notBefore.time < now && now < cert.notAfter.time)
        val days = (cert.notAfter.time - cert.notBefore.time) / 86_400_000L
        assertTrue("validity too short: $days", days > 3600)
    }

    @Test
    fun certIsAlsoClientAuthCapableForThePotentialClientRole() {
        val id = P4.generatePhoneIdentity()
        assertTrue(id.cert.extendedKeyUsage!!.contains("1.3.6.1.5.5.7.3.2"))
        // and it re-parses through the factory (DER shape is sound)
        val again = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(id.certDer().inputStream()) as X509Certificate
        assertEquals(id.cert.serialNumber, again.serialNumber)
    }

    @Test
    fun pairingCodeSixDigitsSymmetricDeterministic() {
        val c1 = P4.codeFromFingerprints(fp(0x11), fp(0x22))
        val c2 = P4.codeFromFingerprints(fp(0x22), fp(0x11))
        assertTrue("argument order must not matter: $c1 vs $c2", c1 == c2)
        assertEquals(6, c1.length)
        assertTrue(c1.all { it.isDigit() })
        assertEquals(c1, P4.codeFromFingerprints(fp(0x11), fp(0x22)))
    }

    /** GOLDEN parity with the Rust side (pc/udreceiver crypto.rs pairing_code,
     *  cargo test pairing_code_golden_vector -- --nocapture):
     *  fingerprint all-0x00 vs all-0x01 -> 969324; all-0x00 vs all-0x00 -> 664624. */
    @Test
    fun pairingCodeGoldenVectorParityWithRust() {
        assertEquals("969324", P4.codeFromFingerprints(ByteArray(32), ByteArray(32) { 0x01 }))
        assertEquals("664624", P4.codeFromFingerprints(ByteArray(32), ByteArray(32)))
    }

    @Test
    fun unsignedCompareMatchesRustLexicographic() {
        val a = byteArrayOf((-1).toByte(), 0)
        val b = byteArrayOf(1, 0)
        // unsigned: 0x01 < 0xFF, so b < a -> negative result
        assertTrue(P4.unsignedCompare(b, a) < 0)
        assertEquals(0, P4.unsignedCompare(a, a))
    }

    @Test
    fun trustedStoreRememberForgetFind() {
        val dir = File.createTempFile("ud-p4-store", "").apply { delete(); mkdirs() }
        val f = File(dir, "phones.ndjson")
        assertTrue(P4.trustRows(f).isEmpty())
        P4.trustRemember(f, "A1" + "AA".repeat(31), "studio pc")
        P4.trustRemember(f, "B2" + "BB".repeat(31), "family pc")
        assertEquals(2, P4.trustRows(f).size)
        assertTrue(P4.trustForget(f, "A1"))
        assertEquals(1, P4.trustRows(f).size)
        assertEquals("family pc", P4.trustRows(f)[0].second)
        assertFalse("unknown prefix", P4.trustForget(f, "ZZ"))
        dir.deleteRecursively()
    }

    private fun fp(fill: Int): ByteArray = ByteArray(32) { fill.toByte() }
}

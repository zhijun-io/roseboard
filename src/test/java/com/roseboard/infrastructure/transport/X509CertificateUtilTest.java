package com.roseboard.infrastructure.transport;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class X509CertificateUtilTest {

    @Test
    void extractsDeviceNameFromCommonNameByRegex() throws Exception {
        String chain = Files.readString(Path.of("src/test/resources/provision/x509ChainProvisionTest.pem"));
        List<String> certificates = X509CertificateUtil.splitCertificateChain(chain);
        String commonName = X509CertificateUtil.parseCommonName(X509CertificateUtil.readCertificate(certificates.getFirst()));

        assertEquals("deviceCertificate", X509CertificateUtil.extractDeviceName(commonName, "([^@]+)"));
        assertEquals("DeviceA", X509CertificateUtil.extractDeviceName("DeviceA.company.com", "(.*)\\.company.com"));
    }

    @Test
    void sha3HashIsStableForTrimmedCertificate() throws Exception {
        String chain = Files.readString(Path.of("src/test/resources/provision/x509ChainProvisionTest.pem"));
        List<String> certificates = X509CertificateUtil.splitCertificateChain(chain);

        String hash = X509CertificateUtil.sha3HashHex(certificates.get(1));
        assertNotNull(hash);
        assertEquals(hash, X509CertificateUtil.sha3HashHex(certificates.get(1).replace("\n", "\r\n")));
    }
}

package com.roseboard.infrastructure.transport.mqtt;

import com.roseboard.infrastructure.transport.X509CertificateUtil;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

final class MqttTestSslSupport {
    private static final String PASSWORD = "password";

    private MqttTestSslSupport() {
    }

    record Material(Path serverKeyStore, Path clientKeyStore, String clientCertificatePem) {
    }

    static Material create(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path serverKeyStore = dir.resolve("server.p12");
        Path clientKeyStore = dir.resolve("client.p12");
        runKeytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", serverKeyStore.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD,
                "-dname", "CN=localhost", "-validity", "365",
                "-ext", "SAN=ip:127.0.0.1,dns:localhost");
        runKeytool("-genkeypair", "-alias", "client", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", clientKeyStore.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD,
                "-dname", "CN=mqtt-x509-device", "-validity", "365");
        String clientCertificatePem = exportClientCertificatePem(clientKeyStore);
        return new Material(serverKeyStore, clientKeyStore, clientCertificatePem);
    }

    private static String exportClientCertificatePem(Path clientKeyStore) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(clientKeyStore)) {
            keyStore.load(input, PASSWORD.toCharArray());
        }
        X509Certificate certificate = (X509Certificate) keyStore.getCertificate("client");
        return X509CertificateUtil.toPem(certificate);
    }

    private static void runKeytool(String... command) throws Exception {
        Process process = new ProcessBuilder(listWithKeytool(command))
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("keytool timed out: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + new String(process.getInputStream().readAllBytes()));
        }
    }

    private static String[] listWithKeytool(String... command) {
        String[] full = new String[command.length + 1];
        full[0] = "keytool";
        System.arraycopy(command, 0, full, 1, command.length);
        return full;
    }
}

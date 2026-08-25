package com.roseboard.infrastructure.transport;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class X509CertificateUtil {
    private static final Pattern CERTIFICATE_BLOCK = Pattern.compile(
            "-----BEGIN CERTIFICATE-----\\s*.*?\\s*-----END CERTIFICATE-----",
            Pattern.DOTALL);

    private X509CertificateUtil() {
    }

    public static String certTrimNewLines(String input) {
        return input.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("\n", "")
                .replace("\r", "")
                .replace("-----END CERTIFICATE-----", "");
    }

    public static String sha3HashHex(String data) {
        String trimmed = certTrimNewLines(data);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA3-256");
            return HexFormat.of().formatHex(digest.digest(trimmed.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA3-256 unavailable", exception);
        }
    }

    public static String sha3HashHex(X509Certificate certificate) {
        return sha3HashHex(toPem(certificate));
    }

    public static String toPem(X509Certificate certificate) {
        try {
            String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                    .encodeToString(certificate.getEncoded());
            return "-----BEGIN CERTIFICATE-----\n" + encoded + "\n-----END CERTIFICATE-----";
        } catch (CertificateEncodingException exception) {
            throw new IllegalArgumentException("Unable to encode certificate", exception);
        }
    }

    public static List<String> splitCertificateChain(String chain) {
        List<String> certificates = new ArrayList<>();
        Matcher matcher = CERTIFICATE_BLOCK.matcher(chain);
        while (matcher.find()) {
            certificates.add(matcher.group());
        }
        return certificates;
    }

    public static X509Certificate readCertificate(String pem) {
        if (pem == null || pem.isBlank()) {
            return null;
        }
        try {
            String normalized = pem.replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(normalized);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            try (ByteArrayInputStream input = new ByteArrayInputStream(decoded)) {
                return (X509Certificate) factory.generateCertificate(input);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String parseCommonName(X509Certificate certificate) {
        if (certificate == null) {
            return null;
        }
        String dn = certificate.getSubjectX500Principal().getName();
        for (String part : dn.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CN=")) {
                return trimmed.substring(3);
            }
        }
        return null;
    }

    public static String extractDeviceName(String commonName, String regex) {
        if (commonName == null || regex == null || regex.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile(regex).matcher(commonName);
        if (matcher.find() && matcher.groupCount() >= 1) {
            return matcher.group(1);
        }
        return null;
    }
}

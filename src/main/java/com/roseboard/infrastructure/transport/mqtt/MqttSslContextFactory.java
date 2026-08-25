package com.roseboard.infrastructure.transport.mqtt;

import io.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

@Component
@ConditionalOnProperty(prefix = "roseboard.transport.mqtt", name = "enabled", havingValue = "true")
final class MqttSslContextFactory {
    private static final Logger log = LoggerFactory.getLogger(MqttSslContextFactory.class);

    private final MqttTransportProperties.Ssl ssl;

    MqttSslContextFactory(MqttTransportProperties properties) {
        this.ssl = properties.getSsl();
    }

    boolean isConfigured() {
        return ssl.getKeyStore() != null && !ssl.getKeyStore().isBlank();
    }

    SslHandler newHandler() {
        SSLContext context = createContext();
        SSLEngine engine = context.createSSLEngine();
        engine.setUseClientMode(false);
        engine.setWantClientAuth(true);
        return new SslHandler(engine);
    }

    private SSLContext createContext() {
        if (!isConfigured()) {
            throw new IllegalStateException("MQTT SSL keystore is not configured");
        }
        try {
            KeyStore keyStore = KeyStore.getInstance(ssl.getKeyStoreType());
            Path keyStorePath = Path.of(ssl.getKeyStore());
            try (InputStream input = Files.newInputStream(keyStorePath)) {
                keyStore.load(input, password(ssl.getKeyStorePassword()));
            }
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, ssl.keyPasswordChars());
            SSLContext context = SSLContext.getInstance(ssl.getProtocol());
            context.init(keyManagerFactory.getKeyManagers(), permissiveTrustManagers(), null);
            return context;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to initialize MQTT SSL context from " + ssl.getKeyStore(), exception);
        }
    }

    private static char[] password(String value) {
        return value == null ? new char[0] : value.toCharArray();
    }

    private static TrustManager[] permissiveTrustManagers() {
        return new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };
    }
}

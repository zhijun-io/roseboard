package com.roseboard.infrastructure.transport.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "roseboard.transport.mqtt")
public class MqttTransportProperties {
    private boolean enabled;
    private String bindAddress = "0.0.0.0";
    private int port = 1883;
    private long idleTimeoutMs = 600_000L;
    private int maximumPacketSize = 268_435_455;
    private int serverReceiveMaximum = 65_535;
    private Ssl ssl = new Ssl();
    private WebSocket webSocket = new WebSocket();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    public void setIdleTimeoutMs(long idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
    }

    public int getMaximumPacketSize() {
        return maximumPacketSize;
    }

    public void setMaximumPacketSize(int maximumPacketSize) {
        this.maximumPacketSize = maximumPacketSize;
    }

    public int getServerReceiveMaximum() {
        return serverReceiveMaximum;
    }

    public void setServerReceiveMaximum(int serverReceiveMaximum) {
        this.serverReceiveMaximum = serverReceiveMaximum;
    }

    public Ssl getSsl() {
        return ssl;
    }

    public void setSsl(Ssl ssl) {
        this.ssl = ssl;
    }

    public WebSocket getWebSocket() {
        return webSocket;
    }

    public void setWebSocket(WebSocket webSocket) {
        this.webSocket = webSocket;
    }

    public static class Ssl {
        private boolean enabled;
        private String bindAddress = "0.0.0.0";
        private int port = 8883;
        private String protocol = "TLS";
        private String keyStore;
        private String keyStorePassword;
        private String keyStoreType = "PKCS12";
        private String keyPassword;
        private boolean skipValidityCheckForClientCert;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBindAddress() {
            return bindAddress;
        }

        public void setBindAddress(String bindAddress) {
            this.bindAddress = bindAddress;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public String getKeyStore() {
            return keyStore;
        }

        public void setKeyStore(String keyStore) {
            this.keyStore = keyStore;
        }

        public String getKeyStorePassword() {
            return keyStorePassword;
        }

        public void setKeyStorePassword(String keyStorePassword) {
            this.keyStorePassword = keyStorePassword;
        }

        public String getKeyStoreType() {
            return keyStoreType;
        }

        public void setKeyStoreType(String keyStoreType) {
            this.keyStoreType = keyStoreType;
        }

        public String getKeyPassword() {
            return keyPassword;
        }

        public void setKeyPassword(String keyPassword) {
            this.keyPassword = keyPassword;
        }

        public boolean isSkipValidityCheckForClientCert() {
            return skipValidityCheckForClientCert;
        }

        public void setSkipValidityCheckForClientCert(boolean skipValidityCheckForClientCert) {
            this.skipValidityCheckForClientCert = skipValidityCheckForClientCert;
        }

        public char[] keyPasswordChars() {
            if (keyPassword != null && !keyPassword.isBlank()) {
                return keyPassword.toCharArray();
            }
            return keyStorePassword == null ? new char[0] : keyStorePassword.toCharArray();
        }
    }

    public static class WebSocket {
        private boolean enabled;
        private String bindAddress = "0.0.0.0";
        private int port = 8083;
        private String path = "/mqtt";
        private boolean sslEnabled;
        private int sslPort = 8084;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBindAddress() {
            return bindAddress;
        }

        public void setBindAddress(String bindAddress) {
            this.bindAddress = bindAddress;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public boolean isSslEnabled() {
            return sslEnabled;
        }

        public void setSslEnabled(boolean sslEnabled) {
            this.sslEnabled = sslEnabled;
        }

        public int getSslPort() {
            return sslPort;
        }

        public void setSslPort(int sslPort) {
            this.sslPort = sslPort;
        }
    }
}

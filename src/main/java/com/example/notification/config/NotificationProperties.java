package com.example.notification.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class NotificationProperties {

    private Worker worker = new Worker();
    private Delivery delivery = new Delivery();
    private Security security = new Security();
    private Crypto crypto = new Crypto();

    public Worker getWorker() {
        return worker;
    }

    public void setWorker(Worker worker) {
        this.worker = worker;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public void setDelivery(Delivery delivery) {
        this.delivery = delivery;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public Crypto getCrypto() {
        return crypto;
    }

    public void setCrypto(Crypto crypto) {
        this.crypto = crypto;
    }

    public static class Worker {
        private boolean enabled = true;
        private Duration scanDelay = Duration.ofSeconds(5);
        private int batchSize = 20;
        private Duration lease = Duration.ofSeconds(30);
        private int maxConcurrency = 32;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getScanDelay() {
            return scanDelay;
        }

        public void setScanDelay(Duration scanDelay) {
            this.scanDelay = scanDelay;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getLease() {
            return lease;
        }

        public void setLease(Duration lease) {
            this.lease = lease;
        }

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }
    }

    public static class Delivery {
        private int defaultMaxAttempts = 5;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(10);
        private Duration initialBackoff = Duration.ofSeconds(60);
        private Duration maxBackoff = Duration.ofHours(1);
        private double jitterRatio = 0.2;

        public int getDefaultMaxAttempts() {
            return defaultMaxAttempts;
        }

        public void setDefaultMaxAttempts(int defaultMaxAttempts) {
            this.defaultMaxAttempts = defaultMaxAttempts;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }

        public double getJitterRatio() {
            return jitterRatio;
        }

        public void setJitterRatio(double jitterRatio) {
            this.jitterRatio = jitterRatio;
        }
    }

    public static class Security {
        private boolean allowLocalTargets;
        private Map<String, Source> sources = new HashMap<>();

        public boolean isAllowLocalTargets() {
            return allowLocalTargets;
        }

        public void setAllowLocalTargets(boolean allowLocalTargets) {
            this.allowLocalTargets = allowLocalTargets;
        }

        public Map<String, Source> getSources() {
            return sources;
        }

        public void setSources(Map<String, Source> sources) {
            this.sources = sources;
        }
    }

    public static class Source {
        private List<String> allowedDomains = new ArrayList<>();

        public List<String> getAllowedDomains() {
            return allowedDomains;
        }

        public void setAllowedDomains(List<String> allowedDomains) {
            this.allowedDomains = allowedDomains;
        }
    }

    public static class Crypto {
        private String secret = "change-me-32-byte-dev-secret-only";

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }
    }
}

package com.bedrock.mm.adapter;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Trading adapter configuration
 */
@Data
@Component
@ConfigurationProperties(prefix = "bedrock.adapter")
public class AdapterConfig {
    
    /**
     * Whether adapter is enabled
     */
    private boolean enabled = true;
    
    /**
     * Adapter type.
     * Supports single value (e.g., "simulation", "binance")
     * or comma-separated multiple values (e.g., "binance,bitget").
     */
    private String type = "simulation";
    
    /**
     * Environment (sandbox, testnet, live)
     */
    private String environment = "sandbox";
    
    /**
     * Connection settings
     */
    private ConnectionConfig connection = new ConnectionConfig();
    
    /**
     * Authentication settings
     */
    private AuthConfig auth = new AuthConfig();
    
    /**
     * Rate limiting settings
     */
    private RateLimitConfig rateLimit = new RateLimitConfig();
    
    /**
     * Retry settings
     */
    private RetryConfig retry = new RetryConfig();
    
    /**
     * WebSocket settings
     */
    private WebSocketConfig webSocket = new WebSocketConfig();
    
    /**
     * Order settings
     */
    private OrderConfig order = new OrderConfig();
    
    /**
     * Custom adapter-specific settings
     */
    private Map<String, Object> customSettings = new HashMap<>();

    // Explicit getters/setters for top-level fields to ensure compilation without Lombok
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public ConnectionConfig getConnection() { return connection; }
    public void setConnection(ConnectionConfig connection) { this.connection = connection; }

    public AuthConfig getAuth() { return auth; }
    public void setAuth(AuthConfig auth) { this.auth = auth; }

    public RateLimitConfig getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimitConfig rateLimit) { this.rateLimit = rateLimit; }

    public RetryConfig getRetry() { return retry; }
    public void setRetry(RetryConfig retry) { this.retry = retry; }

    public WebSocketConfig getWebSocket() { return webSocket; }
    public void setWebSocket(WebSocketConfig webSocket) { this.webSocket = webSocket; }

    public OrderConfig getOrder() { return order; }
    public void setOrder(OrderConfig order) { this.order = order; }

    public Map<String, Object> getCustomSettings() { return customSettings; }
    public void setCustomSettings(Map<String, Object> customSettings) { this.customSettings = customSettings; }
    
    @Data
    public static class ConnectionConfig {
        private String baseUrl;
        private String wsUrl;
        private long connectTimeoutMs = 10000;
        private long readTimeoutMs = 30000;
        private long writeTimeoutMs = 10000;
        private int maxConnections = 10;
        private boolean enableCompression = true;
        private boolean enableKeepAlive = true;
        private long keepAliveIntervalMs = 30000;
        // Explicit getters/setters to avoid Lombok dependency at compile time
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getWsUrl() { return wsUrl; }
        public void setWsUrl(String wsUrl) { this.wsUrl = wsUrl; }
        public long getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public long getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(long readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
        public long getWriteTimeoutMs() { return writeTimeoutMs; }
        public void setWriteTimeoutMs(long writeTimeoutMs) { this.writeTimeoutMs = writeTimeoutMs; }
        public int getMaxConnections() { return maxConnections; }
        public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }
        public boolean isEnableCompression() { return enableCompression; }
        public void setEnableCompression(boolean enableCompression) { this.enableCompression = enableCompression; }
        public boolean isEnableKeepAlive() { return enableKeepAlive; }
        public void setEnableKeepAlive(boolean enableKeepAlive) { this.enableKeepAlive = enableKeepAlive; }
        public long getKeepAliveIntervalMs() { return keepAliveIntervalMs; }
        public void setKeepAliveIntervalMs(long keepAliveIntervalMs) { this.keepAliveIntervalMs = keepAliveIntervalMs; }
    }
    
    @Data
    public static class AuthConfig {
        private String apiKey;
        private String secretKey;
        private String passphrase; // For some exchanges like OKX
        private boolean enableSignature = true;
        private String signatureMethod = "HmacSHA256";
        private boolean enableTimestamp = true;
        private long timestampOffsetMs = 0;

        /**
         * Per-exchange auth credentials. Keys should match adapter types
         * (e.g., "binance", "bitget").
         */
        private Map<String, AuthDetails> exchanges = new HashMap<>();

        /**
         * Get auth details for a specific exchange. Falls back to global auth
         * fields if no exchange-specific auth is configured.
         */
        public AuthDetails getAuthFor(String exchange) {
            if (exchange == null) return buildFallbackAuth();
            AuthDetails details = exchanges.get(exchange.toLowerCase());
            return details != null ? details : buildFallbackAuth();
        }

        private AuthDetails buildFallbackAuth() {
            AuthDetails d = new AuthDetails();
            d.setApiKey(apiKey);
            d.setSecretKey(secretKey);
            d.setPassphrase(passphrase);
            d.setEnableSignature(enableSignature);
            d.setSignatureMethod(signatureMethod);
            d.setEnableTimestamp(enableTimestamp);
            d.setTimestampOffsetMs(timestampOffsetMs);
            return d;
        }
    }

    @Data
    public static class AuthDetails {
        private String apiKey;
        private String secretKey;
        private String passphrase;
        private boolean enableSignature = true;
        private String signatureMethod = "HmacSHA256";
        private boolean enableTimestamp = true;
        private long timestampOffsetMs = 0;
        
        // Explicit getters/setters to ensure compatibility when Lombok annotation processing is unavailable
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public String getPassphrase() { return passphrase; }
        public void setPassphrase(String passphrase) { this.passphrase = passphrase; }
        public boolean isEnableSignature() { return enableSignature; }
        public void setEnableSignature(boolean enableSignature) { this.enableSignature = enableSignature; }
        public String getSignatureMethod() { return signatureMethod; }
        public void setSignatureMethod(String signatureMethod) { this.signatureMethod = signatureMethod; }
        public boolean isEnableTimestamp() { return enableTimestamp; }
        public void setEnableTimestamp(boolean enableTimestamp) { this.enableTimestamp = enableTimestamp; }
        public long getTimestampOffsetMs() { return timestampOffsetMs; }
        public void setTimestampOffsetMs(long timestampOffsetMs) { this.timestampOffsetMs = timestampOffsetMs; }
    }
    
    @Data
    public static class RateLimitConfig {
        private boolean enabled = true;
        private int requestsPerSecond = 10;
        private int requestsPerMinute = 1200;
        private int orderRequestsPerSecond = 5;
        private int orderRequestsPerMinute = 300;
        private long rateLimitWindowMs = 60000;
        private boolean enableBackoff = true;
        private long backoffBaseMs = 1000;
        private double backoffMultiplier = 2.0;
        private long maxBackoffMs = 30000;
        // Explicit getters/setters used by validation code
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getRequestsPerSecond() { return requestsPerSecond; }
        public void setRequestsPerSecond(int requestsPerSecond) { this.requestsPerSecond = requestsPerSecond; }
        public int getRequestsPerMinute() { return requestsPerMinute; }
        public void setRequestsPerMinute(int requestsPerMinute) { this.requestsPerMinute = requestsPerMinute; }
        public int getOrderRequestsPerSecond() { return orderRequestsPerSecond; }
        public void setOrderRequestsPerSecond(int orderRequestsPerSecond) { this.orderRequestsPerSecond = orderRequestsPerSecond; }
        public int getOrderRequestsPerMinute() { return orderRequestsPerMinute; }
        public void setOrderRequestsPerMinute(int orderRequestsPerMinute) { this.orderRequestsPerMinute = orderRequestsPerMinute; }
        public long getRateLimitWindowMs() { return rateLimitWindowMs; }
        public void setRateLimitWindowMs(long rateLimitWindowMs) { this.rateLimitWindowMs = rateLimitWindowMs; }
        public boolean isEnableBackoff() { return enableBackoff; }
        public void setEnableBackoff(boolean enableBackoff) { this.enableBackoff = enableBackoff; }
        public long getBackoffBaseMs() { return backoffBaseMs; }
        public void setBackoffBaseMs(long backoffBaseMs) { this.backoffBaseMs = backoffBaseMs; }
        public double getBackoffMultiplier() { return backoffMultiplier; }
        public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
        public long getMaxBackoffMs() { return maxBackoffMs; }
        public void setMaxBackoffMs(long maxBackoffMs) { this.maxBackoffMs = maxBackoffMs; }
    }
    
    @Data
    public static class RetryConfig {
        private boolean enabled = true;
        private int maxRetries = 3;
        private long initialDelayMs = 1000;
        private double multiplier = 2.0;
        private long maxDelayMs = 10000;
        private boolean retryOnTimeout = true;
        private boolean retryOnRateLimit = true;
        private boolean retryOnServerError = true;
        // Explicit getters/setters used by validation code
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public long getInitialDelayMs() { return initialDelayMs; }
        public void setInitialDelayMs(long initialDelayMs) { this.initialDelayMs = initialDelayMs; }
        public double getMultiplier() { return multiplier; }
        public void setMultiplier(double multiplier) { this.multiplier = multiplier; }
        public long getMaxDelayMs() { return maxDelayMs; }
        public void setMaxDelayMs(long maxDelayMs) { this.maxDelayMs = maxDelayMs; }
        public boolean isRetryOnTimeout() { return retryOnTimeout; }
        public void setRetryOnTimeout(boolean retryOnTimeout) { this.retryOnTimeout = retryOnTimeout; }
        public boolean isRetryOnRateLimit() { return retryOnRateLimit; }
        public void setRetryOnRateLimit(boolean retryOnRateLimit) { this.retryOnRateLimit = retryOnRateLimit; }
        public boolean isRetryOnServerError() { return retryOnServerError; }
        public void setRetryOnServerError(boolean retryOnServerError) { this.retryOnServerError = retryOnServerError; }
    }
    
    @Data
    public static class WebSocketConfig {
        private boolean enabled = true;
        private boolean autoReconnect = true;
        private long reconnectDelayMs = 5000;
        private int maxReconnectAttempts = 10;
        private long pingIntervalMs = 30000;
        private long pongTimeoutMs = 10000;
        private int maxFrameSize = 65536;
        private boolean enableCompression = true;
        // Explicit getters/setters used by adapters
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isAutoReconnect() { return autoReconnect; }
        public void setAutoReconnect(boolean autoReconnect) { this.autoReconnect = autoReconnect; }
        public long getReconnectDelayMs() { return reconnectDelayMs; }
        public void setReconnectDelayMs(long reconnectDelayMs) { this.reconnectDelayMs = reconnectDelayMs; }
        public int getMaxReconnectAttempts() { return maxReconnectAttempts; }
        public void setMaxReconnectAttempts(int maxReconnectAttempts) { this.maxReconnectAttempts = maxReconnectAttempts; }
    }
    
    @Data
    public static class OrderConfig {
        private boolean enableOrderValidation = true;
        private boolean enablePositionTracking = true;
        private boolean enableRiskChecks = true;
        private long orderTimeoutMs = 30000;
        private int maxOrdersPerSymbol = 100;
        private int maxTotalOrders = 1000;
        private boolean enableOrderCaching = true;
        private long orderCacheExpiryMs = 300000; // 5 minutes
        // Explicit getters/setters used by validation code
        public boolean isEnableOrderValidation() { return enableOrderValidation; }
        public void setEnableOrderValidation(boolean enableOrderValidation) { this.enableOrderValidation = enableOrderValidation; }
        public boolean isEnablePositionTracking() { return enablePositionTracking; }
        public void setEnablePositionTracking(boolean enablePositionTracking) { this.enablePositionTracking = enablePositionTracking; }
        public boolean isEnableRiskChecks() { return enableRiskChecks; }
        public void setEnableRiskChecks(boolean enableRiskChecks) { this.enableRiskChecks = enableRiskChecks; }
        public long getOrderTimeoutMs() { return orderTimeoutMs; }
        public void setOrderTimeoutMs(long orderTimeoutMs) { this.orderTimeoutMs = orderTimeoutMs; }
        public int getMaxOrdersPerSymbol() { return maxOrdersPerSymbol; }
        public void setMaxOrdersPerSymbol(int maxOrdersPerSymbol) { this.maxOrdersPerSymbol = maxOrdersPerSymbol; }
        public int getMaxTotalOrders() { return maxTotalOrders; }
        public void setMaxTotalOrders(int maxTotalOrders) { this.maxTotalOrders = maxTotalOrders; }
        public boolean isEnableOrderCaching() { return enableOrderCaching; }
        public void setEnableOrderCaching(boolean enableOrderCaching) { this.enableOrderCaching = enableOrderCaching; }
        public long getOrderCacheExpiryMs() { return orderCacheExpiryMs; }
        public void setOrderCacheExpiryMs(long orderCacheExpiryMs) { this.orderCacheExpiryMs = orderCacheExpiryMs; }
    }
    
    /**
     * Get custom setting
     */
    @SuppressWarnings("unchecked")
    public <T> T getCustomSetting(String key, T defaultValue) {
        return (T) customSettings.getOrDefault(key, defaultValue);
    }

    /**
     * Get adapter-specific custom settings map, e.g., custom-settings.binance.*
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getCustomSettingsFor(String adapter) {
        Object v = customSettings.get(adapter);
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        return new HashMap<>();
    }
    
    /**
     * Set custom setting
     */
    public void setCustomSetting(String key, Object value) {
        customSettings.put(key, value);
    }
    
    /**
     * Validate configuration
     */
    public boolean isValid() {
        if (!enabled) return true;
        
        if (type == null || type.trim().isEmpty()) {
            return false;
        }
        
        if (environment == null || environment.trim().isEmpty()) {
            return false;
        }
        
        // Validate connection config
        if (connection.getConnectTimeoutMs() <= 0 ||
            connection.getReadTimeoutMs() <= 0 ||
            connection.getWriteTimeoutMs() <= 0 ||
            connection.getMaxConnections() <= 0) {
            return false;
        }
        
        // Validate rate limit config
        if (rateLimit.isEnabled()) {
            if (rateLimit.getRequestsPerSecond() <= 0 ||
                rateLimit.getRequestsPerMinute() <= 0 ||
                rateLimit.getOrderRequestsPerSecond() <= 0 ||
                rateLimit.getOrderRequestsPerMinute() <= 0) {
                return false;
            }
        }
        
        // Validate retry config
        if (retry.isEnabled()) {
            if (retry.getMaxRetries() < 0 ||
                retry.getInitialDelayMs() <= 0 ||
                retry.getMultiplier() <= 1.0 ||
                retry.getMaxDelayMs() <= 0) {
                return false;
            }
        }
        
        // Validate order config
        if (order.getOrderTimeoutMs() <= 0 ||
            order.getMaxOrdersPerSymbol() <= 0 ||
            order.getMaxTotalOrders() <= 0) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Create a copy of the configuration
     */
    public AdapterConfig copy() {
        AdapterConfig copy = new AdapterConfig();
        copy.enabled = this.enabled;
        copy.type = this.type;
        copy.environment = this.environment;
        
        // Deep copy nested objects
        copy.connection = copyConnectionConfig(this.connection);
        copy.auth = copyAuthConfig(this.auth);
        copy.rateLimit = copyRateLimitConfig(this.rateLimit);
        copy.retry = copyRetryConfig(this.retry);
        copy.webSocket = copyWebSocketConfig(this.webSocket);
        copy.order = copyOrderConfig(this.order);
        copy.customSettings = new HashMap<>(this.customSettings);
        
        return copy;
    }
    
    private ConnectionConfig copyConnectionConfig(ConnectionConfig source) {
        ConnectionConfig copy = new ConnectionConfig();
        copy.baseUrl = source.baseUrl;
        copy.wsUrl = source.wsUrl;
        copy.connectTimeoutMs = source.connectTimeoutMs;
        copy.readTimeoutMs = source.readTimeoutMs;
        copy.writeTimeoutMs = source.writeTimeoutMs;
        copy.maxConnections = source.maxConnections;
        copy.enableCompression = source.enableCompression;
        copy.enableKeepAlive = source.enableKeepAlive;
        copy.keepAliveIntervalMs = source.keepAliveIntervalMs;
        return copy;
    }
    
    private AuthConfig copyAuthConfig(AuthConfig source) {
        AuthConfig copy = new AuthConfig();
        copy.apiKey = source.apiKey;
        copy.secretKey = source.secretKey;
        copy.passphrase = source.passphrase;
        copy.enableSignature = source.enableSignature;
        copy.signatureMethod = source.signatureMethod;
        copy.enableTimestamp = source.enableTimestamp;
        copy.timestampOffsetMs = source.timestampOffsetMs;
        if (source.exchanges != null) {
            copy.exchanges = new HashMap<>();
            source.exchanges.forEach((k, v) -> {
                if (v != null) {
                    AuthDetails d = new AuthDetails();
                    d.setApiKey(v.getApiKey());
                    d.setSecretKey(v.getSecretKey());
                    d.setPassphrase(v.getPassphrase());
                    d.setEnableSignature(v.isEnableSignature());
                    d.setSignatureMethod(v.getSignatureMethod());
                    d.setEnableTimestamp(v.isEnableTimestamp());
                    d.setTimestampOffsetMs(v.getTimestampOffsetMs());
                    copy.exchanges.put(k, d);
                }
            });
        }
        return copy;
    }
    
    private RateLimitConfig copyRateLimitConfig(RateLimitConfig source) {
        RateLimitConfig copy = new RateLimitConfig();
        copy.enabled = source.enabled;
        copy.requestsPerSecond = source.requestsPerSecond;
        copy.requestsPerMinute = source.requestsPerMinute;
        copy.orderRequestsPerSecond = source.orderRequestsPerSecond;
        copy.orderRequestsPerMinute = source.orderRequestsPerMinute;
        copy.rateLimitWindowMs = source.rateLimitWindowMs;
        copy.enableBackoff = source.enableBackoff;
        copy.backoffBaseMs = source.backoffBaseMs;
        copy.backoffMultiplier = source.backoffMultiplier;
        copy.maxBackoffMs = source.maxBackoffMs;
        return copy;
    }
    
    private RetryConfig copyRetryConfig(RetryConfig source) {
        RetryConfig copy = new RetryConfig();
        copy.enabled = source.enabled;
        copy.maxRetries = source.maxRetries;
        copy.initialDelayMs = source.initialDelayMs;
        copy.multiplier = source.multiplier;
        copy.maxDelayMs = source.maxDelayMs;
        copy.retryOnTimeout = source.retryOnTimeout;
        copy.retryOnRateLimit = source.retryOnRateLimit;
        copy.retryOnServerError = source.retryOnServerError;
        return copy;
    }
    
    private WebSocketConfig copyWebSocketConfig(WebSocketConfig source) {
        WebSocketConfig copy = new WebSocketConfig();
        copy.enabled = source.enabled;
        copy.autoReconnect = source.autoReconnect;
        copy.reconnectDelayMs = source.reconnectDelayMs;
        copy.maxReconnectAttempts = source.maxReconnectAttempts;
        copy.pingIntervalMs = source.pingIntervalMs;
        copy.pongTimeoutMs = source.pongTimeoutMs;
        copy.maxFrameSize = source.maxFrameSize;
        copy.enableCompression = source.enableCompression;
        return copy;
    }
    
    private OrderConfig copyOrderConfig(OrderConfig source) {
        OrderConfig copy = new OrderConfig();
        copy.enableOrderValidation = source.enableOrderValidation;
        copy.enablePositionTracking = source.enablePositionTracking;
        copy.enableRiskChecks = source.enableRiskChecks;
        copy.orderTimeoutMs = source.orderTimeoutMs;
        copy.maxOrdersPerSymbol = source.maxOrdersPerSymbol;
        copy.maxTotalOrders = source.maxTotalOrders;
        copy.enableOrderCaching = source.enableOrderCaching;
        copy.orderCacheExpiryMs = source.orderCacheExpiryMs;
        return copy;
    }
}
package com.bedrock.mm.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import com.sun.management.OperatingSystemMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Central monitoring service
 */
@Service
@ConditionalOnProperty(name = "bedrock.monitor.enabled", havingValue = "true", matchIfMissing = false)
public class MonitorService {
    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);
    
    private final MonitorConfig config;
    private MetricRegistry metricRegistry;
    private ScheduledExecutorService scheduler;
    
    // JVM metrics
    private MetricRegistry.Gauge heapUsedGauge;
    private MetricRegistry.Gauge heapMaxGauge;
    private MetricRegistry.Gauge nonHeapUsedGauge;
    private MetricRegistry.Gauge threadCountGauge;
    private MetricRegistry.Gauge uptimeGauge;
    
    // System metrics
    private MetricRegistry.Gauge cpuUsageGauge;
    private MetricRegistry.Counter gcCountCounter;
    private MetricRegistry.Timer gcTimeTimer;
    
    @Autowired
    public MonitorService(MonitorConfig config) {
        this.config = config;
        // Delay initialization of MetricRegistry until start() is called
        // This avoids Chronicle Map initialization issues in test environments
    }
    
    public void start() {
        if (!config.isEnabled()) {
            log.info("Monitoring is disabled");
            return;
        }
        
        log.info("Starting monitoring service with config: {}", config);
        
        // Initialize components only when monitoring is enabled
        try {
            //TODO 在JDK21 有反射问题，待解决。
            this.metricRegistry = new MemoryMappedMetricRegistry(config.getMetricsDirectory());
            this.scheduler = Executors.newScheduledThreadPool(2);

            initializeMetrics();
            scheduleMetricCollection();
            scheduleMetricFlush();

            log.info("Monitoring service started");
        } catch (Throwable t) {
            // Be defensive: if Chronicle Map or direct buffers fail to init (JDK/module issues),
            // degrade gracefully so the application can still start.
            log.error("Failed to initialize monitoring; disabling it. Cause: {}", t.toString(), t);
            // Cleanup any partially initialized resources
            if (scheduler != null) {
                try { scheduler.shutdownNow(); } catch (Exception ignored) {}
                scheduler = null;
            }
            if (metricRegistry != null) {
                try { metricRegistry.close(); } catch (Exception ignored) {}
                metricRegistry = null;
            }
            // Flip config flag so downstream callers get No-Op metrics
            try { config.setEnabled(false); } catch (Exception ignored) {}
        }
    }
    
    @PreDestroy
    // 移除 @PreDestroy，使用集中编排显式停止
    public void stop() {
        log.info("Stopping monitoring service");
        
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (metricRegistry != null) {
            metricRegistry.close();
        }
        log.info("Monitoring service stopped");
    }
    
    public MetricRegistry getMetricRegistry() {
        return metricRegistry;
    }
    
    private void initializeMetrics() {
        if (config.isEnableJvmMetrics()) {
            heapUsedGauge = metricRegistry.gauge(config.getMetricPrefix() + ".jvm.memory.heap.used");
            heapMaxGauge = metricRegistry.gauge(config.getMetricPrefix() + ".jvm.memory.heap.max");
            nonHeapUsedGauge = metricRegistry.gauge(config.getMetricPrefix() + ".jvm.memory.nonheap.used");
            threadCountGauge = metricRegistry.gauge(config.getMetricPrefix() + ".jvm.threads.count");
            uptimeGauge = metricRegistry.gauge(config.getMetricPrefix() + ".jvm.uptime");
        }
        
        if (config.isEnableSystemMetrics()) {
            cpuUsageGauge = metricRegistry.gauge(config.getMetricPrefix() + ".system.cpu.usage");
            gcCountCounter = metricRegistry.counter(config.getMetricPrefix() + ".jvm.gc.count");
            gcTimeTimer = metricRegistry.timer(config.getMetricPrefix() + ".jvm.gc.time");
        }
    }
    
    private void scheduleMetricCollection() {
        scheduler.scheduleAtFixedRate(this::collectMetrics, 0, 1, TimeUnit.SECONDS);
    }
    
    private void scheduleMetricFlush() {
        long flushIntervalSeconds = config.getFlushInterval().getSeconds();
        scheduler.scheduleAtFixedRate(metricRegistry::flush, 
                flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);
    }
    
    private void collectMetrics() {
        try {
            if (config.isEnableJvmMetrics()) {
                collectJvmMetrics();
            }
            
            if (config.isEnableSystemMetrics()) {
                collectSystemMetrics();
            }
        } catch (Exception e) {
            log.warn("Error collecting metrics", e);
        }
    }
    
    private void collectJvmMetrics() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        
        // Memory metrics
        heapUsedGauge.setValue(memoryBean.getHeapMemoryUsage().getUsed());
        heapMaxGauge.setValue(memoryBean.getHeapMemoryUsage().getMax());
        nonHeapUsedGauge.setValue(memoryBean.getNonHeapMemoryUsage().getUsed());
        
        // Thread metrics
        threadCountGauge.setValue(threadBean.getThreadCount());
        
        // Uptime
        uptimeGauge.setValue(runtimeBean.getUptime());
    }
    
    private void collectSystemMetrics() {
        // CPU usage (simplified)
        try {
            OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double cpuUsage = osBean.getProcessCpuLoad();
            if (cpuUsage >= 0) {
                cpuUsageGauge.setValue(cpuUsage * 100);
            }
        } catch (Exception e) {
            log.warn("Failed to collect CPU metrics: {}", e.getMessage());
        }
        
        // GC metrics would require more complex implementation
        // This is a placeholder for demonstration
    }
    
    /**
     * Create a counter metric
     */
    public MetricRegistry.Counter counter(String name) {
        if (metricRegistry == null) {
            return new NoOpCounter();
        }
        return metricRegistry.counter(config.getMetricPrefix() + "." + name);
    }
    
    /**
     * Create a gauge metric
     */
    public MetricRegistry.Gauge gauge(String name) {
        if (metricRegistry == null) {
            return new NoOpGauge();
        }
        return metricRegistry.gauge(config.getMetricPrefix() + "." + name);
    }
    
    /**
     * Create a histogram metric
     */
    public MetricRegistry.Histogram histogram(String name) {
        if (metricRegistry == null) {
            return new NoOpHistogram();
        }
        return metricRegistry.histogram(config.getMetricPrefix() + "." + name);
    }
    
    /**
     * Create a timer metric
     */
    public MetricRegistry.Timer timer(String name) {
        if (metricRegistry == null) {
            return new NoOpTimer();
        }
        return metricRegistry.timer(config.getMetricPrefix() + "." + name);
    }
    
    // No-op implementations for when monitoring is disabled
    private static class NoOpCounter implements MetricRegistry.Counter {
        @Override public void increment() {}
        @Override public void increment(long delta) {}
        @Override public long getCount() { return 0; }
        @Override public String getName() { return "noop"; }
        @Override public MetricRegistry.MetricType getType() { return MetricRegistry.MetricType.COUNTER; }
        @Override public long getLastUpdated() { return 0; }
    }
    
    private static class NoOpGauge implements MetricRegistry.Gauge {
        @Override public void setValue(double value) {}
        @Override public double getValue() { return 0.0; }
        @Override public String getName() { return "noop"; }
        @Override public MetricRegistry.MetricType getType() { return MetricRegistry.MetricType.GAUGE; }
        @Override public long getLastUpdated() { return 0; }
    }
    
    private static class NoOpHistogram implements MetricRegistry.Histogram {
        @Override public void update(long value) {}
        @Override public void update(double value) {}
        @Override public long getCount() { return 0; }
        @Override public double getMin() { return 0.0; }
        @Override public double getMax() { return 0.0; }
        @Override public double getMean() { return 0.0; }
        @Override public double getStdDev() { return 0.0; }
        @Override public double getPercentile(double percentile) { return 0.0; }
        @Override public String getName() { return "noop"; }
        @Override public MetricRegistry.MetricType getType() { return MetricRegistry.MetricType.HISTOGRAM; }
        @Override public long getLastUpdated() { return 0; }
    }
    
    private static class NoOpTimer implements MetricRegistry.Timer {
        @Override public void update(long duration, TimeUnit unit) {}
        @Override public void update(double duration, TimeUnit unit) {}
        @Override public long getCount() { return 0; }
        @Override public double getMin(TimeUnit unit) { return 0.0; }
        @Override public double getMax(TimeUnit unit) { return 0.0; }
        @Override public double getMean(TimeUnit unit) { return 0.0; }
        @Override public double getStdDev(TimeUnit unit) { return 0.0; }
        @Override public double getPercentile(double percentile, TimeUnit unit) { return 0.0; }
        @Override public double getRate() { return 0.0; }
        @Override public <T> T time(java.util.concurrent.Callable<T> callable) throws Exception { return callable.call(); }
        @Override public void time(Runnable runnable) { runnable.run(); }
        @Override public MetricRegistry.Timer.Context time() { return new NoOpContext(); }
        @Override public String getName() { return "noop"; }
        @Override public MetricRegistry.MetricType getType() { return MetricRegistry.MetricType.TIMER; }
        @Override public long getLastUpdated() { return 0; }
        
        private static class NoOpContext implements MetricRegistry.Timer.Context {
            @Override public void stop() {}
            @Override public void close() {}
        }
    }
}
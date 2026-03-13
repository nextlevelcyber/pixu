package com.bedrock.mm.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.openhft.chronicle.map.ChronicleMap;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Memory-mapped file backed metric registry
 */
public class MemoryMappedMetricRegistry implements MetricRegistry {
    private static final Logger log = LoggerFactory.getLogger(MemoryMappedMetricRegistry.class);
    
    private static final int METRIC_BUFFER_SIZE = 1024; // 1KB per metric
    private static final String DEFAULT_METRICS_DIR = "metrics";
    
    private final Path metricsDir;
    private final Map<String, Metric> metrics = new ConcurrentHashMap<>();
    private final ChronicleMap<String, MetricData> persistentMetrics;
    private volatile boolean closed = false;
    
    public MemoryMappedMetricRegistry() {
        this(DEFAULT_METRICS_DIR);
    }
    
    public MemoryMappedMetricRegistry(String metricsDirectory) {
        this.metricsDir = Paths.get(metricsDirectory);
        
        try {
            Files.createDirectories(metricsDir);
            
            File metricsFile = metricsDir.resolve("metrics.dat").toFile();
            this.persistentMetrics = ChronicleMap
                    .of(String.class, MetricData.class)
                    .entries(10000)
                    .createPersistedTo(metricsFile);
                    
            log.info("Initialized memory-mapped metric registry at {}", metricsDir);
            
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize metric registry", e);
        }
    }
    
    @Override
    public Counter counter(String name) {
        return (Counter) metrics.computeIfAbsent(name, k -> new MemoryMappedCounter(k));
    }
    
    @Override
    public Gauge gauge(String name) {
        return (Gauge) metrics.computeIfAbsent(name, k -> new MemoryMappedGauge(k));
    }
    
    @Override
    public Histogram histogram(String name) {
        return (Histogram) metrics.computeIfAbsent(name, k -> new MemoryMappedHistogram(k));
    }
    
    @Override
    public Timer timer(String name) {
        return (Timer) metrics.computeIfAbsent(name, k -> new MemoryMappedTimer(k));
    }
    
    @Override
    public Map<String, Metric> getMetrics() {
        return Map.copyOf(metrics);
    }
    
    @Override
    public void remove(String name) {
        Metric metric = metrics.remove(name);
        if (metric != null) {
            persistentMetrics.remove(name);
        }
    }
    
    @Override
    public void clear() {
        metrics.clear();
        persistentMetrics.clear();
    }
    
    @Override
    public void flush() {
        for (Metric metric : metrics.values()) {
            if (metric instanceof MemoryMappedMetric) {
                ((MemoryMappedMetric) metric).flush();
            }
        }
    }
    
    @Override
    public void close() {
        if (!closed) {
            flush();
            persistentMetrics.close();
            closed = true;
            log.info("Closed memory-mapped metric registry");
        }
    }
    
    // Base class for memory-mapped metrics
    private abstract class MemoryMappedMetric implements Metric {
        protected final String name;
        protected final AtomicBuffer buffer;
        protected final AtomicLong lastUpdated = new AtomicLong(System.currentTimeMillis());
        
        protected MemoryMappedMetric(String name) {
            this.name = name;
            this.buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(METRIC_BUFFER_SIZE));
        }
        
        @Override
        public String getName() {
            return name;
        }
        
        @Override
        public long getLastUpdated() {
            return lastUpdated.get();
        }
        
        protected void updateTimestamp() {
            lastUpdated.set(System.currentTimeMillis());
        }
        
        public abstract void flush();
    }
    
    // Counter implementation
    private class MemoryMappedCounter extends MemoryMappedMetric implements Counter {
        private static final int COUNT_OFFSET = 0;
        
        public MemoryMappedCounter(String name) {
            super(name);
            // Load existing value if available
            MetricData existing = persistentMetrics.get(name);
            if (existing != null) {
                buffer.putLong(COUNT_OFFSET, existing.longValue);
            }
        }
        
        @Override
        public void increment() {
            increment(1);
        }
        
        @Override
        public void increment(long delta) {
            buffer.getAndAddLong(COUNT_OFFSET, delta);
            updateTimestamp();
        }
        
        @Override
        public long getCount() {
            return buffer.getLong(COUNT_OFFSET);
        }
        
        @Override
        public MetricType getType() {
            return MetricType.COUNTER;
        }
        
        @Override
        public void flush() {
            MetricData data = new MetricData();
            data.type = MetricType.COUNTER;
            data.longValue = getCount();
            data.lastUpdated = getLastUpdated();
            persistentMetrics.put(name, data);
        }
    }
    
    // Gauge implementation
    private class MemoryMappedGauge extends MemoryMappedMetric implements Gauge {
        private static final int VALUE_OFFSET = 0;
        
        public MemoryMappedGauge(String name) {
            super(name);
            // Load existing value if available
            MetricData existing = persistentMetrics.get(name);
            if (existing != null) {
                buffer.putDouble(VALUE_OFFSET, existing.doubleValue);
            }
        }
        
        @Override
        public void setValue(double value) {
            buffer.putDouble(VALUE_OFFSET, value);
            updateTimestamp();
        }
        
        @Override
        public double getValue() {
            return buffer.getDouble(VALUE_OFFSET);
        }
        
        @Override
        public MetricType getType() {
            return MetricType.GAUGE;
        }
        
        @Override
        public void flush() {
            MetricData data = new MetricData();
            data.type = MetricType.GAUGE;
            data.doubleValue = getValue();
            data.lastUpdated = getLastUpdated();
            persistentMetrics.put(name, data);
        }
    }
    
    // Simplified histogram implementation
    private class MemoryMappedHistogram extends MemoryMappedMetric implements Histogram {
        private static final int COUNT_OFFSET = 0;
        private static final int SUM_OFFSET = 8;
        private static final int MIN_OFFSET = 16;
        private static final int MAX_OFFSET = 24;
        private static final int SAMPLE_SIZE = 4096;
        private final double[] samples = new double[SAMPLE_SIZE];
        private final AtomicLong sampleWrites = new AtomicLong(0);
        
        public MemoryMappedHistogram(String name) {
            super(name);
            buffer.putDouble(MIN_OFFSET, Double.MAX_VALUE);
            buffer.putDouble(MAX_OFFSET, -Double.MAX_VALUE);
        }
        
        @Override
        public void update(long value) {
            update((double) value);
        }
        
        @Override
        public void update(double value) {
            buffer.getAndAddLong(COUNT_OFFSET, 1);
            // For double values, we need to use a different approach since there's no atomic addDouble
            double currentSum = buffer.getDouble(SUM_OFFSET);
            buffer.putDouble(SUM_OFFSET, currentSum + value);
            
            // Update min
            double currentMin = buffer.getDouble(MIN_OFFSET);
            if (value < currentMin) {
                buffer.putDouble(MIN_OFFSET, value);
            }
            
            // Update max
            double currentMax = buffer.getDouble(MAX_OFFSET);
            if (value > currentMax) {
                buffer.putDouble(MAX_OFFSET, value);
            }

            long writeSeq = sampleWrites.getAndIncrement();
            samples[(int) (writeSeq % SAMPLE_SIZE)] = value;
            
            updateTimestamp();
        }
        
        @Override
        public long getCount() {
            return buffer.getLong(COUNT_OFFSET);
        }
        
        @Override
        public double getMin() {
            return buffer.getDouble(MIN_OFFSET);
        }
        
        @Override
        public double getMax() {
            return buffer.getDouble(MAX_OFFSET);
        }
        
        @Override
        public double getMean() {
            long count = getCount();
            return count > 0 ? buffer.getDouble(SUM_OFFSET) / count : 0.0;
        }
        
        @Override
        public double getStdDev() {
            // Simplified implementation
            return 0.0;
        }
        
        @Override
        public double getPercentile(double percentile) {
            if (percentile <= 0.0) {
                return getMin();
            }
            if (percentile >= 100.0) {
                return getMax();
            }

            long totalCount = getCount();
            if (totalCount <= 0) {
                return 0.0;
            }

            int available = (int) Math.min(totalCount, SAMPLE_SIZE);
            double[] copy = new double[available];
            long writes = sampleWrites.get();
            if (writes <= SAMPLE_SIZE) {
                System.arraycopy(samples, 0, copy, 0, available);
            } else {
                int start = (int) (writes % SAMPLE_SIZE);
                int firstPart = SAMPLE_SIZE - start;
                if (firstPart >= available) {
                    System.arraycopy(samples, start, copy, 0, available);
                } else {
                    System.arraycopy(samples, start, copy, 0, firstPart);
                    System.arraycopy(samples, 0, copy, firstPart, available - firstPart);
                }
            }

            Arrays.sort(copy);
            int index = (int) Math.ceil((percentile / 100.0) * available) - 1;
            if (index < 0) {
                index = 0;
            } else if (index >= available) {
                index = available - 1;
            }
            return copy[index];
        }
        
        @Override
        public MetricType getType() {
            return MetricType.HISTOGRAM;
        }
        
        @Override
        public void flush() {
            MetricData data = new MetricData();
            data.type = MetricType.HISTOGRAM;
            data.longValue = getCount();
            data.doubleValue = getMean();
            data.lastUpdated = getLastUpdated();
            persistentMetrics.put(name, data);
        }
    }
    
    // Timer implementation
    private class MemoryMappedTimer extends MemoryMappedHistogram implements Timer {
        
        public MemoryMappedTimer(String name) {
            super(name);
        }
        
        @Override
        public void update(long duration, TimeUnit unit) {
            update(TimeUnit.NANOSECONDS.convert(duration, unit));
        }
        
        @Override
        public void update(double duration, TimeUnit unit) {
            update(TimeUnit.NANOSECONDS.convert((long) duration, unit));
        }
        
        @Override
        public double getMin(TimeUnit unit) {
            return unit.convert((long) getMin(), TimeUnit.NANOSECONDS);
        }
        
        @Override
        public double getMax(TimeUnit unit) {
            return unit.convert((long) getMax(), TimeUnit.NANOSECONDS);
        }
        
        @Override
        public double getMean(TimeUnit unit) {
            return unit.convert((long) getMean(), TimeUnit.NANOSECONDS);
        }
        
        @Override
        public double getStdDev(TimeUnit unit) {
            return unit.convert((long) getStdDev(), TimeUnit.NANOSECONDS);
        }
        
        @Override
        public double getPercentile(double percentile, TimeUnit unit) {
            return unit.convert((long) getPercentile(percentile), TimeUnit.NANOSECONDS);
        }
        
        @Override
        public double getRate() {
            long duration = System.currentTimeMillis() - getLastUpdated();
            return duration > 0 ? (getCount() * 1000.0) / duration : 0.0;
        }
        
        @Override
        public <T> T time(java.util.concurrent.Callable<T> callable) throws Exception {
            long start = System.nanoTime();
            try {
                return callable.call();
            } finally {
                update(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            }
        }
        
        @Override
        public void time(Runnable runnable) {
            long start = System.nanoTime();
            try {
                runnable.run();
            } finally {
                update(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            }
        }
        
        @Override
        public Context time() {
            return new TimerContext();
        }
        
        @Override
        public MetricType getType() {
            return MetricType.TIMER;
        }
        
        private class TimerContext implements Context {
            private final long startTime = System.nanoTime();
            private volatile boolean stopped = false;
            
            @Override
            public void stop() {
                if (!stopped) {
                    update(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
                    stopped = true;
                }
            }
            
            @Override
            public void close() {
                stop();
            }
        }
    }
    
    // Data class for persistence
    public static class MetricData {
        public MetricType type;
        public long longValue;
        public double doubleValue;
        public long lastUpdated;
    }
}

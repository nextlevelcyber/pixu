package com.bedrock.mm.monitor;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Registry for metrics with memory-mapped file backing
 */
public interface MetricRegistry {
    
    /**
     * Register a counter metric
     */
    Counter counter(String name);
    
    /**
     * Register a gauge metric
     */
    Gauge gauge(String name);
    
    /**
     * Register a histogram metric
     */
    Histogram histogram(String name);
    
    /**
     * Register a timer metric
     */
    Timer timer(String name);
    
    /**
     * Get all registered metrics
     */
    Map<String, Metric> getMetrics();
    
    /**
     * Remove a metric
     */
    void remove(String name);
    
    /**
     * Clear all metrics
     */
    void clear();
    
    /**
     * Flush metrics to persistent storage
     */
    void flush();
    
    /**
     * Close the registry and release resources
     */
    void close();
    
    /**
     * Counter metric interface
     */
    interface Counter extends Metric {
        void increment();
        void increment(long delta);
        long getCount();
    }
    
    /**
     * Gauge metric interface
     */
    interface Gauge extends Metric {
        void setValue(double value);
        double getValue();
    }
    
    /**
     * Histogram metric interface
     */
    interface Histogram extends Metric {
        void update(long value);
        void update(double value);
        long getCount();
        double getMin();
        double getMax();
        double getMean();
        double getStdDev();
        double getPercentile(double percentile);
    }
    
    /**
     * Timer metric interface
     */
    interface Timer extends Metric {
        void update(long duration, TimeUnit unit);
        void update(double duration, TimeUnit unit);
        long getCount();
        double getMin(TimeUnit unit);
        double getMax(TimeUnit unit);
        double getMean(TimeUnit unit);
        double getStdDev(TimeUnit unit);
        double getPercentile(double percentile, TimeUnit unit);
        double getRate();
        
        /**
         * Time a callable
         */
        <T> T time(java.util.concurrent.Callable<T> callable) throws Exception;
        
        /**
         * Time a runnable
         */
        void time(Runnable runnable);
        
        /**
         * Create a timing context
         */
        Context time();
        
        interface Context extends AutoCloseable {
            void stop();
            @Override
            void close();
        }
    }
    
    /**
     * Base metric interface
     */
    interface Metric {
        String getName();
        MetricType getType();
        long getLastUpdated();
    }
    
    /**
     * Metric types
     */
    enum MetricType {
        COUNTER, GAUGE, HISTOGRAM, TIMER
    }
}
package com.bedrock.mm.md.book;

import com.bedrock.mm.common.model.Symbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sequence number validator for detecting message loss, duplication, and rewind.
 * Triggers order book rebuild when gaps or anomalies are detected.
 *
 * Thread-safe per-symbol state tracking with zero allocation on hot path.
 * Use symbolId as array index for O(1) lookup without HashMap overhead.
 */
public interface SequenceValidator {

    /**
     * Validate sequence number continuity.
     *
     * @param symbol trading symbol
     * @param seq current message sequence number
     * @return validation result
     */
    ValidationResult validate(Symbol symbol, long seq);

    /**
     * Set known-good snapshot sequence number (call after fetching REST snapshot).
     *
     * @param symbol trading symbol
     * @param snapshotSeq final update sequence of the snapshot
     */
    void setSnapshotSequence(Symbol symbol, long snapshotSeq);

    /**
     * Reset validator state (call after reconnect/resubscribe).
     *
     * @param symbol trading symbol
     */
    void reset(Symbol symbol);

    /**
     * Get last validated sequence number.
     *
     * @param symbol trading symbol
     * @return last valid sequence, -1 if uninitialized
     */
    long getLastValidSequence(Symbol symbol);

    /**
     * Validation result enum.
     */
    enum ValidationResult {
        /** Continuous sequence, safe to apply delta */
        VALID,
        /** First message received, need snapshot initialization */
        NEED_SNAPSHOT,
        /** Sequence gap detected (message loss), need order book rebuild */
        GAP_DETECTED,
        /** Duplicate sequence number, ignore this message */
        DUPLICATE,
        /** Sequence rewind detected (anomaly), need rebuild and alert */
        REWIND_DETECTED
    }

    /**
     * Create default implementation with primitive long[] state tracking.
     *
     * @param maxSymbolId maximum symbol ID (array size = maxSymbolId + 1)
     * @return zero-allocation validator instance
     */
    static SequenceValidator createDefault(int maxSymbolId) {
        return new DefaultSequenceValidator(maxSymbolId);
    }
}

/**
 * Default implementation using primitive long[] for per-symbol state.
 * No HashMap, no boxing, no allocation on hot path.
 *
 * Array index = symbolId, value = last validated sequence (-1 = uninitialized).
 */
class DefaultSequenceValidator implements SequenceValidator {
    private static final Logger log = LoggerFactory.getLogger(DefaultSequenceValidator.class);
    private static final long UNINITIALIZED = -1L;

    private final long[] lastSequences;

    DefaultSequenceValidator(int maxSymbolId) {
        this.lastSequences = new long[maxSymbolId + 1];
        for (int i = 0; i < lastSequences.length; i++) {
            lastSequences[i] = UNINITIALIZED;
        }
    }

    @Override
    public ValidationResult validate(Symbol symbol, long seq) {
        int symbolId = symbol.getSymbolId();
        if (symbolId < 0 || symbolId >= lastSequences.length) {
            log.error("Invalid symbolId {} for {}, out of bounds [0, {})",
                    symbolId, symbol.getName(), lastSequences.length);
            return ValidationResult.GAP_DETECTED; // Conservative: trigger rebuild
        }

        long lastSeq = lastSequences[symbolId];

        // First message for this symbol
        if (lastSeq == UNINITIALIZED) {
            log.debug("First sequence for {}: {}, need snapshot", symbol.getName(), seq);
            return ValidationResult.NEED_SNAPSHOT;
        }

        // Duplicate
        if (seq == lastSeq) {
            log.debug("Duplicate sequence for {}: {}", symbol.getName(), seq);
            return ValidationResult.DUPLICATE;
        }

        // Rewind (seq < lastSeq) - anomaly
        if (seq < lastSeq) {
            log.warn("Sequence rewind detected for {}: {} -> {}, need rebuild",
                    symbol.getName(), lastSeq, seq);
            return ValidationResult.REWIND_DETECTED;
        }

        // Gap detection (seq > lastSeq + 1)
        if (seq > lastSeq + 1) {
            long gap = seq - lastSeq - 1;
            log.warn("Sequence gap detected for {}: {} -> {} (missed {} messages), need rebuild",
                    symbol.getName(), lastSeq, seq, gap);
            return ValidationResult.GAP_DETECTED;
        }

        // Continuous (seq == lastSeq + 1)
        lastSequences[symbolId] = seq;
        return ValidationResult.VALID;
    }

    @Override
    public void setSnapshotSequence(Symbol symbol, long snapshotSeq) {
        int symbolId = symbol.getSymbolId();
        if (symbolId < 0 || symbolId >= lastSequences.length) {
            log.error("Invalid symbolId {} for {}, cannot set snapshot sequence",
                    symbolId, symbol.getName());
            return;
        }

        lastSequences[symbolId] = snapshotSeq;
        log.info("Set snapshot sequence for {}: {}", symbol.getName(), snapshotSeq);
    }

    @Override
    public void reset(Symbol symbol) {
        int symbolId = symbol.getSymbolId();
        if (symbolId < 0 || symbolId >= lastSequences.length) {
            log.error("Invalid symbolId {} for {}, cannot reset",
                    symbolId, symbol.getName());
            return;
        }

        lastSequences[symbolId] = UNINITIALIZED;
        log.info("Reset sequence state for {}", symbol.getName());
    }

    @Override
    public long getLastValidSequence(Symbol symbol) {
        int symbolId = symbol.getSymbolId();
        if (symbolId < 0 || symbolId >= lastSequences.length) {
            log.error("Invalid symbolId {} for {}, cannot get last sequence",
                    symbolId, symbol.getName());
            return UNINITIALIZED;
        }

        return lastSequences[symbolId];
    }
}

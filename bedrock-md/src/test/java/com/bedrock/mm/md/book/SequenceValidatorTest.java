package com.bedrock.mm.md.book;

import com.bedrock.mm.common.model.Symbol;
import com.bedrock.mm.md.book.SequenceValidator.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SequenceValidator.
 * Verifies gap detection, duplicate handling, rewind detection, and reset logic.
 */
class SequenceValidatorTest {

    private SequenceValidator validator;
    private Symbol btcUsdt;
    private Symbol ethUsdt;

    @BeforeEach
    void setUp() {
        validator = SequenceValidator.createDefault(10000);
        btcUsdt = Symbol.btcUsdt();
        ethUsdt = Symbol.ethUsdt();
    }

    @Test
    void testFirstSequenceNeedsSnapshot() {
        ValidationResult result = validator.validate(btcUsdt, 100L);
        assertEquals(ValidationResult.NEED_SNAPSHOT, result);
        assertEquals(-1L, validator.getLastValidSequence(btcUsdt));
    }

    @Test
    void testValidContinuousSequence() {
        validator.setSnapshotSequence(btcUsdt, 100L);

        ValidationResult result1 = validator.validate(btcUsdt, 101L);
        assertEquals(ValidationResult.VALID, result1);
        assertEquals(101L, validator.getLastValidSequence(btcUsdt));

        ValidationResult result2 = validator.validate(btcUsdt, 102L);
        assertEquals(ValidationResult.VALID, result2);
        assertEquals(102L, validator.getLastValidSequence(btcUsdt));
    }

    @Test
    void testDuplicateSequence() {
        validator.setSnapshotSequence(btcUsdt, 100L);
        validator.validate(btcUsdt, 101L);

        ValidationResult result = validator.validate(btcUsdt, 101L);
        assertEquals(ValidationResult.DUPLICATE, result);
        assertEquals(101L, validator.getLastValidSequence(btcUsdt));
    }

    @Test
    void testGapDetection() {
        validator.setSnapshotSequence(btcUsdt, 100L);
        validator.validate(btcUsdt, 101L);

        // Gap: 101 -> 105 (missing 102, 103, 104)
        ValidationResult result = validator.validate(btcUsdt, 105L);
        assertEquals(ValidationResult.GAP_DETECTED, result);
        assertEquals(101L, validator.getLastValidSequence(btcUsdt)); // Should not update
    }

    @Test
    void testRewindDetection() {
        validator.setSnapshotSequence(btcUsdt, 100L);
        validator.validate(btcUsdt, 101L);
        validator.validate(btcUsdt, 102L);

        // Rewind: 102 -> 99
        ValidationResult result = validator.validate(btcUsdt, 99L);
        assertEquals(ValidationResult.REWIND_DETECTED, result);
        assertEquals(102L, validator.getLastValidSequence(btcUsdt)); // Should not update
    }

    @Test
    void testPerSymbolIsolation() {
        // Set different sequences for different symbols
        validator.setSnapshotSequence(btcUsdt, 100L);
        validator.setSnapshotSequence(ethUsdt, 200L);

        // Validate independently
        ValidationResult btcResult = validator.validate(btcUsdt, 101L);
        assertEquals(ValidationResult.VALID, btcResult);
        assertEquals(101L, validator.getLastValidSequence(btcUsdt));

        ValidationResult ethResult = validator.validate(ethUsdt, 201L);
        assertEquals(ValidationResult.VALID, ethResult);
        assertEquals(201L, validator.getLastValidSequence(ethUsdt));

        // Verify isolation
        assertEquals(101L, validator.getLastValidSequence(btcUsdt));
        assertEquals(201L, validator.getLastValidSequence(ethUsdt));
    }

    @Test
    void testReset() {
        validator.setSnapshotSequence(btcUsdt, 100L);
        validator.validate(btcUsdt, 101L);
        assertEquals(101L, validator.getLastValidSequence(btcUsdt));

        // Reset should clear state
        validator.reset(btcUsdt);
        assertEquals(-1L, validator.getLastValidSequence(btcUsdt));

        // Next validation should require snapshot
        ValidationResult result = validator.validate(btcUsdt, 200L);
        assertEquals(ValidationResult.NEED_SNAPSHOT, result);
    }

    @Test
    void testSnapshotSequenceSetting() {
        validator.setSnapshotSequence(btcUsdt, 1000L);
        assertEquals(1000L, validator.getLastValidSequence(btcUsdt));

        // Next sequence should be continuous
        ValidationResult result = validator.validate(btcUsdt, 1001L);
        assertEquals(ValidationResult.VALID, result);
    }

    @Test
    void testInvalidSymbolIdHandling() {
        // Create symbol with out-of-bounds ID
        Symbol invalidSymbol = new Symbol(99999, "INVALID", "INV", "USDT", 2, 6);

        ValidationResult result = validator.validate(invalidSymbol, 100L);
        // Should return GAP_DETECTED as conservative fallback
        assertEquals(ValidationResult.GAP_DETECTED, result);
    }

    @Test
    void testLargeGap() {
        validator.setSnapshotSequence(btcUsdt, 100L);
        validator.validate(btcUsdt, 101L);

        // Very large gap
        ValidationResult result = validator.validate(btcUsdt, 100000L);
        assertEquals(ValidationResult.GAP_DETECTED, result);
    }

    @Test
    void testMultipleSymbolsWithGaps() {
        validator.setSnapshotSequence(btcUsdt, 100L);
        validator.setSnapshotSequence(ethUsdt, 200L);

        // BTC has gap
        validator.validate(btcUsdt, 101L);
        ValidationResult btcResult = validator.validate(btcUsdt, 105L);
        assertEquals(ValidationResult.GAP_DETECTED, btcResult);

        // ETH should be unaffected
        ValidationResult ethResult = validator.validate(ethUsdt, 201L);
        assertEquals(ValidationResult.VALID, ethResult);
    }
}

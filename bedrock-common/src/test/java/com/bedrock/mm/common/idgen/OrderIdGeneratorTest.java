package com.bedrock.mm.common.idgen;

import com.bedrock.mm.common.model.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OrderIdGeneratorTest {

    private OrderIdGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new OrderIdGenerator(123);
    }

    @Test
    void testBasicGeneration() {
        long id1 = generator.nextId(Side.BUY);
        long id2 = generator.nextId(Side.SELL);

        assertNotEquals(id1, id2, "Sequential IDs must be unique");
        assertTrue(id2 > id1, "IDs must be monotonically increasing");
    }

    @Test
    void testInstrumentIdExtraction() {
        long orderId = generator.nextId(Side.BUY);
        int extractedInstrumentId = OrderIdGenerator.extractInstrumentId(orderId);

        assertEquals(123, extractedInstrumentId, "Instrument ID must match");
    }

    @Test
    void testSideExtraction() {
        long buyOrderId = generator.nextId(Side.BUY);
        long sellOrderId = generator.nextId(Side.SELL);

        assertEquals(Side.BUY, OrderIdGenerator.extractSide(buyOrderId), "BUY side must be encoded correctly");
        assertEquals(Side.SELL, OrderIdGenerator.extractSide(sellOrderId), "SELL side must be encoded correctly");
    }

    @Test
    void testSequenceExtraction() {
        generator.reset();

        long id1 = generator.nextId(Side.BUY);
        long id2 = generator.nextId(Side.BUY);
        long id3 = generator.nextId(Side.BUY);

        assertEquals(0, OrderIdGenerator.extractSequence(id1), "First ID sequence must be 0");
        assertEquals(1, OrderIdGenerator.extractSequence(id2), "Second ID sequence must be 1");
        assertEquals(2, OrderIdGenerator.extractSequence(id3), "Third ID sequence must be 2");
    }

    @Test
    void testTimestampExtraction() {
        long beforeMs = System.currentTimeMillis();
        long orderId = generator.nextId(Side.BUY);
        long afterMs = System.currentTimeMillis();

        long extractedTs = OrderIdGenerator.extractTimestamp(orderId);

        assertTrue(extractedTs >= beforeMs, "Extracted timestamp must be >= generation time start");
        assertTrue(extractedTs <= afterMs, "Extracted timestamp must be <= generation time end");
    }

    @Test
    void testUniquenessWithinMillisecond() {
        generator.reset();
        Set<Long> ids = new HashSet<>();
        int count = 1000;

        for (int i = 0; i < count; i++) {
            long id = generator.nextId(i % 2 == 0 ? Side.BUY : Side.SELL);
            assertTrue(ids.add(id), "All IDs within same millisecond must be unique");
        }

        assertEquals(count, ids.size(), "Generated count must match unique ID count");
    }

    @Test
    void testUniquenessAcrossInstruments() {
        OrderIdGenerator gen1 = new OrderIdGenerator(1);
        OrderIdGenerator gen2 = new OrderIdGenerator(2);

        long id1 = gen1.nextId(Side.BUY);
        long id2 = gen2.nextId(Side.BUY);

        assertNotEquals(id1, id2, "IDs from different instruments must be unique");

        int inst1 = OrderIdGenerator.extractInstrumentId(id1);
        int inst2 = OrderIdGenerator.extractInstrumentId(id2);

        assertEquals(1, inst1, "Instrument 1 ID must be encoded");
        assertEquals(2, inst2, "Instrument 2 ID must be encoded");
    }

    @Test
    void testMonotonicityWithinInstrument() {
        long prevId = 0;
        for (int i = 0; i < 100; i++) {
            long id = generator.nextId(Side.BUY);
            assertTrue(id > prevId, "IDs must be strictly increasing");
            prevId = id;
        }
    }

    @Test
    void testInvalidInstrumentId() {
        assertThrows(IllegalArgumentException.class, () -> new OrderIdGenerator(-1),
            "Negative instrument ID must throw");
        assertThrows(IllegalArgumentException.class, () -> new OrderIdGenerator(0x10000),
            "Instrument ID > 65535 must throw");
    }

    @Test
    void testSequenceRolloverToNextMillisecond() throws InterruptedException {
        generator.reset();

        // Generate IDs until we exhaust sequence or hit new millisecond
        long firstTs = System.currentTimeMillis();
        int count = 0;
        long lastId = 0;

        while (count < 100) {
            lastId = generator.nextId(Side.BUY);
            count++;

            // Check if we rolled to next millisecond
            if (OrderIdGenerator.extractTimestamp(lastId) > firstTs) {
                // Sequence should reset to 0 in new millisecond
                assertEquals(0, OrderIdGenerator.extractSequence(lastId),
                    "Sequence must reset to 0 on new millisecond");
                break;
            }
        }
    }

    @Test
    void testPerformanceBenchmark() {
        // Warm-up
        OrderIdGenerator benchGen = new OrderIdGenerator(999);
        for (int i = 0; i < 10_000; i++) {
            benchGen.nextId(Side.BUY);
        }

        // Benchmark
        benchGen.reset();
        int iterations = 10_000_000;
        long startNs = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            benchGen.nextId(i % 2 == 0 ? Side.BUY : Side.SELL);
        }

        long elapsedNs = System.nanoTime() - startNs;
        double opsPerSec = (iterations * 1_000_000_000.0) / elapsedNs;

        System.out.printf("OrderIdGenerator performance: %.2f M ops/sec (%.2f ns/op)%n",
            opsPerSec / 1_000_000, (double) elapsedNs / iterations);

        assertTrue(opsPerSec > 10_000_000,
            String.format("Throughput %.2f M ops/s must exceed 10M ops/s", opsPerSec / 1_000_000));
    }

    @Test
    void testReset() {
        long id1 = generator.nextId(Side.BUY);
        generator.reset();
        long id2 = generator.nextId(Side.BUY);

        // After reset, sequence should restart but timestamp will be different
        int seq1 = OrderIdGenerator.extractSequence(id1);
        int seq2 = OrderIdGenerator.extractSequence(id2);

        assertEquals(0, seq2, "Sequence must reset to 0 after reset()");
    }

    @Test
    void testBitFieldIsolation() {
        // Verify bit fields don't overlap by setting extreme values
        OrderIdGenerator maxInstrument = new OrderIdGenerator(0xFFFF);
        long id = maxInstrument.nextId(Side.SELL);

        int instrument = OrderIdGenerator.extractInstrumentId(id);
        Side side = OrderIdGenerator.extractSide(id);

        assertEquals(0xFFFF, instrument, "Max instrument ID must be preserved");
        assertEquals(Side.SELL, side, "Side must be preserved with max instrument ID");
    }
}

package com.bedrock.mm.aeron.bus.instrument;

import com.bedrock.mm.aeron.channel.ChannelConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registry for per-instrument stream ID allocation.
 * <p>
 * Strategy: Each instrument gets a base stream ID (1000, 2000, 3000...),
 * and channel types are added as offsets (+1 for mds.bbo, +2 for mds.depth, etc.).
 * <p>
 * Example:
 * - binance:BTC-USDT → base 1000
 *   - mds.bbo → stream 1001
 *   - mds.depth → stream 1002
 *   - pricing.target → stream 2001
 * - binance:ETH-USDT → base 10000
 *   - mds.bbo → stream 10001
 *   - mds.depth → stream 10002
 */
public class InstrumentRegistry {
    private static final Logger log = LoggerFactory.getLogger(InstrumentRegistry.class);

    // Base stream ID increment per instrument (allocate 1000 stream IDs per instrument)
    private static final int BASE_INCREMENT = 10000;
    private static final int STARTING_BASE = 1000;

    private final Map<String, Integer> instrumentToBase = new ConcurrentHashMap<>();
    private final AtomicInteger nextBase = new AtomicInteger(STARTING_BASE);

    /**
     * Register an instrument and allocate its base stream ID.
     *
     * @param instrumentId instrument identifier (e.g., "binance:BTC-USDT")
     * @return base stream ID
     */
    public int registerInstrument(String instrumentId) {
        return instrumentToBase.computeIfAbsent(instrumentId, id -> {
            int base = nextBase.getAndAdd(BASE_INCREMENT);
            log.info("InstrumentRegistry: allocated base stream ID {} for instrument {}", base, id);
            return base;
        });
    }

    /**
     * Get stream ID for a specific channel on an instrument.
     *
     * @param instrumentId instrument identifier
     * @param channelName channel name (e.g., "mds.bbo")
     * @return stream ID, or -1 if instrument not registered
     */
    public int getStreamId(String instrumentId, String channelName) {
        Integer base = instrumentToBase.get(instrumentId);
        if (base == null) {
            return -1;
        }
        int offset = getChannelOffset(channelName);
        if (offset < 0) {
            log.warn("InstrumentRegistry: unknown channel name {}", channelName);
            return -1;
        }
        return base + offset;
    }

    /**
     * Get base stream ID for an instrument.
     *
     * @param instrumentId instrument identifier
     * @return base stream ID, or -1 if not registered
     */
    public int getBaseStreamId(String instrumentId) {
        Integer base = instrumentToBase.get(instrumentId);
        return base != null ? base : -1;
    }

    /**
     * Check if an instrument is registered.
     *
     * @param instrumentId instrument identifier
     * @return true if registered
     */
    public boolean isRegistered(String instrumentId) {
        return instrumentToBase.containsKey(instrumentId);
    }

    /**
     * Get all registered instruments.
     *
     * @return map of instrument ID to base stream ID
     */
    public Map<String, Integer> getAllInstruments() {
        return new ConcurrentHashMap<>(instrumentToBase);
    }

    /**
     * Map channel name to offset from base stream ID.
     * Offsets are derived from ChannelConstants stream IDs.
     *
     * @param channelName channel name
     * @return offset, or -1 if unknown
     */
    private static int getChannelOffset(String channelName) {
        return switch (channelName) {
            case ChannelConstants.CHANNEL_MDS_BBO -> 1;       // 1001
            case ChannelConstants.CHANNEL_MDS_DEPTH -> 2;     // 1002
            case ChannelConstants.CHANNEL_MDS_TRADE -> 3;     // 1003
            case ChannelConstants.CHANNEL_PRICING_TARGET -> 2001 - 1000; // 1001
            case ChannelConstants.CHANNEL_OMS_POSITION -> 3001 - 1000;   // 2001
            case ChannelConstants.CHANNEL_OMS_ORDER -> 3002 - 1000;      // 2002
            case ChannelConstants.CHANNEL_MGMT_CMD -> 9001 - 1000;       // 8001
            default -> -1;
        };
    }
}

package com.bedrock.mm.aeron.channel;

/**
 * Aeron channel definitions for per-instrument communication.
 *
 * This class defines the standard stream IDs and channel names used across the Bedrock
 * market making infrastructure. Each channel represents a unidirectional data flow between
 * specific components.
 *
 * <h2>Channel Naming Convention</h2>
 * Format: {@code <source>.<data-type>}
 * - source: producing component (mds, pricing, oms, mgmt)
 * - data-type: message category (bbo, depth, trade, target, position, order, cmd)
 *
 * <h2>Stream ID Allocation</h2>
 * - 1000-1999: Market Data Service (MDS) channels
 * - 2000-2999: Pricing/Strategy channels
 * - 3000-3999: Order Management System (OMS) channels
 * - 9000-9999: Management/Control channels
 *
 * <h2>Transport Modes</h2>
 * These stream IDs work with all Aeron transport modes:
 * - IN_PROC: Agrona Ringbuffer (single process)
 * - AERON_IPC: Aeron IPC shared memory (multi-process, same host)
 * - AERON_UDP: Aeron UDP (multi-process, cross-host)
 */
public final class ChannelConstants {

    private ChannelConstants() {
        // Utility class
    }

    // ==================== Market Data Service (MDS) Channels ====================

    /**
     * Channel: {@code mds.bbo}
     * <p>
     * Best Bid/Offer (BBO) updates from Market Data Service.
     *
     * <h3>Message Flow</h3>
     * <pre>
     * MarketDataService → [mds.bbo] → Strategy Components
     * </pre>
     *
     * <h3>Message Types</h3>
     * - {@code MarketTick} with BBO price/quantity for each side
     *
     * <h3>Producers</h3>
     * - {@code MarketDataService} (bedrock-md)
     *
     * <h3>Consumers</h3>
     * - Strategy implementations (SimpleMaStrategy, SimpleMarketMakingStrategy)
     * - Risk management components
     * - Market monitoring/analytics
     *
     * <h3>Frequency</h3>
     * High frequency (100-1000 Hz typical, bursts up to 10k Hz)
     *
     * <h3>Latency Requirements</h3>
     * Critical path - target <100µs end-to-end
     */
    public static final String CHANNEL_MDS_BBO = "mds.bbo";
    public static final int STREAM_ID_MDS_BBO = 1001;

    /**
     * Channel: {@code mds.depth}
     * <p>
     * Full order book depth snapshots from Market Data Service.
     *
     * <h3>Message Flow</h3>
     * <pre>
     * MarketDataService → [mds.depth] → Strategy Components
     * </pre>
     *
     * <h3>Message Types</h3>
     * - {@code BookDelta} with full book levels (typically 5-20 levels per side)
     *
     * <h3>Producers</h3>
     * - {@code MarketDataService} (bedrock-md)
     *
     * <h3>Consumers</h3>
     * - Strategies requiring full book visibility (market making, arbitrage)
     * - Book reconstruction components
     * - Market microstructure analytics
     *
     * <h3>Frequency</h3>
     * Medium frequency (10-100 Hz typical)
     *
     * <h3>Latency Requirements</h3>
     * Important - target <500µs end-to-end
     */
    public static final String CHANNEL_MDS_DEPTH = "mds.depth";
    public static final int STREAM_ID_MDS_DEPTH = 1002;

    /**
     * Channel: {@code mds.trade}
     * <p>
     * Public trade ticks from Market Data Service.
     *
     * <h3>Message Flow</h3>
     * <pre>
     * MarketDataService → [mds.trade] → Strategy Components
     * </pre>
     *
     * <h3>Message Types</h3>
     * - {@code MarketTick} representing executed trades with price/quantity/side
     *
     * <h3>Producers</h3>
     * - {@code MarketDataService} (bedrock-md)
     *
     * <h3>Consumers</h3>
     * - Strategies tracking trade flow and volume profile
     * - VWAP/TWAP execution algorithms
     * - Trade analytics and surveillance
     *
     * <h3>Frequency</h3>
     * High frequency (100-1000 Hz typical, bursts up to 50k Hz on liquid instruments)
     *
     * <h3>Latency Requirements</h3>
     * Important - target <200µs end-to-end
     */
    public static final String CHANNEL_MDS_TRADE = "mds.trade";
    public static final int STREAM_ID_MDS_TRADE = 1003;

    // ==================== Pricing/Strategy Channels ====================

    /**
     * Channel: {@code pricing.target}
     * <p>
     * Quote targets from Strategy to Order Management System.
     *
     * <h3>Message Flow</h3>
     * <pre>
     * Strategy → [pricing.target] → OMS → Exchange
     * </pre>
     *
     * <h3>Message Types</h3>
     * - {@code QuoteTarget} with desired bid/ask prices and quantities
     * - Strategy intent before order placement
     *
     * <h3>Producers</h3>
     * - Strategy implementations (SimpleMaStrategy, SimpleMarketMakingStrategy)
     * - Pricing engines
     *
     * <h3>Consumers</h3>
     * - {@code OrderManagementService} (bedrock-adapter)
     * - Risk checks and position managers
     * - Quote audit trail
     *
     * <h3>Frequency</h3>
     * Medium frequency (10-100 Hz per strategy)
     *
     * <h3>Latency Requirements</h3>
     * Critical path - target <50µs from strategy to OMS
     */
    public static final String CHANNEL_PRICING_TARGET = "pricing.target";
    public static final int STREAM_ID_PRICING_TARGET = 2001;

    // ==================== Order Management System (OMS) Channels ====================

    /**
     * Channel: {@code oms.position}
     * <p>
     * Position updates from Order Management System.
     *
     * <h3>Message Flow</h3>
     * <pre>
     * OMS → [oms.position] → Strategy + Risk Components
     * </pre>
     *
     * <h3>Message Types</h3>
     * - {@code PositionUpdate} with current holdings per instrument
     * - Real-time position tracking after fills
     *
     * <h3>Producers</h3>
     * - {@code OrderManagementService} (bedrock-adapter)
     * - Position aggregation service
     *
     * <h3>Consumers</h3>
     * - Strategy implementations (for position-aware quoting)
     * - Risk management (position limits, exposure monitoring)
     * - P&L calculation engines
     *
     * <h3>Frequency</h3>
     * Low frequency (1-10 Hz, event-driven on fills)
     *
     * <h3>Latency Requirements</h3>
     * Important - target <1ms from fill to position update
     */
    public static final String CHANNEL_OMS_POSITION = "oms.position";
    public static final int STREAM_ID_OMS_POSITION = 3001;

    /**
     * Channel: {@code oms.order}
     * <p>
     * Order execution events from Order Management System.
     *
     * <h3>Message Flow</h3>
     * <pre>
     * OMS → [oms.order] → Strategy Components
     * </pre>
     *
     * <h3>Message Types</h3>
     * - {@code OrderAck} for order acknowledgments (NEW, REJECTED, CANCELED)
     * - {@code Fill} for partial/complete fills
     * - {@code ExecEvent} for execution updates
     *
     * <h3>Producers</h3>
     * - {@code OrderManagementService} (bedrock-adapter)
     * - Exchange gateway adapters (Binance, Bitget)
     *
     * <h3>Consumers</h3>
     * - Strategy implementations (order lifecycle tracking)
     * - Execution analytics
     * - Audit trail and compliance reporting
     *
     * <h3>Frequency</h3>
     * Medium frequency (10-100 Hz per strategy)
     *
     * <h3>Latency Requirements</h3>
     * Critical path - target <100µs from exchange ack to strategy
     */
    public static final String CHANNEL_OMS_ORDER = "oms.order";
    public static final int STREAM_ID_OMS_ORDER = 3002;

    // ==================== Management/Control Channels ====================

    /**
     * Channel: {@code mgmt.cmd}
     * <p>
     * Management and control commands.
     *
     * <h3>Message Flow</h3>
     * <pre>
     * Management API → [mgmt.cmd] → All Components
     * </pre>
     *
     * <h3>Message Types</h3>
     * - {@code ControlCommand} for start/stop/pause operations
     * - {@code ConfigUpdate} for runtime parameter changes
     * - {@code HealthCheck} for liveness monitoring
     *
     * <h3>Producers</h3>
     * - REST management API (bedrock-app)
     * - Monitoring/orchestration services
     *
     * <h3>Consumers</h3>
     * - All services (MarketDataService, StrategyService, OrderManagementService)
     * - Health monitoring endpoints
     *
     * <h3>Frequency</h3>
     * Very low frequency (<1 Hz, operator-initiated)
     *
     * <h3>Latency Requirements</h3>
     * Non-critical - target <100ms for control commands
     */
    public static final String CHANNEL_MGMT_CMD = "mgmt.cmd";
    public static final int STREAM_ID_MGMT_CMD = 9001;

    // ==================== Utility Methods ====================

    /**
     * Returns the channel name for a given stream ID.
     *
     * @param streamId Aeron stream ID
     * @return channel name, or "unknown" if stream ID is not recognized
     */
    public static String getChannelName(int streamId) {
        return switch (streamId) {
            case STREAM_ID_MDS_BBO -> CHANNEL_MDS_BBO;
            case STREAM_ID_MDS_DEPTH -> CHANNEL_MDS_DEPTH;
            case STREAM_ID_MDS_TRADE -> CHANNEL_MDS_TRADE;
            case STREAM_ID_PRICING_TARGET -> CHANNEL_PRICING_TARGET;
            case STREAM_ID_OMS_POSITION -> CHANNEL_OMS_POSITION;
            case STREAM_ID_OMS_ORDER -> CHANNEL_OMS_ORDER;
            case STREAM_ID_MGMT_CMD -> CHANNEL_MGMT_CMD;
            default -> "unknown";
        };
    }

    /**
     * Returns the stream ID for a given channel name.
     *
     * @param channelName channel name
     * @return stream ID, or -1 if channel name is not recognized
     */
    public static int getStreamId(String channelName) {
        if (channelName == null) return -1;
        return switch (channelName) {
            case CHANNEL_MDS_BBO -> STREAM_ID_MDS_BBO;
            case CHANNEL_MDS_DEPTH -> STREAM_ID_MDS_DEPTH;
            case CHANNEL_MDS_TRADE -> STREAM_ID_MDS_TRADE;
            case CHANNEL_PRICING_TARGET -> STREAM_ID_PRICING_TARGET;
            case CHANNEL_OMS_POSITION -> STREAM_ID_OMS_POSITION;
            case CHANNEL_OMS_ORDER -> STREAM_ID_OMS_ORDER;
            case CHANNEL_MGMT_CMD -> STREAM_ID_MGMT_CMD;
            default -> -1;
        };
    }

    /**
     * Checks if a stream ID is valid and registered.
     *
     * @param streamId stream ID to validate
     * @return true if stream ID is recognized
     */
    public static boolean isValidStreamId(int streamId) {
        String channelName = getChannelName(streamId);
        return !channelName.equals("unknown") && getStreamId(channelName) == streamId;
    }
}

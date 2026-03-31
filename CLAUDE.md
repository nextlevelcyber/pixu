# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Bedrock** is being refactored into a low-latency HFT Market Maker system written in Java 21. The target architecture (see `docs/QT_HFT_MM_ARCHITECTURE.md`) is a 4-process design with per-instrument strict ordering and tick-to-trade latency < 10μs.

**Current Phase:** Phase 5 (OMS Bus Wiring) complete. Single-process tick-to-trade loop fully wired. Bitget UTA market maker integration in progress. Phase 6 (multi-process Aeron split) or production hardening next.

**Key Architectural Decisions:**
- **No "Strategy" module** - Market Makers don't have directional Alpha. FairMid + QuoteConstruction merge into a "Pricing Engine". Current `bedrock-strategy` will be refactored.
- **Per-instrument ordering** - Each instrument has dedicated LMAX Disruptor, no global ordering required.
- **ExecGateway is OMS submodule** - Not an independent process, lifecycle bound to OMS.
- **HA via REST reconciliation** - No Chronicle Queue event replay. OMS restart recovers state via REST GET /openOrders + /positions.
- **Zero allocation hot path** - All hot path uses primitive long arrays, no heap allocation, no String operations.

## Agent Roles

See `AGENTS.md` for specialized agent definitions:
- **hft-analyst**: Exchange data mastery, microstructure analytics, latency forensics
- **hft-strategist**: HFT strategy design, alpha formulation, executable specifications

## Build & Run Commands

**Recommended:** Use shell scripts for all operations (they include required JVM flags and environment setup):
```bash
# Full build (all modules)
./scripts/build.sh

# Clean build, skip tests
./scripts/build.sh -c -t

# Build specific module (common, aeron, sbe, monitor, md, strategy, adapter, app)
./scripts/build.sh -m app

# Build with Docker image
./scripts/build.sh -d
```

**Direct Maven** (requires manual configuration of Maven home and local repo):
```bash
# Set your Maven paths first
MVN="/path/to/mvn --settings /path/to/settings.xml -Dmaven.repo.local=/path/to/repo"

# Build app with all dependencies, skip tests
$MVN clean package -DskipTests -pl bedrock-app -am

# Run tests
$MVN test -pl bedrock-aeron
$MVN -Dtest=InProcEventBusIntegrationTest test -pl bedrock-aeron
$MVN test -pl bedrock-oms
$MVN test -pl bedrock-pricing
$MVN test -pl bedrock-app -Dtest="OmsBusConsumerTest,OmsCoordinatorTest,PricingBusConsumerTest,PricingEngineCoordinatorTest"
```

**Requirements:** Java 21+, Maven 3.9.9.

**Maven paths:** Update the following for your environment when running Maven directly:
- `MAVEN_HOME`: Maven installation directory
- `MAVEN_SETTINGS`: Path to `settings.xml`
- `MAVEN_REPO_LOCAL`: Path to local Maven repository

Profile names: `development`, `production`, `test` (note: using `dev` as profile will NOT load the development config).

**Chronicle Queue startup:** Requires `--add-opens` JVM flags when run outside the start script — use the script to avoid Chronicle initialization failures.

## Run & Stop Commands

```bash
# Run application
./scripts/start.sh -p development -m FULL
# Background mode
./scripts/start.sh -p development -b
# Remote debug on port 5005
./scripts/start.sh -p development -d 5005
# With bus mode override
./scripts/start.sh --bus-mode IN_PROC|AERON_IPC|AERON_UDP

# Stop
./scripts/stop.sh
```

## Test Commands

```bash
# Run all tests
./scripts/test.sh

# Unit tests only
./scripts/test.sh -u

# Integration tests only
./scripts/test.sh -i

# Run specific test class
./scripts/test.sh -t BitgetMessageParserTest

# Test specific module
./scripts/test.sh -m aeron

# Tests with coverage
./scripts/test.sh -c

# Run tests with simulation exchange (no API keys needed)
./scripts/test.sh -Dbedrock.adapter.type=simulation
```

## Module Dependency Graph

```
bedrock-sbe          (SBE codegen, no deps)
bedrock-common       (event payloads, channel SPI, models; depends on bedrock-sbe)
bedrock-aeron        (EventBus, InstrumentEventBus, ChannelFactory; depends on bedrock-common)
bedrock-monitor      (MetricRegistry, MemoryMappedMetricRegistry; depends on chronicle-map)
bedrock-md-api       (MarketDataService interface, MarketTick, BookDelta)
bedrock-md           (feed impls: Binance, Bitget WS, L2OrderBook; depends on bedrock-md-api, bedrock-common)
bedrock-pricing      (PricingOrchestrator, FairMidPipeline, QuoteConstructPipeline; depends on bedrock-common)
bedrock-oms          (OrderStateMachine, OrderStore, ExecGateway, PositionTracker, RegionManager; depends on bedrock-common)
bedrock-strategy     (Strategy SPI, StrategyService, SimpleMaStrategy; LEGACY — to be refactored)
bedrock-adapter      (TradingAdapter SPI, Binance/Bitget/Simulation impls; depends on bedrock-common)
bedrock-app          (Spring Boot entry point, wires all modules; depends on everything)
```

Maven Enforcer rules prevent upward dependency leakage (e.g., `bedrock-md` cannot import from `bedrock-app`).

## Target Architecture (4-Process Design)

See `docs/QT_HFT_MM_ARCHITECTURE.md` for full details.

```
Process 1: MDS →归一化行情 → Aeron IPC → Process 2: Pricing Engine → QuoteTarget → Aeron IPC → Process 3: OMS
                                                                                                  ↓
                                                                                            ExecGateway (submodule)
                                                                                                  ↓
                                                                                            Exchange WS/REST
Process 4: Monitor (订阅所有 channel，只读，运维接口)
```

**Current Implementation Status:**

### Phase 0 (Base Layer) - ✅ COMPLETE
- SBE schema extended with 6 HFT event types: BboEvent(1100), DepthEvent(1101), TradeEvent(1102), QuoteTarget(2100), ExecEvent(3100), PositionEvent(3101)
- OrderIdGenerator: 64-bit snowflake (instrId 16b + seq 16b + ts 31b + side 1b), implemented at `bedrock-common/src/main/java/com/bedrock/mm/common/idgen/OrderIdGenerator.java`
- ChannelConstants: 7 per-instrument channel definitions at `bedrock-aeron/src/main/java/com/bedrock/mm/aeron/channel/ChannelConstants.java`
- InstrumentRegistry: Stream ID allocation (base 1000, increment 10000 per instrument)
- All core data structures use primitive long arrays (zero allocation hot path)

### Phase 1 (MDS Refactor) - ✅ COMPLETE
- **L2OrderBook**: Pure long[] array implementation, 20-level depth, binary search insert/delete, zero GC
  - Location: `bedrock-md/src/main/java/com/bedrock/mm/md/book/L2OrderBook.java`
  - Operations: applyDelta, applySnapshot, rebuild
- **SequenceValidator**: Per-symbol O(1) validation with 5 states (VALID/NEED_SNAPSHOT/GAP/DUPLICATE/REWIND)
  - Location: `bedrock-md/src/main/java/com/bedrock/mm/md/book/SequenceValidator.java`
  - Uses primitive long[] array indexed by symbolId, no HashMap
- **RestSnapshotFetcher**: Binance & Bitget REST snapshot fetchers with Jackson streaming parser
  - Location: `bedrock-md/src/main/java/com/bedrock/mm/md/book/RestSnapshotFetcher.java`
  - Triggered on GAP_DETECTED or REWIND_DETECTED
- **InstrumentEventBus**: Per-instrument LMAX Disruptor with 1024 buffer, strategy-first dispatch
  - Location: `bedrock-aeron/src/main/java/com/bedrock/mm/aeron/bus/instrument/InstrumentEventBus.java`
  - Single consumer thread per instrument ensures strict ordering
- **InstrumentEventBusCoordinator**: Lifecycle manager for per-instrument buses
  - Location: `bedrock-app/src/main/java/com/bedrock/mm/app/runtime/bus/instrument/InstrumentEventBusCoordinator.java`
- **CrossInstrumentBridge**: Cross-instrument event subscription support
  - Location: `bedrock-aeron/src/main/java/com/bedrock/mm/aeron/bus/instrument/CrossInstrumentBridge.java`

### Phase 2 (OMS Rebuild) - ✅ COMPLETE
Target: Build OrderStateMachine, OrderStore, ExecGateway, Position tracking, Region management.

**Implemented Components:**

1. **OrderStateMachine** - `bedrock-oms/src/main/java/com/bedrock/mm/oms/statemachine/OrderStateMachine.java`
   - State transitions: PENDING_NEW → OPEN → FILLED/CANCELLED/REJECTED
   - 19 unit tests (OrderStateMachineTest) - all passing

2. **OrderStore** - `bedrock-oms/src/main/java/com/bedrock/mm/oms/store/OrderStore.java`
   - Dual indexing: Agrona Long2ObjectHashMap (internal orderId) + Long2LongHashMap (exchOrderId reverse lookup)
   - PriceRegion[] for region-based order tracking
   - 10 unit tests (OrderStoreTest) - all passing

3. **ExecGateway (Binance)** - `bedrock-oms/src/main/java/com/bedrock/mm/oms/exec/binance/BinanceExecGateway.java`
   - Private WebSocket client: `BinancePrivateWsClient.java`
   - REST reconciliation: `RestReconciliation.java`
   - Order placement/cancellation via REST
   - 7 unit tests (BinanceExecGatewayTest) - all passing

4. **PositionTracker** - `bedrock-oms/src/main/java/com/bedrock/mm/oms/position/PositionTracker.java`
   - Hot path < 500ns, zero allocation
   - Weighted average entry price, unrealized PnL calculation
   - 11 unit tests (PositionTrackerTest) - all passing

5. **HedgeManager** - `bedrock-oms/src/main/java/com/bedrock/mm/oms/position/HedgeManager.java`
   - Delta hedging with threshold-based triggering
   - Rate limiting (max 1 hedge per 60s)
   - 12 unit tests (HedgeManagerTest) - all passing

6. **RegionManager** - `bedrock-oms/src/main/java/com/bedrock/mm/oms/region/RegionManager.java`
   - Diff algorithm: QuoteTarget vs OrderStore → OrderActions
   - Two-sided and single-sided quoting support
   - 6 unit tests (RegionManagerTest) - all passing

7. **RegionBoundaryCalculator** - `bedrock-oms/src/main/java/com/bedrock/mm/oms/region/RegionBoundaryCalculator.java`
   - Tick-aligned boundary calculation (fairMid + ratios)
   - 7 unit tests (RegionBoundaryCalculatorTest) - all passing

8. **RiskGuard** - `bedrock-oms/src/main/java/com/bedrock/mm/oms/risk/RiskGuard.java`
   - Pre-execution checks: price deviation, order/position limits, rate limiting
   - Hot-updatable parameters (volatile fields)
   - 14 unit tests (RiskGuardTest) - all passing

**Test Results:** 102 tests, 0 failures, 0 errors

**Key OMS Design Points:**
- ExecGateway is OMS submodule (not separate process)
- Private WS + REST reconciliation (no Chronicle Queue replay)
- Region-based order maintenance: PriceRegion tracks orders in price bands relative to fairMid
- OrderStore uses Agrona Long2ObjectHashMap (primitive long key, no boxing)
- Restart recovery: REST GET /openOrders → rebuild OrderStore → optional cancelAll() → resume
- OrderIdGenerator: 64-bit snowflake (instrumentId 16b + seq 16b + ts 31b + side 1b)

### Phase 3 (Pricing Engine) - ✅ COMPLETE
New module: `bedrock-pricing`. 65 unit tests, 0 failures.

- **PricingOrchestrator** - `bedrock-pricing/src/main/java/com/bedrock/mm/pricing/PricingOrchestrator.java`
  - `onBbo()` triggers full recompute via FairMidPipeline → QuoteConstructPipeline; publishes QuoteTarget via callback
  - `onTrade()` / `onPosition()` / `onIndexPrice()` update InstrumentContext only (no recompute)
- **FairMidPipeline** - seed = `(bid+ask)>>>1`, chains component offsets
- **QuoteConstructPipeline** - per-side component arrays, fills `target.bidPrice/askPrice/bidSize/askSize`
- **FairMid components**: `DepthFollowComponent` (VWAP), `IndexFollowComponent` (weight × delta), `TradeFlowComponent` (EWMA direction)
- **QuoteConstruct components**: `BaseSpreadComponent`, `VolatilitySpreadComponent`, `DeltaSkewComponent`, `PositionBoundComponent`
- **PricingOrchestratorFactory** - `createDefault()` wires standard config (halfSpread=$0.10, softLimit=8 BTC)

### Phase 4 (Pricing Bus Wiring) - ✅ COMPLETE
21 new tests pass (L2OrderBookAccessorTest 8 + PricingBusConsumerTest 6 + PricingEngineCoordinatorTest 7).

- **BboPayload** - `bedrock-common/.../event/BboPayload.java`: BBO event payload (1e-8 fixed-point longs)
- **PricingBusConsumer** - `bedrock-app/.../bus/PricingBusConsumer.java`: routes `BBO`→`onBbo()`, `MARKET_TICK`→`onTrade()`, `POSITION_UPDATE`→`onPosition()`; dead-letters on exception
- **PricingEngineCoordinator** - `bedrock-app/.../bus/PricingEngineCoordinator.java`: `@ConditionalOnProperty("bedrock.pricing.enabled")`; creates per-symbol PricingOrchestrators, registers PricingBusConsumer on per-instrument bus
- **BBO publish flow**: `BinanceFeed`/`BitgetV3Feed` → `mdService.publishBbo()` → `EventBus(BBO)` → `PricingBusConsumer` → `orchestrator.onBbo()` → `EventBus(QUOTE_TARGET)`

### Phase 5 (OMS Bus Wiring) - ✅ COMPLETE
13 new tests pass (OmsBusConsumerTest 6 + OmsCoordinatorTest 7).

- **PositionPayload** - `bedrock-common/.../event/PositionPayload.java`: POSITION_UPDATE event payload
- **OmsBusConsumer** - `bedrock-app/.../bus/OmsBusConsumer.java`: routes `QUOTE_TARGET` → `RegionManager.diff()` → `RiskGuard.preCheck()` → `ExecGateway.placeOrder/cancelOrder`; dead-letters on exception
- **OmsCoordinator** - `bedrock-app/.../bus/OmsCoordinator.java`: `@ConditionalOnProperty("bedrock.oms.enabled")`; manages per-symbol `InstrumentOmsState`; handles `ExecEvent` → `OrderStateMachine.transition()` → `PositionTracker.onFill()` → publishes `POSITION_UPDATE`; `exchange=simulation` uses no-op gateway for testing

**Complete single-process tick-to-trade loop:**
```
BinanceFeed → BBO → PricingBusConsumer → onBbo() → QUOTE_TARGET
  → OmsBusConsumer → RegionManager.diff() → ExecGateway.placeOrder()
  → ExecEvent → OrderStateMachine → PositionTracker.onFill()
  → POSITION_UPDATE → PricingBusConsumer → orchestrator.onPosition()  ← delta skew feedback
```

### Legacy EventBus (Coexists with Per-Instrument Bus)
The original global EventBus coexists with per-instrument buses for backward compatibility:
- `InProcEventBus` / `AeronEventBus` — global single-stream bus (used as fallback)
- `EventBusCoordinator` — global bus coordinator
- Per-instrument buses (`InstrumentEventBusCoordinator`) take priority when `bedrock.bus.perInstrumentEnabled=true`

## Reference Documentation

- **`docs/QT_HFT_MM_ARCHITECTURE.md`** - Target architecture blueprint (crypto HFT MM, 4-process design, tick-to-trade <10μs)
- **`docs/QT_CLAUDE_ANOTHER.md`** - Production MM system reference (LMAX Disruptor pipeline, TradeUnit, KBChange pricing)

**Key Design Principles from QT_HFT_MM_ARCHITECTURE.md:**

1. **Market Makers have no "Strategy" module** - No directional Alpha. The decision chain is:
   ```
   FairMid Calculation → Quote Construction → Order Maintenance
   (Pricing Engine)                           (OMS)
   ```

2. **Per-Instrument Ordering, Not Global** - Each instrument is strictly ordered internally, instruments are independent and can run in parallel.

3. **Hot Path Constraints:**
   - No heap allocation (use primitive arrays, object pools)
   - No blocking (async all I/O)
   - No syscalls except network I/O
   - No String operations

4. **HA via REST Reconciliation:**
   ```
   OMS restart → REST GET /openOrders → rebuild OrderStore
              → REST GET /positions  → rebuild PositionTracker
              → optional cancelAll() → resume normal operation
   Total time: < 2 seconds
   ```

5. **Long-based Order ID:**
   ```
   Bit 63-48: instrumentId (16 bits)
   Bit 47-32: sequence (16 bits, per-millisecond counter)
   Bit 31-1:  timestamp in milliseconds (31 bits, ~24 day range)
   Bit 0:     side (0=bid, 1=ask)
   ```

6. **Region-Based Quoting:**
   - PriceRegion: price band relative to fairMid (e.g., -0.1% to -0.2%)
   - Each region tracks orderIds in that price range
   - RegionManager compares QuoteTarget to current orders → generates NEW/CANCEL actions

## SBE Message Schema

Defined in `bedrock-sbe/src/main/resources/sbe/bedrock-messages.xml`.

**Legacy Events (to be deprecated):**
| Message | Stream ID | Notes |
|---|---|---|
| `MarketTick` | 1001 | Legacy global market tick, replaced by BboEvent per-instrument |
| `BookDelta` | 1002 | Legacy global book delta, replaced by DepthEvent per-instrument |
| `OrderCommand` | 2001 | Legacy order command |
| `OrderAck` | 3001 | Legacy order ack |
| `Fill` | 3002 | Legacy fill event |

**Phase 0 HFT Events (SBE schema) + Phase 4/5 Bus EventTypes:**
| Message / EventType | Stream ID | Key Fields | Usage |
|---|---|---|---|
| `BboEvent` (SBE) / `BBO` | 1100 | `instrumentId`, `bidPrice`, `askPrice`, `bidSize`, `askSize`, `seqId`, `recvNanos`, `exchNanos` | MDS → Pricing: best bid/offer update |
| `DepthEvent` (SBE) | 1101 | `instrumentId`, `levels`, `bidPrices[]`, `bidSizes[]`, `askPrices[]`, `askSizes[]`, `seqId` | L2 order book snapshot |
| `TradeEvent` (SBE) / `MARKET_TICK` | 1102 | `instrumentId`, `price`, `size`, `isBuy`, `seqId`, `recvNanos`, `exchNanos` | MDS → Pricing: public trade tick |
| `QuoteTarget` (SBE+POJO) / `QUOTE_TARGET` | 2100 | `instrumentId`, `regionIndex`, `bidPrice`, `askPrice`, `bidSize`, `askSize`, `fairMid`, `flags`, `seqId` | Pricing → OMS: quote maintenance target |
| `ExecEvent` (SBE) | 3100 | `instrumentId`, `internalOrderId`, `exchOrderId`, `type`, `fillPrice`, `fillSize`, `remainSize`, `isBid`, `seqId` | ExecGateway → OMS: execution update (internal callback) |
| `PositionEvent` (SBE) / `POSITION_UPDATE` | 3101 | `instrumentId`, `netPosition`, `delta`, `unrealizedPnl`, `seqId` | OMS → Pricing: position feedback for delta skew |

**Bus payload classes (JSON codec, `bedrock-common/.../event/`):**
- `BboPayload` — used for `BBO` EventEnvelope
- `QuoteTarget` (pricing model) — used for `QUOTE_TARGET` EventEnvelope
- `PositionPayload` — used for `POSITION_UPDATE` EventEnvelope

**Data Encoding:**
- All prices and quantities are fixed-point long (scale 1e-8): `(long)(doubleValue * 1e8)`
- Use `Double.doubleToRawLongBits()` / `Double.longBitsToDouble()` for IEEE 754 bit-exact encoding
- `instrumentId` is int (not String symbol lookup)
- SBE templates are fixed-width for hot path performance

## Key Interfaces & Extension Points

| Interface | Location | Purpose |
|---|---|---|
| `Strategy` | `bedrock-strategy` | Implement to add a new trading strategy |
| `TradingAdapter` | `bedrock-adapter` | Implement to add a new exchange connector |
| `MarketFeed` | `bedrock-md/feed` | Implement to add a new data source |
| `ChannelProvider` | `bedrock-common/channel` | SPI for new transport backends |
| `EventBus` | `bedrock-aeron/bus` | Swap transport layer |

**Built-in implementations:**
- Strategies: `SimpleMaStrategy` (MA crossover), `SimpleMarketMakingStrategy`
- Adapters: `BinanceTradingAdapter`, `BitgetTradingAdapter`, `SimulationTradingAdapter`
- Feeds: `BinanceFeed`, `BitgetV3Feed`

**Adding a new exchange adapter:**
1. Implement `TradingAdapter` interface in `bedrock-adapter`
2. Add configuration properties to `application.yml`
3. Configure credentials via environment variables (`${EXCHANGE_API_KEY}`, etc.)
4. Set `bedrock.adapter.type: <your-adapter>` in application.yml

## Configuration

All settings are under the `bedrock.*` namespace in `bedrock-app/src/main/resources/application.yml`. Spring profiles (`development`, `production`, `test`) override specific sections at the bottom of that file.

**Key switches:**
- `bedrock.bus.mode`: `IN_PROC` | `AERON_IPC` | `AERON_UDP` — controls single vs. multi-process deployment
- `bedrock.bus.payloadCodec`: `JSON` | `SBE` — payload encoding (wire envelope format is always binary)
- `bedrock.bus.perInstrumentEnabled`: `true`/`false` — use per-instrument LMAX Disruptor buses
- `bedrock.md.simulation.enabled`: enable the built-in market data simulator (no exchange connection needed)
- `bedrock.adapter.type`: `binance` | `bitget` | `simulation`
- `bedrock.pricing.enabled`: `false` (default) — enable PricingEngineCoordinator; set `true` to start Pricing Engine
- `bedrock.pricing.symbols`: list of symbols to run pricing for (e.g., `[BTCUSDT, ETHUSDT]`)
- `bedrock.oms.enabled`: `false` (default) — enable OmsCoordinator; set `true` to start OMS
- `bedrock.oms.symbols`: list of symbols to manage orders for
- `bedrock.oms.exchange`: `binance` | `simulation` (no-op gateway, safe for testing)
- `bedrock.strategy.enabled`: `true`/`false` — enable StrategyService
- `bedrock.md.bitget.enabled`: enable Bitget V3 market data feed
- `bedrock.md.bitget.symbols`: list of symbols for Bitget (e.g., `[BTCUSDT, ETHUSDT]`)

**Exchange credentials** are injected via environment variables:
- `BINANCE_API_KEY`, `BINANCE_SECRET_KEY`
- `BITGET_API_KEY`, `BITGET_SECRET_KEY`, `BITGET_PASSPHRASE`

**Management/actuator API:** Port 8081 (configurable via `bedrock.server.managementPort`). REST API base path `/api/v1`.

**Key endpoints:**
- `GET /api/v1/status` — system health
- `GET /api/v1/md/status` — market data service status
- `POST /api/v1/md/start|stop` — start/stop market data feeds
- `GET /api/v1/strategies` — list strategies
- `POST /api/v1/strategies/{name}/start|stop` — control individual strategies
- `GET /api/v1/adapters/balances|positions` — exchange account info

## Known Issues & Gotchas

- **Timestamp units**: Simulation feed emits nanosecond timestamps; real feeds use milliseconds. Kline/candlestick bucket rollover depends on millisecond timestamps — mixing units breaks bar aggregation.
- **Profile name**: The active profile must be `development` (not `dev`) for the development config block to apply.
- **Chronicle startup**: `MemoryMappedMetricRegistry` requires `--add-opens` on Java 11+. Always use `./scripts/start.sh` rather than running the JAR directly.
- **chronicle-core version pin**: `chronicle-map 3.24ea1` pulls `chronicle-core 2.24ea1` transitively, but only its POM (no JAR) is in the local repo. Root `pom.xml` pins `chronicle-core` to `2.24ea2` in `<dependencyManagement>` to fix this. Do not remove this pin.
- **Dead-letter channel**: Decode failures and unhandled exceptions in bus consumers are routed to `DeadLetterChannel` rather than crashing the loop. Check dead-letter records when events go missing.
- **SBE template mismatch**: `SbeEventSerde` fails fast with an explicit error on wrong-type deserialization (template-id guardrail); do not rely on fallback JSON for high-frequency event types.
- **Two QuoteTarget classes**: `bedrock-pricing/src/main/java/com/bedrock/mm/pricing/model/QuoteTarget.java` is the canonical POJO used on the bus. `bedrock-oms/src/main/java/com/bedrock/mm/oms/model/QuoteTarget.java` is a placeholder — imports must use the pricing version in bus consumers.
- **ExecEvent threading**: `BinanceExecGateway` calls `execEventConsumer.accept()` from async HTTP threads. In production, this should be posted back to the Disruptor; currently handled in-place (acceptable for Phase 5, must fix before live trading).
- **BedrockApplicationTest failures**: 5 tests fail with Mockito `MockMaker` plugin init error — pre-existing, unrelated to Phases 3-5. All other bedrock-app tests pass.
- **OmsCoordinator no bus warning**: When started without an `InstrumentEventBusCoordinator` (e.g., in unit tests), logs "no event bus available" — expected behavior, not an error.

## Troubleshooting

**Chronicle Queue initialization failures:**
- Use `./scripts/start.sh` instead of `java -jar` — required `--add-opens` JVM flags are set in the script

**Mockito MockMaker errors in tests:**
- Pre-existing issue in `BedrockApplicationTest` (5 failing tests) — unrelated to Phases 3-5, safe to ignore

**"no event bus available" warnings:**
- Expected when running `OmsCoordinator` without `InstrumentEventBusCoordinator` (e.g., unit tests without full app context)

**Events going missing:**
- Check `DeadLetterChannel` for decode failures or unhandled exceptions in bus consumers
- Verify `bedrock.bus.payloadCodec` matches the expected format (JSON vs SBE)

## Performance Constraints

- **No heap allocation on the hot path** — use Agrona `DirectBuffer` / object pools; avoid `new` in `onMarketTick` / `onBookDelta` handlers.
- SBE codec operates on `DirectBuffer` — do not convert to String or use reflection inside codec methods.
- When strategy detects backlog / lag, it must suspend command emission and only update internal state — never block the bus loop thread.
- Prices and quantities are fixed-point integers (scale 1e-8). Never use `double` arithmetic for financial calculations.
- Important events (plan/order/ack/fill) are written asynchronously to Chronicle Queue for replay — avoid adding synchronous I/O on the hot path.

## Coding Conventions

- Java 21, 4-space indentation, standard naming: `PascalCase` (classes/interfaces), `camelCase` (methods/fields), `UPPER_SNAKE_CASE` (constants).
- All packages under `com.bedrock.mm.<module>`.
- Prefer explicit, small classes in hot paths; follow existing primitive-heavy patterns for zero-allocation code.
- Tests: `*Test` for unit tests, `*IntegrationTest` for integration tests. Add tests in the same module as the changed code.

## Commit Conventions

Use clear imperative messages scoped to the module, e.g.:
- `pricing: clamp spread under high volatility`
- `oms: add OrderStateMachine cancel-reject transition test`

PRs must include: scope summary + affected modules, exact verification commands run, any new env var requirements (`BINANCE_*`, `BITGET_*`), and API/log screenshots when changing runtime or ops behavior.

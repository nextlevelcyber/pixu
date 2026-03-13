# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

Maven home: `/Users/kaymen/tool/apache-maven-3.9.9`
Local repository: `/Users/kaymen/tool/repository`

```bash
# Build entire project (tests skipped by default via maven.test.skip=true)
/Users/kaymen/tool/apache-maven-3.9.9/bin/mvn clean package -pl mm-server -am

# Build with tests enabled
/Users/kaymen/tool/apache-maven-3.9.9/bin/mvn clean package -pl mm-server -am -Dmaven.test.skip=false

# Run a single test class
/Users/kaymen/tool/apache-maven-3.9.9/bin/mvn test -pl engine -Dtest=MaintainRegionStrategyTest -Dmaven.test.skip=false

# Run a single test in mm-server
/Users/kaymen/tool/apache-maven-3.9.9/bin/mvn test -pl mm-server -Dtest=MarketMakingApplicationTest -Dmaven.test.skip=false

# Run the main application (requires Apollo config server and ZooKeeper)
java -jar mm-server/target/mm-server-*.jar

# Run the REST API server
java -jar exchange-engine-rest/target/exchange-engine-rest-*.jar
```

## Module Structure

This is a Maven multi-module project for a crypto exchange primary market-making engine:

- **client** — Exchange connectivity layer. Per-exchange WebSocket/REST clients implementing `IExecClient`, `IQuoteConsumer`, `IUserConsumer`. Exchanges: Binance, Bitget, BitgetV2, Bybit, CoinTR, Gate, HashKey, Huobi, Kraken, KuCoin, MEXC, OKX, OSL, OslGlobal, Solana.
- **core** — Shared utilities: Redis caching (`RedisService`), ID generation (`SnowflakeGenerator`, `TimeBasedIDGen`), InfluxDB metrics, compression, validation rules.
- **accounting** — Order book state management. `OrderBook` tracks open orders per `BookRegion` (price band). Services: `BalanceService`, `PositionService`, `DeltaService`, `QuoteService`, `OrderBookService`.
- **engine** — Core trading engine logic (no Spring boot main). Contains:
  - `tradeunit/` — `TradeUnit` (the central data model) and `TradeUnitManager` (lifecycle orchestrator)
  - `ringbuffer/` — LMAX Disruptor event pipeline
  - `datafeed/` — Market data and user data feeds
  - `strategy/` — Trading strategies
  - `service/` — Price computation services (KBChange, volatility, fairmid)
  - `service/component/` — Pluggable price-adjustment components
  - `risk/` — Risk rules
  - `exec/` — Order execution
  - `ha/` — High-availability via Chronicle Queue
  - `handle/` — REST/HTTP data handlers per exchange
- **exchange-engine-facade** — Feign client interfaces exposing fair-mid price APIs to other services.
- **exchange-engine-rest** — Spring Boot REST app (`EngineRestApplication`) serving `TradeContextController` for fair-mid price queries.
- **mm-server** — Spring Boot main app (`MarketMakingApplication`). Depends on `engine`. Wires Apollo config, ZooKeeper HA, and starts `TradeUnitManager`.

## Architecture: Event Pipeline

The core processing loop for each `TradeUnit` is a single-threaded LMAX Disruptor pipeline:

```
DataFeed (WebSocket / HTTP / Timer)
    → DisruptorProcessor.publishEvent()
    → CombinedEngineHandler (single thread):
        1. SequenceIdHandler   — assigns sequence IDs
        2. AccountingHandler   — updates OrderBook, balance, position, delta
        3. StrategyHandler     — runs active strategies, produces order actions
        4. RiskHandler         — applies risk rules (rate limits, open order count)
        5. ExecHandler         — sends place/cancel orders via ExecClientManager
        6. CleanHandler        — clears event references
```

All handlers run sequentially in the same Disruptor thread per `TradeUnit`. Only the `AccountingHandler` runs for standby instances; `StrategyHandler`, `RiskHandler`, `ExecHandler` are skipped when `tradeUnit.isActive() == false`.

## Key Concepts

**TradeUnit** — The unit of scheduling. Each `TradeUnit` has one market-making exchange/symbol pair (`mmExchange`/`mmSymbol`) and one or more hedge exchange/symbol pairs. All PnL is tracked at this level. One process can run multiple `TradeUnit` instances.

**Apollo Config** — All runtime parameters are loaded from Apollo (Ctrip's config center). `MarketMakingProperties` parses Apollo namespaces into `MmProperties` objects. `ApolloManager` provides live-updating accessors for trading/hedging on/off switches.

**KBChange Services** — A family of services (`DeltaSensingKBChangeService`, `VolatilitySensingKBChangeService`, `TradeSensingKBChangeService`, etc.) that compute bid/ask spread adjustments (KB = spread basis) based on market signals.

**Components** — Pluggable price-shaping modules (`DepthFollowComponent`, `SpreadComponent`, `PositionChangeComponent`, `IndexFollowComponent`, etc.) that each produce a bid/ask offset applied to the final quoted price.

**HA** — High-availability via `ChronicleQueueEventStore` (persisted ring buffer). Active instance writes events; standby instance replays. ZooKeeper (`MasterSlaveZKConfig`) manages active/standby leader election.

**Dry Run Modes** — `execClientDryRun=true` prevents real orders from being placed. `hedgeDryRun=true` prevents hedge orders. Both default to `true` in non-production (`Constants.isProd == false`).

## Configuration

- Java 11 source/target (despite the repo name `jdk17`)
- Maven home: `/Users/kaymen/tool/apache-maven-3.9.9`
- Maven local repository: `/Users/kaymen/tool/repository`
- Tests are skipped by default (`maven.test.skip=true` in root pom)
- The project depends on internal `upex-*` and `quant-*` artifacts from a private Maven registry
- Runtime requires: Apollo Config Server, ZooKeeper, Redis

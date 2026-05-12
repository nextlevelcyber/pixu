# MDS Current Status

日期：2026-05-07

## Current State

MDS 已完成基础行情链路：`MarketDataServiceImpl`、`FeedManager`、Binance/Bitget public feed、L2 order book、sequence validation、REST snapshot fetcher、MarketTick/BookDelta/BBO 事件发布。

当前代码可以从 Binance/Bitget WebSocket 解析 ticker/depth，维护每 symbol 的 `L2OrderBook`，在 top-of-book 完整时发布 `BBO`。`ApplicationService` 启动顺序是先 bus、pricing、OMS，再启动 feeds 和 MDS，因此消费者理论上会先注册。

默认配置下 `bedrock.md.enabled=true`、Binance public/private feed enabled、simulation disabled、pricing/OMS disabled。也就是说默认 FULL 启动会拉真实 Binance 行情和 private feed，但不会启动新的 Pricing/OMS 链路。

## Gaps

- MDS 发布路径主要还是全局 `EventBus`/legacy channel；尚未优先路由到 `InstrumentEventBusCoordinator`。
- `publishBbo` 只有 `EventBus` 路径，没有 legacy fallback 或独立 BBO channel。
- Binance/Bitget sequence 处理仍偏简化，缺少交易所原生序列语义与 snapshot/delta 缓冲闭环。
- Snapshot rebuild 后没有立即发布 BBO。
- Snapshot 线程会并发修改 `L2OrderBook` 与 `SequenceValidator`，和 WebSocket 回调线程之间缺少明确串行化边界。
- 可观测性缺少 BBO、gap、rewind、snapshot latency/failure、book stale、publish drop 等指标。
- MDS 模块测试未形成完整入口验证；当前 reactor 测试会先被上游模块问题挡住。

## Verification Notes

尝试运行：

```bash
/Users/kaymen/tool/apache-maven-3.9.9/bin/mvn -Dmaven.repo.local=/Users/kaymen/workspace/pixu/.m2 -pl bedrock-md -am test -DfailIfNoTests=false
```

结果：构建在 `bedrock-common` 测试阶段失败，未进入 `bedrock-md`。失败测试是 `OrderIdGeneratorTest.testTimestampExtraction` 和 `OrderIdGeneratorTest.testPerformanceBenchmark`。

尝试运行：

```bash
/Users/kaymen/tool/apache-maven-3.9.9/bin/mvn -Dmaven.repo.local=/Users/kaymen/workspace/pixu/.m2 -pl bedrock-md -am install -DskipTests
```

结果：构建在 `bedrock-aeron` 编译阶段失败，日志显示 javac 使用 `release 11`，但代码包含 Java 14+ switch expression 和 Java 16+ record。根 `pom.xml` 声明 Java 21，说明 Maven 编译配置实际生效路径需要修正。

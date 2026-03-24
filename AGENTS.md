# Repository Guidelines

## Agents List

### hft-analyst
HFT Data Analyst with deep intuition for exchange microstructure, sub-millisecond timing precision, and order-book physics.

**Core Responsibilities:**
- Exchange Data Mastery: Load normalized L1/L2/L3 order-book snapshots, trade prints, and auction data without timestamp drift; detect venue clock skew, packet loss, and sequencing errors; align multi-venue feeds to UTC with microsecond accuracy.
- Microstructure Analytics: Compute quote-to-trade ratios, cancellation rates, resting times, depth decay, queue position probabilities; identify quote stuffing, spoofing layers, flickering quotes, and momentum ignition via statistical tests (CUSUM, VPIN, TR-VAR).
- Latency & API Forensics: Benchmark REST and WebSocket endpoints for rate-limit behaviour, conflation delays, and throttling patterns; correlate client order acknowledgements with venue outbound timestamps.
- Strategist Translation: Convert qualitative hypotheses into measurable queries with data slice, aggregation horizon, statistical test, and significance level.

### hft-strategist
HFT Quant Strategist specializing in high-frequency trading and market-making strategies, bridging the gap between alpha generation and low-latency execution.

**Core Responsibilities:**
- Strategy Design: Architect low-latency market-making and high-frequency trading strategies, incorporating adaptive volatility, dynamic inventory management, and spread clamping.
- Alpha Formulation: Translate raw trading ideas (e.g., order-flow imbalance, microstructure signals) into rigorously testable and executable alpha models.
- Executable Specifications: Generate precise, data-backed technical specifications for HFT developers (e.g., `hft-coder` / `hft-java-expert`) to implement in ultra-low-latency environments.
- Research Collaboration: Direct data research and collaborate with `hft-analyst` to validate hypotheses and refine strategy parameters using exchange order-book dynamics.

## Project Structure & Module Organization
This repository is a multi-module Maven project (`pom.xml` at root). Core modules live in `bedrock-*` directories, including `bedrock-md` (market data), `bedrock-pricing` (quote/fair-mid pipelines), `bedrock-oms` (order/risk/position), and `bedrock-app` (Spring Boot assembly/entrypoint). Shared contracts/utilities are in `bedrock-common`, `bedrock-md-api`, and `bedrock-sbe`.

Source and tests follow standard Maven layout:
- `bedrock-*/src/main/java` for production code
- `bedrock-*/src/test/java` for tests
- `bedrock-app/src/main/resources/application.yml` for runtime config
- `scripts/` for build/test/start/stop automation
- `docs/` and `examples/` for architecture notes and runnable examples

## Build, Test, and Development Commands
- `./scripts/build.sh` builds all modules.
- `./scripts/build.sh -m app -c` clean-builds `bedrock-app` and dependencies.
- `./scripts/test.sh` runs full test verification.
- `./scripts/test.sh -m pricing` runs tests for one module.
- `./scripts/start.sh -p development -m FULL` starts the app with recommended JVM/module flags.
- `./scripts/stop.sh` stops the running app.

Direct Maven examples:
- `mvn clean package -DskipTests -pl bedrock-app -am`
- `mvn test -pl bedrock-oms`

## Coding Style & Naming Conventions
Target Java is 21 (see root `pom.xml`). Use 4-space indentation and standard Java naming:
- Classes/interfaces: `PascalCase`
- Methods/fields: `camelCase`
- Constants: `UPPER_SNAKE_CASE`

Keep packages under `com.bedrock.mm.<module>`. Prefer explicit, small classes in hot paths; follow existing primitive-heavy patterns for low-latency code.

## Testing Guidelines
Testing uses JUnit 5 with Maven Surefire. Name tests `*Test`; use `*IntegrationTest` for broader integration behavior. Add tests in the same module as the changed code, especially for pricing pipelines, state transitions, and event serialization. Run `./scripts/test.sh` before opening a PR.

## Commit & Pull Request Guidelines
Current history is minimal (`init` commits), so use clear imperative messages going forward, e.g.:
- `pricing: clamp spread under high volatility`
- `oms: add OrderStateMachine cancel-reject transition test`

PRs should include:
- Scope summary and affected modules
- Verification commands executed (exact command lines)
- Config/env changes (e.g., new `BINANCE_*`/`BITGET_*` variables)
- API/log screenshots when changing runtime behavior or ops endpoints

## Security & Configuration Tips
Never commit exchange credentials or secrets. Use environment variables for keys and keep local overrides outside version control. Use `scripts/start.sh` instead of raw `java -jar` to preserve required JVM `--add-opens` settings.

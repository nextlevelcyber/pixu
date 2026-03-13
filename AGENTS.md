# Repository Guidelines

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

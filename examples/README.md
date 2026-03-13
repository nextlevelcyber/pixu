# Bedrock Market Making System - Examples

This directory contains example scripts and configurations to help you get started with the Bedrock Market Making System.

## Quick Start

### 1. Build the Project

```bash
# Build the entire project
./scripts/build.sh

# Or build with clean
./scripts/build.sh -c

# Or build without tests (faster)
./scripts/build.sh -t
```

### 2. Run Simulation Demo

The easiest way to get started is with the simulation demo:

```bash
./examples/run-simulation.sh
```

This will:
- Start the application in simulation mode
- Generate simulated market data for BTCUSDT, ETHUSDT, ADAUSDT
- Run a simple market making strategy
- Demonstrate API calls
- Monitor the system for 60 seconds

### 3. Test APIs

Test all available APIs:

```bash
./examples/api-test.sh
```

Or run specific test types:

```bash
# Basic API tests only
./examples/api-test.sh -b

# Performance tests
./examples/api-test.sh -p

# Load tests
./examples/api-test.sh -l

# Error scenario tests
./examples/api-test.sh -e
```

## Manual Operation

### Start the Application

```bash
# Start in development mode (default)
./scripts/start.sh

# Start in production mode
./scripts/start.sh -p production -m FULL

# Start with debug enabled
./scripts/start.sh -d 5005

# Start in background
./scripts/start.sh -b
```

### Stop the Application

```bash
# Graceful shutdown
./scripts/stop.sh

# Force kill
./scripts/stop.sh -f
```

### Run Tests

```bash
# Run all tests
./scripts/test.sh

# Run unit tests only
./scripts/test.sh -u

# Run integration tests only
./scripts/test.sh -i

# Run tests with coverage
./scripts/test.sh -c

# Test specific module
./scripts/test.sh -m app
```

## Application Modes

The Bedrock system supports different operational modes:

### SIMULATION (Default)
- Simulated market data
- Paper trading
- Safe for testing and development

```bash
./scripts/start.sh -m SIMULATION
```

### FULL
- All services enabled
- Live market data (when configured)
- Live trading (when configured)

```bash
./scripts/start.sh -m FULL
```

### MARKET_DATA_ONLY
- Only market data service
- No trading strategies or adapters

```bash
./scripts/start.sh -m MARKET_DATA_ONLY
```

### STRATEGY_ONLY
- Only strategy service
- Requires external market data

```bash
./scripts/start.sh -m STRATEGY_ONLY
```

### ADAPTER_ONLY
- Only trading adapter service
- For order execution only

```bash
./scripts/start.sh -m ADAPTER_ONLY
```

## API Endpoints

Once the application is running, you can access these endpoints:

### Application Management
- `GET /api/v1/status` - Application status
- `GET /api/v1/health` - Health check
- `POST /api/v1/restart` - Restart services

### Strategy Management
- `GET /api/v1/strategies` - List all strategies
- `GET /api/v1/strategies/running` - List running strategies
- `GET /api/v1/strategies/stats` - Strategy statistics
- `POST /api/v1/strategies/start` - Start all strategies
- `POST /api/v1/strategies/stop` - Stop all strategies
- `POST /api/v1/strategies/reset` - Reset strategies

### Adapter Management
- `GET /api/v1/adapters` - List all adapters
- `GET /api/v1/adapters/stats` - Adapter statistics
- `GET /api/v1/adapters/symbols` - Supported symbols
- `GET /api/v1/adapters/balances` - Account balances
- `GET /api/v1/adapters/positions` - Current positions

### Monitoring
- `GET http://localhost:8081/actuator/health` - Actuator health
- `GET http://localhost:8081/actuator/metrics` - System metrics
- `GET http://localhost:8081/actuator/info` - Application info

## Configuration

### Environment Profiles

The application supports different profiles:

- `development` (default) - Development settings
- `production` - Production settings
- `test` - Testing settings

### Configuration Files

- `bedrock-app/src/main/resources/application.yml` - Main configuration
- `bedrock-app/src/main/resources/application-{profile}.yml` - Profile-specific configuration

### Key Configuration Sections

```yaml
bedrock:
  mode: SIMULATION  # Application mode
  environment: development  # Environment
  
  server:
    port: 8080  # API server port
    management-port: 8081  # Management port
  
  md:
    enabled: true
    simulation-enabled: true
    symbols: ["BTCUSDT", "ETHUSDT", "ADAUSDT"]
  
  strategy:
    enabled: true
    execution-mode: SIMULATION
  
  adapter:
    enabled: true
    # Single adapter:
    # type: simulation
    # Multiple adapters are supported via comma-separated values:
    # type: binance,bitget
    type: simulation

  # Per-exchange auth and adapter-specific settings
  # Keys under auth.exchanges and custom-settings must match adapter.type entries
  adapter:
    auth:
      exchanges:
        binance:
          api-key: "${BINANCE_API_KEY:}"
          secret-key: "${BINANCE_SECRET_KEY:}"
        bitget:
          api-key: "${BITGET_API_KEY:}"
          secret-key: "${BITGET_SECRET_KEY:}"
          passphrase: "${BITGET_PASSPHRASE:}"
    custom-settings:
      binance:
        base-url: "https://api.binance.com"
        ws-url: "wss://stream.binance.com:9443"
        recv-window-ms: 5000
        health-path: "/api/v3/ping"
        ws-subscriptions: ["btcusdt@trade", "ethusdt@trade"]
      bitget:
        base-url: "https://api.bitget.com"
        ws-url: "wss://ws.bitget.com/mix/v1/stream"
        # Enables private WS login regardless of the endpoint path
        ws-login-enabled: false
        # Auto-login also triggers when ws-subscriptions include private channels
        # (orders, positions, balance, account)
        product-type: "mix"
        health-path: "/api/mix/v1/market/time"
        # ws-subscriptions: ["ticker:BTCUSDT", "ticker:ETHUSDT"]
```

## Troubleshooting

### Common Issues

1. **Port already in use**
   ```bash
   # Use different ports
   ./scripts/start.sh -P 8090 -M 8091
   ```

2. **Java version issues**
   ```bash
   # Check Java version (requires Java 17+)
   java -version
   ```

3. **Build failures**
   ```bash
   # Clean build
   ./scripts/build.sh -c
   
   # Skip tests if they fail
   ./scripts/build.sh -t
   ```

4. **Application won't start**
   ```bash
   # Check logs
   tail -f logs/bedrock.log
   
   # Or check console output
   ./scripts/start.sh  # (without -b flag)
   ```

### Logs

Application logs are written to:
- Console output (when running in foreground)
- `logs/bedrock.log` (main application log)
- `logs/bedrock.out` (when running in background)

### Debug Mode

Enable debug mode for troubleshooting:

```bash
# Start with debug on port 5005
./scripts/start.sh -d 5005

# Connect with your IDE or debugger
```

## Performance Tuning

### JVM Options

```bash
# Custom JVM options
./scripts/start.sh -j "-Xmx4g -Xms2g -XX:+UseG1GC"
```

### Configuration Tuning

Key performance settings in `application.yml`:

```yaml
bedrock:
  md:
    buffer:
      ring-buffer-size: 65536  # Increase for higher throughput
      batch-size: 100
  
  strategy:
    performance-settings:
      worker-threads: 4  # Adjust based on CPU cores
      strategy-buffer-size: 1024
```

## Next Steps

1. **Customize Strategies**: Implement your own trading strategies
2. **Add Market Data Sources**: Connect to real exchanges
3. **Configure Live Trading**: Set up live trading adapters
4. **Monitor Performance**: Use the monitoring endpoints
5. **Scale Deployment**: Deploy to production environment

For more detailed information, see the main project documentation.
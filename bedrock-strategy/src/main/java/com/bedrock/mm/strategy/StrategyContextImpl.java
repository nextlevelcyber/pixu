package com.bedrock.mm.strategy;

import com.bedrock.mm.md.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Strategy context implementation
 */
public class StrategyContextImpl implements StrategyContext {
    private static final Logger log = LoggerFactory.getLogger(StrategyContextImpl.class);
    
    private final MarketDataService marketDataService;
    private final Object monitorService;
    private final StrategyConfig strategyConfig;
    
    // Placeholder interfaces - will be implemented in future modules
    private final OrderManager orderManager;
    private final PositionManager positionManager;
    private final RiskManager riskManager;
    
    // Removed constructors referencing MonitorService to avoid class loading issues

    // Overloads that avoid referencing MonitorService in call sites
    public StrategyContextImpl(MarketDataService marketDataService,
                               StrategyConfig strategyConfig) {
        this.marketDataService = marketDataService;
        this.monitorService = null;
        this.strategyConfig = strategyConfig;

        this.orderManager = new DummyOrderManager();
        this.positionManager = new DummyPositionManager();
        this.riskManager = new DummyRiskManager();
    }

    public StrategyContextImpl(MarketDataService marketDataService,
                               StrategyConfig strategyConfig,
                               OrderManager orderManager) {
        this.marketDataService = marketDataService;
        this.monitorService = null;
        this.strategyConfig = strategyConfig;

        this.orderManager = (orderManager != null) ? orderManager : new DummyOrderManager();
        this.positionManager = new DummyPositionManager();
        this.riskManager = new DummyRiskManager();
    }
    
    @Override
    public MarketDataService getMarketDataService() {
        return marketDataService;
    }
    
    @Override
    public Object getMonitorService() {
        return monitorService;
    }
    
    @Override
    public OrderManager getOrderManager() {
        return orderManager;
    }
    
    @Override
    public PositionManager getPositionManager() {
        return positionManager;
    }
    
    @Override
    public RiskManager getRiskManager() {
        return riskManager;
    }
    
    @Override
    public StrategyConfig getConfig() {
        return strategyConfig;
    }
    
    @Override
    public void log(String level, String message, Object... args) {
        switch (level.toUpperCase()) {
            case "INFO":
                log.info(message, args);
                break;
            case "WARN":
                log.warn(message, args);
                break;
            case "ERROR":
                log.error(message, args);
                break;
            case "DEBUG":
                log.debug(message, args);
                break;
            default:
                log.info(message, args);
                break;
        }
    }
    
    @Override
    public void logInfo(String message, Object... args) {
        log.info(message, args);
    }
    
    @Override
    public void logWarn(String message, Object... args) {
        log.warn(message, args);
    }
    
    @Override
    public void logError(String message, Object... args) {
        log.error(message, args);
    }
    
    // Dummy implementations for future modules
    
    private static class DummyOrderManager implements OrderManager {
        @Override
        public String submitOrder(String symbol, String side, double price, double quantity) {
            log.info("DummyOrderManager: Would submit order {} {} @ {} qty {}", 
                    symbol, side, price, quantity);
            return "DUMMY_ORDER_" + System.currentTimeMillis();
        }
        
        @Override
        public boolean cancelOrder(String orderId) {
            log.info("DummyOrderManager: Would cancel order {}", orderId);
            return true;
        }
        
        @Override
        public boolean isOrderActive(String orderId) {
            return false;
        }
    }
    
    private static class DummyPositionManager implements PositionManager {
        @Override
        public double getPosition(String symbol) {
            return 0.0;
        }
        
        @Override
        public double getAvailableBalance(String asset) {
            return 10000.0; // Dummy balance
        }
        
        @Override
        public double getUnrealizedPnl(String symbol) {
            return 0.0;
        }
    }
    
    private static class DummyRiskManager implements RiskManager {
        @Override
        public boolean checkRisk(String symbol, String side, double price, double quantity) {
            return true; // Always allow for now
        }
        
        @Override
        public double getMaxOrderSize(String symbol) {
            return 1000.0; // Dummy max size
        }
        
        @Override
        public boolean isWithinRiskLimits(String symbol, double position) {
            return Math.abs(position) <= 100.0; // Dummy limit
        }
    }
}
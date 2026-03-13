package com.bedrock.mm.md;

import com.bedrock.mm.md.providers.binance.BinanceFeed;
import com.bedrock.mm.md.providers.binance.BinanceProperties;
import com.bedrock.mm.md.providers.binance.BinancePrivateProperties;
import com.bedrock.mm.md.providers.binance.BinancePrivateFeed;
import com.bedrock.mm.md.providers.bitget.BitgetProperties;
import com.bedrock.mm.md.providers.bitget.BitgetV3Feed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;

/**
 * FeedManager: 根据配置统一管理各平台行情Feed的加载、启动与关闭。
 * 不再依赖每个Feed的Spring条件启动，由管理器集中控制。
 */
@Service
@ConditionalOnProperty(name = "bedrock.md.enabled", havingValue = "true", matchIfMissing = true)
public class FeedManager {
    private static final Logger log = LoggerFactory.getLogger(FeedManager.class);

    private final MarketDataServiceImpl mdService;
    private final MarketDataConfig mdConfig;
    private final PrivateDataServiceImpl privateService;
    private final BinanceProperties binanceProps;
    private final BitgetProperties bitgetProps;
    private final BinancePrivateProperties binancePrivateProps;

    private final List<AutoCloseable> activeFeeds = new ArrayList<>();

    public FeedManager(MarketDataServiceImpl mdService,
                       MarketDataConfig mdConfig,
                       PrivateDataServiceImpl privateService,
                       @Autowired(required = false) BinanceProperties binanceProps,
                       @Autowired(required = false) BitgetProperties bitgetProps,
                       @Autowired(required = false) BinancePrivateProperties binancePrivateProps) {
        this.mdService = mdService;
        this.mdConfig = mdConfig;
        this.privateService = privateService;
        this.binanceProps = binanceProps;
        this.bitgetProps = bitgetProps;
        this.binancePrivateProps = binancePrivateProps;
    }

    /**
     * 按配置启动所有启用的Feed
     */
    public void startAll() {
        log.info("FeedManager starting feeds by config...");
        // Binance
        if (binanceProps != null && binanceProps.isEnabled()) {
            BinanceFeed binance = new BinanceFeed(mdService, binanceProps);
            binance.start();
            activeFeeds.add(binance);
            log.info("BinanceFeed started by FeedManager");
        }
        // Bitget V3
        if (bitgetProps != null && bitgetProps.isEnabled()) {
            BitgetV3Feed bitget = new BitgetV3Feed(mdService, bitgetProps);
            bitget.start();
            activeFeeds.add(bitget);
            log.info("BitgetV3Feed started by FeedManager");
        }
        // Binance Private user data stream
        if (binancePrivateProps != null && binancePrivateProps.isEnabled()) {
            BinancePrivateFeed privateFeed = new BinancePrivateFeed(
                    binancePrivateProps,
                    privateService::handleBinanceMessage
            );
            privateFeed.start();
            activeFeeds.add(privateFeed);
            log.info("BinancePrivateFeed started by FeedManager");
        }
        // TODO: 未来在此添加更多平台Feed的加载逻辑
    }

    /**
     * 停止并关闭所有活跃Feed
     */
    public void stopAll() {
        log.info("FeedManager stopping all feeds...");
        for (AutoCloseable feed : activeFeeds) {
            try {
                feed.close();
            } catch (Exception e) {
                log.warn("Error closing feed", e);
            }
        }
        activeFeeds.clear();
    }

    @PreDestroy
    public void destroy() {
        stopAll();
    }
}
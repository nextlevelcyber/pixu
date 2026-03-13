package com.bedrock.mm.md;

import com.bedrock.mm.aeron.bus.EventBus;
import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventSerde;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.common.event.OrderAckPayload;
import com.bedrock.mm.common.event.FillPayload;
import com.bedrock.mm.common.event.PayloadCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * PrivateDataServiceImpl: 接收私有数据流（如交易所用户数据）并发布到统一事件总线。
 */
@Service
@ConditionalOnProperty(name = "bedrock.md.enabled", havingValue = "true", matchIfMissing = true)
public class PrivateDataServiceImpl {
    private static final Logger log = LoggerFactory.getLogger(PrivateDataServiceImpl.class);

    private final EventBus eventBus;
    private final EventSerde eventSerde;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong seq = new AtomicLong(0);

    public PrivateDataServiceImpl(@Autowired(required = false) EventBus eventBus, EventSerde eventSerde) {
        this.eventBus = eventBus;
        this.eventSerde = eventSerde;
    }

    /**
     * 处理来自 Binance 私有WS的原始消息。仅解析 executionReport，按 NEW 发布 ORDER_ACK，按 TRADE 发布 FILL。
     */
    public void handleBinanceMessage(String msg) {
        try {
            JsonNode root = mapper.readTree(msg);
            String eventType = text(root, "e");
            if (!"executionReport".equals(eventType)) {
                return; // 其他事件类型忽略
            }

            String execType = text(root, "x");
            String symbol = text(root, "s");
            long eventTimeMs = longVal(root, "E", System.currentTimeMillis());
            long tsNs = eventTimeMs * 1_000_000L;
            long sequence = seq.incrementAndGet();

            if ("NEW".equals(execType)) {
                OrderAckPayload payload = new OrderAckPayload(
                        symbol,
                        text(root, "i"),              // orderId
                        text(root, "c"),              // clientOrderId
                        text(root, "S"),              // side
                        text(root, "o"),              // orderType
                        text(root, "X"),              // orderStatus
                        text(root, "p"),              // price
                        text(root, "q"),              // quantity
                        eventTimeMs
                );
                byte[] payloadBytes = eventSerde.serialize(payload);
                publish(EventType.ORDER_ACK, payloadBytes, eventSerde.codec(), symbol, tsNs, sequence);
            } else if ("TRADE".equals(execType)) {
                // 成交事件
                String liquidity = booleanVal(root, "m") ? "MAKER" : "TAKER";
                FillPayload payload = new FillPayload(
                        symbol,
                        text(root, "i"),              // orderId
                        text(root, "c"),              // clientOrderId
                        text(root, "t"),              // tradeId
                        text(root, "S"),              // side
                        text(root, "L"),              // lastPrice
                        text(root, "l"),              // lastQuantity
                        liquidity,
                        text(root, "n"),              // commission
                        text(root, "N"),              // commission asset
                        eventTimeMs
                );
                byte[] payloadBytes = eventSerde.serialize(payload);
                publish(EventType.FILL, payloadBytes, eventSerde.codec(), symbol, tsNs, sequence);
            } else {
                // 其他执行类型暂不处理
            }
        } catch (Exception e) {
            log.warn("Error handling Binance private message: {}", msg, e);
        }
    }

    private void publish(EventType type, byte[] payload, PayloadCodec payloadCodec, String symbol, long tsNs, long seqNum) {
        if (eventBus == null) {
            // 如果未配置事件总线，则仅日志输出
            log.debug("EventBus unavailable. Skip publish type={} symbol={} payloadBytes={}", type, symbol,
                    payload == null ? 0 : payload.length);
            return;
        }
        try {
            EventEnvelope env = new EventEnvelope(type, payload, payloadCodec, symbol, tsNs, seqNum);
            eventBus.publish(env);
        } catch (Exception e) {
            log.warn("Error publishing private event: type={} symbol={} payloadBytes={}", type, symbol,
                    payload == null ? 0 : payload.length, e);
        }
    }

    // Helpers
    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static long longVal(JsonNode node, String field, long def) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? def : v.asLong(def);
    }

    private static boolean booleanVal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.asBoolean(false);
    }
}

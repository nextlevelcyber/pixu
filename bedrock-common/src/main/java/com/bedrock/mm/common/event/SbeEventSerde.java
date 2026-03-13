package com.bedrock.mm.common.event;

import com.bedrock.mm.sbe.BookDeltaPayloadV1Decoder;
import com.bedrock.mm.sbe.BookDeltaPayloadV1Encoder;
import com.bedrock.mm.sbe.FillPayloadV1Decoder;
import com.bedrock.mm.sbe.FillPayloadV1Encoder;
import com.bedrock.mm.sbe.MarketTickPayloadV1Decoder;
import com.bedrock.mm.sbe.MarketTickPayloadV1Encoder;
import com.bedrock.mm.sbe.MessageHeaderDecoder;
import com.bedrock.mm.sbe.MessageHeaderEncoder;
import com.bedrock.mm.sbe.OrderAckPayloadV1Decoder;
import com.bedrock.mm.sbe.OrderAckPayloadV1Encoder;
import com.bedrock.mm.sbe.OrderCommandPayloadDecoder;
import com.bedrock.mm.sbe.OrderCommandPayloadEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.agrona.concurrent.UnsafeBuffer;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Generated-SBE based serde for unified payload DTOs.
 * Falls back to framed JSON bytes for types without generated mappings.
 */
@Component("sbeEventSerde")
public class SbeEventSerde implements EventSerde {
    private static final int MAGIC = 0x53424530; // "SBE0" (fallback frame marker)
    private static final short VERSION = 1;
    private static final int FALLBACK_HEADER_LENGTH = 12;
    private static final double SCALE = 100_000_000.0;

    private final ObjectMapper mapper;

    public SbeEventSerde(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PayloadCodec codec() {
        return PayloadCodec.SBE;
    }

    @Override
    public <T> byte[] serialize(T payload) {
        try {
            if (payload instanceof OrderCommand) {
                OrderCommand cmd = (OrderCommand) payload;
                return encodeOrderCommand(cmd);
            }
            if (payload instanceof OrderAckPayload) {
                OrderAckPayload ack = (OrderAckPayload) payload;
                return encodeOrderAck(ack);
            }
            if (payload instanceof FillPayload) {
                FillPayload fill = (FillPayload) payload;
                return encodeFill(fill);
            }
            if (payload instanceof MarketTickPayload) {
                MarketTickPayload tick = (MarketTickPayload) payload;
                return encodeMarketTick(tick);
            }
            if (payload instanceof BookDeltaPayload) {
                BookDeltaPayload delta = (BookDeltaPayload) payload;
                return encodeBookDelta(delta);
            }
            return encodeFallbackJson(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to SBE-serialize payload", e);
        }
    }

    @Override
    public <T> T deserialize(byte[] payload, Class<T> type) {
        try {
            if (type == OrderCommand.class) {
                ensureExpectedTemplate(payload, type, OrderCommandPayloadDecoder.TEMPLATE_ID);
                return type.cast(decodeOrderCommand(payload));
            }
            if (type == OrderAckPayload.class) {
                ensureExpectedTemplate(payload, type, OrderAckPayloadV1Decoder.TEMPLATE_ID);
                return type.cast(decodeOrderAck(payload));
            }
            if (type == FillPayload.class) {
                ensureExpectedTemplate(payload, type, FillPayloadV1Decoder.TEMPLATE_ID);
                return type.cast(decodeFill(payload));
            }
            if (type == MarketTickPayload.class) {
                ensureExpectedTemplate(payload, type, MarketTickPayloadV1Decoder.TEMPLATE_ID);
                return type.cast(decodeMarketTick(payload));
            }
            if (type == BookDeltaPayload.class) {
                ensureExpectedTemplate(payload, type, BookDeltaPayloadV1Decoder.TEMPLATE_ID);
                return type.cast(decodeBookDelta(payload));
            }
            return decodeFallbackJson(payload, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to SBE-deserialize payload to " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private byte[] encodeOrderCommand(OrderCommand cmd) {
        int capacity = MessageHeaderEncoder.ENCODED_LENGTH
                + OrderCommandPayloadEncoder.BLOCK_LENGTH
                + varSize(cmd.getSymbol())
                + varSize(cmd.getSide())
                + varSize(cmd.getType())
                + varSize(cmd.getTimeInForce())
                + varSize(cmd.getStrategyId())
                + varSize(cmd.getClientOrderId());
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[capacity]);

        MessageHeaderEncoder header = new MessageHeaderEncoder();
        OrderCommandPayloadEncoder encoder = new OrderCommandPayloadEncoder()
                .wrapAndApplyHeader(buffer, 0, header)
                .priceScaled(cmd.getPrice())
                .quantityScaled(cmd.getQuantity())
                .symbol(ascii(cmd.getSymbol()))
                .side(ascii(cmd.getSide()))
                .orderType(ascii(cmd.getType()))
                .timeInForce(ascii(cmd.getTimeInForce()))
                .strategyId(ascii(cmd.getStrategyId()))
                .clientOrderId(ascii(cmd.getClientOrderId()));

        return slice(buffer.byteArray(), MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength());
    }

    private OrderCommand decodeOrderCommand(byte[] payload) {
        UnsafeBuffer buffer = new UnsafeBuffer(payload);
        MessageHeaderDecoder header = new MessageHeaderDecoder();
        OrderCommandPayloadDecoder decoder = new OrderCommandPayloadDecoder().wrapAndApplyHeader(buffer, 0, header);

        OrderCommand cmd = new OrderCommand();
        cmd.setSymbol(emptyToNull(decoder.symbol()));
        cmd.setSide(emptyToNull(decoder.side()));
        cmd.setType(emptyToNull(decoder.orderType()));
        cmd.setPrice(decoder.priceScaled());
        cmd.setQuantity(decoder.quantityScaled());
        cmd.setTimeInForce(emptyToNull(decoder.timeInForce()));
        cmd.setStrategyId(emptyToNull(decoder.strategyId()));
        cmd.setClientOrderId(emptyToNull(decoder.clientOrderId()));
        return cmd;
    }

    private byte[] encodeOrderAck(OrderAckPayload ack) {
        int capacity = MessageHeaderEncoder.ENCODED_LENGTH
                + OrderAckPayloadV1Encoder.BLOCK_LENGTH
                + varSize(ack.symbol)
                + varSize(ack.orderId)
                + varSize(ack.clientOrderId)
                + varSize(ack.side)
                + varSize(ack.orderType)
                + varSize(ack.status)
                + varSize(ack.price)
                + varSize(ack.quantity);
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[capacity]);

        MessageHeaderEncoder header = new MessageHeaderEncoder();
        OrderAckPayloadV1Encoder encoder = new OrderAckPayloadV1Encoder()
                .wrapAndApplyHeader(buffer, 0, header)
                .eventTimeMs(ack.eventTimeMs)
                .symbol(ascii(ack.symbol))
                .orderId(ascii(ack.orderId))
                .clientOrderId(ascii(ack.clientOrderId))
                .side(ascii(ack.side))
                .orderType(ascii(ack.orderType))
                .status(ascii(ack.status))
                .price(ascii(ack.price))
                .quantity(ascii(ack.quantity));

        return slice(buffer.byteArray(), MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength());
    }

    private OrderAckPayload decodeOrderAck(byte[] payload) {
        UnsafeBuffer buffer = new UnsafeBuffer(payload);
        MessageHeaderDecoder header = new MessageHeaderDecoder();
        OrderAckPayloadV1Decoder decoder = new OrderAckPayloadV1Decoder().wrapAndApplyHeader(buffer, 0, header);

        return new OrderAckPayload(
                emptyToNull(decoder.symbol()),
                emptyToNull(decoder.orderId()),
                emptyToNull(decoder.clientOrderId()),
                emptyToNull(decoder.side()),
                emptyToNull(decoder.orderType()),
                emptyToNull(decoder.status()),
                emptyToNull(decoder.price()),
                emptyToNull(decoder.quantity()),
                decoder.eventTimeMs());
    }

    private byte[] encodeFill(FillPayload fill) {
        int capacity = MessageHeaderEncoder.ENCODED_LENGTH
                + FillPayloadV1Encoder.BLOCK_LENGTH
                + varSize(fill.symbol)
                + varSize(fill.orderId)
                + varSize(fill.clientOrderId)
                + varSize(fill.tradeId)
                + varSize(fill.side)
                + varSize(fill.price)
                + varSize(fill.quantity)
                + varSize(fill.liquidity)
                + varSize(fill.commission)
                + varSize(fill.commissionAsset);
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[capacity]);

        MessageHeaderEncoder header = new MessageHeaderEncoder();
        FillPayloadV1Encoder encoder = new FillPayloadV1Encoder()
                .wrapAndApplyHeader(buffer, 0, header)
                .eventTimeMs(fill.eventTimeMs)
                .symbol(ascii(fill.symbol))
                .orderId(ascii(fill.orderId))
                .clientOrderId(ascii(fill.clientOrderId))
                .tradeId(ascii(fill.tradeId))
                .side(ascii(fill.side))
                .price(ascii(fill.price))
                .quantity(ascii(fill.quantity))
                .liquidity(ascii(fill.liquidity))
                .commission(ascii(fill.commission))
                .commissionAsset(ascii(fill.commissionAsset));

        return slice(buffer.byteArray(), MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength());
    }

    private FillPayload decodeFill(byte[] payload) {
        UnsafeBuffer buffer = new UnsafeBuffer(payload);
        MessageHeaderDecoder header = new MessageHeaderDecoder();
        FillPayloadV1Decoder decoder = new FillPayloadV1Decoder().wrapAndApplyHeader(buffer, 0, header);

        return new FillPayload(
                emptyToNull(decoder.symbol()),
                emptyToNull(decoder.orderId()),
                emptyToNull(decoder.clientOrderId()),
                emptyToNull(decoder.tradeId()),
                emptyToNull(decoder.side()),
                emptyToNull(decoder.price()),
                emptyToNull(decoder.quantity()),
                emptyToNull(decoder.liquidity()),
                emptyToNull(decoder.commission()),
                emptyToNull(decoder.commissionAsset()),
                decoder.eventTimeMs());
    }

    private byte[] encodeMarketTick(MarketTickPayload tick) {
        int capacity = MessageHeaderEncoder.ENCODED_LENGTH
                + MarketTickPayloadV1Encoder.BLOCK_LENGTH
                + varSize(tick.symbol);
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[capacity]);

        MessageHeaderEncoder header = new MessageHeaderEncoder();
        MarketTickPayloadV1Encoder encoder = new MarketTickPayloadV1Encoder()
                .wrapAndApplyHeader(buffer, 0, header)
                .timestamp(tick.timestamp)
                .price(tick.price)
                .quantity(tick.quantity)
                .buy(tick.buy ? (short) 1 : (short) 0)
                .sequenceNumber(tick.sequenceNumber)
                .symbol(ascii(tick.symbol));

        return slice(buffer.byteArray(), MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength());
    }

    private MarketTickPayload decodeMarketTick(byte[] payload) {
        UnsafeBuffer buffer = new UnsafeBuffer(payload);
        MessageHeaderDecoder header = new MessageHeaderDecoder();
        MarketTickPayloadV1Decoder decoder = new MarketTickPayloadV1Decoder().wrapAndApplyHeader(buffer, 0, header);

        return new MarketTickPayload(
                emptyToNull(decoder.symbol()),
                decoder.timestamp(),
                decoder.price(),
                decoder.quantity(),
                decoder.buy() == 1,
                decoder.sequenceNumber());
    }

    private byte[] encodeBookDelta(BookDeltaPayload delta) {
        int capacity = MessageHeaderEncoder.ENCODED_LENGTH
                + BookDeltaPayloadV1Encoder.BLOCK_LENGTH
                + varSize(delta.symbol)
                + varSize(delta.side)
                + varSize(delta.action);
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[capacity]);

        MessageHeaderEncoder header = new MessageHeaderEncoder();
        BookDeltaPayloadV1Encoder encoder = new BookDeltaPayloadV1Encoder()
                .wrapAndApplyHeader(buffer, 0, header)
                .timestamp(delta.timestamp)
                .price(delta.price)
                .quantity(delta.quantity)
                .sequenceNumber(delta.sequenceNumber)
                .symbol(ascii(delta.symbol))
                .side(ascii(delta.side))
                .action(ascii(delta.action));

        return slice(buffer.byteArray(), MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength());
    }

    private BookDeltaPayload decodeBookDelta(byte[] payload) {
        UnsafeBuffer buffer = new UnsafeBuffer(payload);
        MessageHeaderDecoder header = new MessageHeaderDecoder();
        BookDeltaPayloadV1Decoder decoder = new BookDeltaPayloadV1Decoder().wrapAndApplyHeader(buffer, 0, header);

        return new BookDeltaPayload(
                emptyToNull(decoder.symbol()),
                decoder.timestamp(),
                emptyToNull(decoder.side()),
                decoder.price(),
                decoder.quantity(),
                emptyToNull(decoder.action()),
                decoder.sequenceNumber());
    }

    private <T> byte[] encodeFallbackJson(T payload) throws Exception {
        byte[] body = mapper.writeValueAsBytes(payload);
        ByteBuffer out = ByteBuffer.allocate(FALLBACK_HEADER_LENGTH + body.length).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(MAGIC);
        out.putShort(VERSION);
        out.putShort((short) 0); // unknown template for fallback
        out.putInt(body.length);
        out.put(body);
        return out.array();
    }

    private <T> T decodeFallbackJson(byte[] payload, Class<T> type) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int magic = in.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("Invalid fallback payload magic: " + Integer.toHexString(magic));
        }
        short version = in.getShort();
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported fallback payload version: " + version);
        }
        in.getShort(); // templateId
        int bodyLength = in.getInt();
        if (bodyLength < 0 || bodyLength > in.remaining()) {
            throw new IllegalArgumentException("Invalid fallback payload length: " + bodyLength);
        }
        byte[] body = new byte[bodyLength];
        in.get(body);
        return mapper.readValue(body, type);
    }

    private static byte[] slice(byte[] src, int length) {
        if (length == src.length) {
            return src;
        }
        byte[] out = new byte[length];
        System.arraycopy(src, 0, out, 0, length);
        return out;
    }

    private static int varSize(String s) {
        return 4 + (s == null ? 0 : s.length());
    }

    private static String ascii(String s) {
        return s == null ? "" : new String(s.getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static long toScaled(double v) {
        return Math.round(v * SCALE);
    }

    private static double fromScaled(long v) {
        return v / SCALE;
    }

    private void ensureExpectedTemplate(byte[] payload, Class<?> targetType, int expectedTemplateId) {
        if (payload == null || payload.length < MessageHeaderDecoder.ENCODED_LENGTH) {
            throw new IllegalArgumentException("Payload too short for SBE header");
        }
        if (isFallbackFrame(payload)) {
            throw new IllegalArgumentException("Payload is fallback JSON frame; expected generated SBE template " + expectedTemplateId);
        }
        UnsafeBuffer buffer = new UnsafeBuffer(payload);
        MessageHeaderDecoder header = new MessageHeaderDecoder().wrap(buffer, 0);
        int schemaId = header.schemaId();
        int templateId = header.templateId();
        if (schemaId != MessageHeaderDecoder.SCHEMA_ID) {
            throw new IllegalArgumentException("Schema mismatch for " + targetType.getSimpleName()
                    + ": expected " + MessageHeaderDecoder.SCHEMA_ID + " but got " + schemaId);
        }
        if (templateId != expectedTemplateId) {
            throw new IllegalArgumentException("Template mismatch for " + targetType.getSimpleName()
                    + ": expected " + expectedTemplateId + " but got " + templateId);
        }
    }

    private static boolean isFallbackFrame(byte[] payload) {
        if (payload.length < 4) {
            return false;
        }
        int magic = ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return magic == MAGIC;
    }
}

package com.bedrock.mm.aeron.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChannelConstantsTest {

    @Test
    void testStreamIdUniqueness() {
        int[] streamIds = {
            ChannelConstants.STREAM_ID_MDS_BBO,
            ChannelConstants.STREAM_ID_MDS_DEPTH,
            ChannelConstants.STREAM_ID_MDS_TRADE,
            ChannelConstants.STREAM_ID_PRICING_TARGET,
            ChannelConstants.STREAM_ID_OMS_POSITION,
            ChannelConstants.STREAM_ID_OMS_ORDER,
            ChannelConstants.STREAM_ID_MGMT_CMD
        };

        for (int i = 0; i < streamIds.length; i++) {
            for (int j = i + 1; j < streamIds.length; j++) {
                assertNotEquals(streamIds[i], streamIds[j],
                    "Stream IDs must be unique: " + streamIds[i] + " vs " + streamIds[j]);
            }
        }
    }

    @Test
    void testChannelNameUniqueness() {
        String[] channelNames = {
            ChannelConstants.CHANNEL_MDS_BBO,
            ChannelConstants.CHANNEL_MDS_DEPTH,
            ChannelConstants.CHANNEL_MDS_TRADE,
            ChannelConstants.CHANNEL_PRICING_TARGET,
            ChannelConstants.CHANNEL_OMS_POSITION,
            ChannelConstants.CHANNEL_OMS_ORDER,
            ChannelConstants.CHANNEL_MGMT_CMD
        };

        for (int i = 0; i < channelNames.length; i++) {
            for (int j = i + 1; j < channelNames.length; j++) {
                assertNotEquals(channelNames[i], channelNames[j],
                    "Channel names must be unique: " + channelNames[i] + " vs " + channelNames[j]);
            }
        }
    }

    @Test
    void testGetChannelName() {
        assertEquals("mds.bbo", ChannelConstants.getChannelName(1001));
        assertEquals("mds.depth", ChannelConstants.getChannelName(1002));
        assertEquals("mds.trade", ChannelConstants.getChannelName(1003));
        assertEquals("pricing.target", ChannelConstants.getChannelName(2001));
        assertEquals("oms.position", ChannelConstants.getChannelName(3001));
        assertEquals("oms.order", ChannelConstants.getChannelName(3002));
        assertEquals("mgmt.cmd", ChannelConstants.getChannelName(9001));
        assertEquals("unknown", ChannelConstants.getChannelName(9999));
    }

    @Test
    void testGetStreamId() {
        assertEquals(1001, ChannelConstants.getStreamId("mds.bbo"));
        assertEquals(1002, ChannelConstants.getStreamId("mds.depth"));
        assertEquals(1003, ChannelConstants.getStreamId("mds.trade"));
        assertEquals(2001, ChannelConstants.getStreamId("pricing.target"));
        assertEquals(3001, ChannelConstants.getStreamId("oms.position"));
        assertEquals(3002, ChannelConstants.getStreamId("oms.order"));
        assertEquals(9001, ChannelConstants.getStreamId("mgmt.cmd"));
        assertEquals(-1, ChannelConstants.getStreamId("invalid.channel"));
        assertEquals(-1, ChannelConstants.getStreamId(null));
    }

    @Test
    void testIsValidStreamId() {
        assertTrue(ChannelConstants.isValidStreamId(1001));
        assertTrue(ChannelConstants.isValidStreamId(1002));
        assertTrue(ChannelConstants.isValidStreamId(1003));
        assertTrue(ChannelConstants.isValidStreamId(2001));
        assertTrue(ChannelConstants.isValidStreamId(3001));
        assertTrue(ChannelConstants.isValidStreamId(3002));
        assertTrue(ChannelConstants.isValidStreamId(9001));
        assertFalse(ChannelConstants.isValidStreamId(9999));
        assertFalse(ChannelConstants.isValidStreamId(-1));
    }

    @Test
    void testBidirectionalMapping() {
        // Verify round-trip mapping for all channels
        String[] channels = {
            ChannelConstants.CHANNEL_MDS_BBO,
            ChannelConstants.CHANNEL_MDS_DEPTH,
            ChannelConstants.CHANNEL_MDS_TRADE,
            ChannelConstants.CHANNEL_PRICING_TARGET,
            ChannelConstants.CHANNEL_OMS_POSITION,
            ChannelConstants.CHANNEL_OMS_ORDER,
            ChannelConstants.CHANNEL_MGMT_CMD
        };

        for (String channel : channels) {
            int streamId = ChannelConstants.getStreamId(channel);
            String recoveredChannel = ChannelConstants.getChannelName(streamId);
            assertEquals(channel, recoveredChannel,
                "Round-trip mapping must preserve channel name: " + channel);
        }
    }

    @Test
    void testStreamIdRanges() {
        // Market Data Service: 1000-1999
        assertTrue(ChannelConstants.STREAM_ID_MDS_BBO >= 1000 && ChannelConstants.STREAM_ID_MDS_BBO < 2000);
        assertTrue(ChannelConstants.STREAM_ID_MDS_DEPTH >= 1000 && ChannelConstants.STREAM_ID_MDS_DEPTH < 2000);
        assertTrue(ChannelConstants.STREAM_ID_MDS_TRADE >= 1000 && ChannelConstants.STREAM_ID_MDS_TRADE < 2000);

        // Pricing/Strategy: 2000-2999
        assertTrue(ChannelConstants.STREAM_ID_PRICING_TARGET >= 2000 && ChannelConstants.STREAM_ID_PRICING_TARGET < 3000);

        // OMS: 3000-3999
        assertTrue(ChannelConstants.STREAM_ID_OMS_POSITION >= 3000 && ChannelConstants.STREAM_ID_OMS_POSITION < 4000);
        assertTrue(ChannelConstants.STREAM_ID_OMS_ORDER >= 3000 && ChannelConstants.STREAM_ID_OMS_ORDER < 4000);

        // Management: 9000-9999
        assertTrue(ChannelConstants.STREAM_ID_MGMT_CMD >= 9000 && ChannelConstants.STREAM_ID_MGMT_CMD < 10000);
    }

    @Test
    void testChannelNamingConvention() {
        // All channel names should follow <source>.<type> format
        assertTrue(ChannelConstants.CHANNEL_MDS_BBO.matches("^[a-z]+\\.[a-z]+$"));
        assertTrue(ChannelConstants.CHANNEL_MDS_DEPTH.matches("^[a-z]+\\.[a-z]+$"));
        assertTrue(ChannelConstants.CHANNEL_MDS_TRADE.matches("^[a-z]+\\.[a-z]+$"));
        assertTrue(ChannelConstants.CHANNEL_PRICING_TARGET.matches("^[a-z]+\\.[a-z]+$"));
        assertTrue(ChannelConstants.CHANNEL_OMS_POSITION.matches("^[a-z]+\\.[a-z]+$"));
        assertTrue(ChannelConstants.CHANNEL_OMS_ORDER.matches("^[a-z]+\\.[a-z]+$"));
        assertTrue(ChannelConstants.CHANNEL_MGMT_CMD.matches("^[a-z]+\\.[a-z]+$"));
    }
}

package com.bedrock.mm.app.config;

import com.bedrock.mm.common.channel.ChannelMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "bedrock.bus")
public class EventBusProperties {
    /** Mode: IN_PROC, AERON_IPC, AERON_UDP */
    private ChannelMode mode = ChannelMode.IN_PROC;
    /** Single stream id for unified event bus */
    private int streamId = 9000;
    /** Aeron UDP endpoint or ignored for IPC/inproc */
    private String endpoint = "aeron:udp?endpoint=localhost:40200";
    /** InProc ringbuffer size (payload area) */
    private int ringBufferSize = 1024 * 1024; // 1MB
    /** Aeron directory used by client/media-driver when mode is Aeron. */
    private String aeronDir = "/tmp/aeron";
    /** Whether this process launches an embedded Aeron MediaDriver. */
    private boolean embeddedMediaDriver = true;
    /** Whether to delete Aeron dir on process start. */
    private boolean deleteAeronDirOnStart = false;

    public ChannelMode getMode() { return mode; }
    public void setMode(ChannelMode mode) { this.mode = (mode == null ? ChannelMode.IN_PROC : mode); }
    public int getStreamId() { return streamId; }
    public void setStreamId(int streamId) { this.streamId = streamId; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public int getRingBufferSize() { return ringBufferSize; }
    public void setRingBufferSize(int ringBufferSize) { this.ringBufferSize = ringBufferSize; }
    public String getAeronDir() { return aeronDir; }
    public void setAeronDir(String aeronDir) { this.aeronDir = aeronDir; }
    public boolean isEmbeddedMediaDriver() { return embeddedMediaDriver; }
    public void setEmbeddedMediaDriver(boolean embeddedMediaDriver) { this.embeddedMediaDriver = embeddedMediaDriver; }
    public boolean isDeleteAeronDirOnStart() { return deleteAeronDirOnStart; }
    public void setDeleteAeronDirOnStart(boolean deleteAeronDirOnStart) { this.deleteAeronDirOnStart = deleteAeronDirOnStart; }
}

package com.bedrock.mm.app.runtime.bus;

public enum DeadLetterReason {
    OUT_OF_ORDER,
    DESERIALIZE_FAILED,
    HANDLER_FAILED,
    PUBLISH_FAILED,
    BRIDGE_FAILED
}

package com.roseboard.infrastructure.message;

/** Pre-registered protobuf parse/encode pair; no reflective type URL loading. */
public interface ProtobufCodec {
    Object parse(byte[] payload) throws Exception;

    byte[] encode(Object value) throws Exception;
}

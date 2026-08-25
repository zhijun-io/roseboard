package com.roseboard.infrastructure.transport;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.roseboard.infrastructure.message.MessageContractException;
import com.roseboard.infrastructure.message.MessageErrorCode;
import com.roseboard.infrastructure.message.ProtobufCodec;

import java.util.List;

public final class PostTelemetryProtobufCodec implements ProtobufCodec {
    public static final PostTelemetryProtobufCodec INSTANCE = new PostTelemetryProtobufCodec();

    private static final Descriptors.Descriptor DESCRIPTOR = buildDescriptor();
    private static final Descriptors.FieldDescriptor VALUE_FIELD = DESCRIPTOR.findFieldByNumber(1);
    private static final Descriptors.FieldDescriptor UNIT_FIELD = DESCRIPTOR.findFieldByNumber(2);

    private PostTelemetryProtobufCodec() {
    }

    @Override
    public Object parse(byte[] payload) throws InvalidProtocolBufferException {
        DynamicMessage message = DynamicMessage.parseFrom(DESCRIPTOR, payload);
        long value = (Long) message.getField(VALUE_FIELD);
        String unit = (String) message.getField(UNIT_FIELD);
        return sample(value, unit);
    }

    @Override
    public byte[] encode(Object value) {
        if (!(value instanceof PostTelemetryMsg msg)) {
            throw new MessageContractException(MessageErrorCode.SCHEMA_VALIDATION, "Expected PostTelemetryMsg");
        }
        long numeric = 0L;
        String unit = "";
        for (PostTelemetryMsg.TsKvList tsKv : msg.tsKvList()) {
            for (KeyValueEntry entry : tsKv.kv()) {
                if ("value".equals(entry.key()) && entry.type() == KeyValueType.LONG_V) {
                    numeric = entry.longV();
                } else if ("unit".equals(entry.key()) && entry.type() == KeyValueType.STRING_V) {
                    unit = entry.stringV();
                }
            }
        }
        return DynamicMessage.newBuilder(DESCRIPTOR)
                .setField(VALUE_FIELD, numeric)
                .setField(UNIT_FIELD, unit)
                .build()
                .toByteArray();
    }

    public static PostTelemetryMsg sample(long value, String unit) {
        return new PostTelemetryMsg(List.of(
                new PostTelemetryMsg.TsKvList(
                        System.currentTimeMillis(),
                        List.of(
                                new KeyValueEntry("value", KeyValueType.LONG_V, false, null, value, 0D, null),
                                new KeyValueEntry("unit", KeyValueType.STRING_V, false, unit, 0L, 0D, null)))));
    }

    private static Descriptors.Descriptor buildDescriptor() {
        try {
            DescriptorProtos.FileDescriptorProto file = DescriptorProtos.FileDescriptorProto.newBuilder()
                    .setName("telemetry.proto")
                    .setPackage("roseboard.transport")
                    .setSyntax("proto3")
                    .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                            .setName("TelemetryPost")
                            .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                                    .setName("value")
                                    .setNumber(1)
                                    .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64)
                                    .build())
                            .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                                    .setName("unit")
                                    .setNumber(2)
                                    .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                                    .build())
                            .build())
                    .build();
            return Descriptors.FileDescriptor
                    .buildFrom(file, new Descriptors.FileDescriptor[0])
                    .findMessageTypeByName("TelemetryPost");
        } catch (Descriptors.DescriptorValidationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

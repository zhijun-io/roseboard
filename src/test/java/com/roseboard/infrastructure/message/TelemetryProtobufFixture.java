package com.roseboard.infrastructure.message;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.UnknownFieldSet;

final class TelemetryProtobufFixture {
    static final Descriptors.Descriptor DESCRIPTOR = buildDescriptor();
    private static final Descriptors.FieldDescriptor VALUE_FIELD = DESCRIPTOR.findFieldByNumber(1);
    private static final Descriptors.FieldDescriptor UNIT_FIELD = DESCRIPTOR.findFieldByNumber(2);

    static final ProtobufCodec CODEC = new ProtobufCodec() {
        @Override
        public Object parse(byte[] payload) throws InvalidProtocolBufferException {
            DynamicMessage message = DynamicMessage.parseFrom(DESCRIPTOR, payload);
            return new ProtobufCodecEquivalenceTest.TelemetrySample(
                    (Long) message.getField(VALUE_FIELD),
                    (String) message.getField(UNIT_FIELD));
        }

        @Override
        public byte[] encode(Object value) {
            if (!(value instanceof ProtobufCodecEquivalenceTest.TelemetrySample sample)) {
                throw new MessageContractException(MessageErrorCode.SCHEMA_VALIDATION, "Expected TelemetrySample");
            }
            return DynamicMessage.newBuilder(DESCRIPTOR)
                    .setField(VALUE_FIELD, sample.value())
                    .setField(UNIT_FIELD, sample.unit())
                    .build()
                    .toByteArray();
        }
    };

    private TelemetryProtobufFixture() {
    }

    static Descriptors.Descriptor descriptor() {
        return DESCRIPTOR;
    }

    static byte[] forwardPreserveUnknown(byte[] payload) throws InvalidProtocolBufferException {
        return DynamicMessage.parseFrom(DESCRIPTOR, payload).toByteArray();
    }

    static void rejectTypeUrl(String typeUrl) {
        throw new MessageContractException(MessageErrorCode.UNKNOWN_SCHEMA,
                "Arbitrary protobuf type URL is not allowed: " + typeUrl);
    }

    static boolean wasReflectiveLoadAttempted() {
        return false;
    }

    static byte[] withUnknownField(long value, String unit, int unknownFieldNumber, long unknownVarint)
            throws InvalidProtocolBufferException {
        return DynamicMessage.newBuilder(DESCRIPTOR)
                .setField(VALUE_FIELD, value)
                .setField(UNIT_FIELD, unit)
                .setUnknownFields(UnknownFieldSet.newBuilder()
                        .addField(unknownFieldNumber,
                                UnknownFieldSet.Field.newBuilder().addVarint(unknownVarint).build())
                        .build())
                .build()
                .toByteArray();
    }

    private static Descriptors.Descriptor buildDescriptor() {
        try {
            DescriptorProtos.FileDescriptorProto file = DescriptorProtos.FileDescriptorProto.newBuilder()
                    .setName("telemetry.proto")
                    .setPackage("roseboard.test")
                    .setSyntax("proto3")
                    .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                            .setName("TelemetrySample")
                            .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                                    .setName("value")
                                    .setNumber(1)
                                    .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64)
                                    .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                    .build())
                            .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                                    .setName("unit")
                                    .setNumber(2)
                                    .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                    .build())
                            .build())
                    .build();
            return Descriptors.FileDescriptor
                    .buildFrom(file, new Descriptors.FileDescriptor[0])
                    .findMessageTypeByName("TelemetrySample");
        } catch (Descriptors.DescriptorValidationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

package com.roseboard.infrastructure.message;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CodecRegistry {
    private static final Set<ContentType> GENERAL_CONTENT_TYPES = Set.of(
            ContentType.APPLICATION_JSON,
            ContentType.APPLICATION_X_PROTOBUF);

    private final Map<String, MessageTypeRegistration> byMessageType;
    private final Map<CodecKey, CodecBinding> byCodecKey;

    private CodecRegistry(Map<String, MessageTypeRegistration> byMessageType,
                          Map<CodecKey, CodecBinding> byCodecKey) {
        this.byMessageType = Map.copyOf(byMessageType);
        this.byCodecKey = Map.copyOf(byCodecKey);
    }

    public static Builder builder() {
        return new Builder();
    }

    public MessageEnvelope encode(EncodeRequest request) {
        Objects.requireNonNull(request, "request");
        MessageTypeRegistration registration = requireMessageType(request.messageType());
        ContentType contentType = request.contentType() == null
                ? defaultContentType(request.messageType())
                : request.contentType();
        String schemaId = request.schemaId() == null ? registration.defaultSchemaId() : request.schemaId();
        int schemaVersion = request.schemaVersion() == null
                ? registration.defaultSchemaVersion()
                : request.schemaVersion();

        CodecBinding binding = requireBinding(contentType, schemaId, schemaVersion, request.messageType());
        byte[] payload = binding.encode(request.value());
        return MessageEnvelope.builder()
                .messageId(request.messageId())
                .key(request.key())
                .headers(request.headers())
                .metadata(request.metadata())
                .descriptor(new MessageDescriptor(
                        request.messageType(), contentType, schemaId, schemaVersion,
                        MessageLimits.MAX_PAYLOAD_BYTES))
                .createdAt(request.createdAt())
                .payload(payload)
                .build();
    }

    public <T> T decode(MessageEnvelope envelope, Class<T> type) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(type, "type");
        ContentType contentType = envelope.descriptor().contentType();
        String messageType = envelope.descriptor().messageType();
        requireMessageType(messageType);
        CodecBinding binding = requireBinding(
                contentType,
                envelope.descriptor().schemaId(),
                envelope.descriptor().schemaVersion(),
                messageType);
        if (!binding.registeredType().equals(type)) {
            throw new MessageContractException(MessageErrorCode.SCHEMA_VALIDATION,
                    "Type is not registered for schema: " + type.getName());
        }
        @SuppressWarnings("unchecked")
        T value = (T) binding.decode(envelope.payload(), type);
        return value;
    }

    public boolean supports(String messageType, ContentType contentType, String schemaId, int schemaVersion) {
        CodecBinding binding = byCodecKey.get(new CodecKey(contentType, schemaId, schemaVersion));
        return binding != null && binding.messageType().equals(messageType);
    }

    public ContentType defaultContentType(String messageType) {
        return requireMessageType(messageType).jsonSupported()
                ? ContentType.APPLICATION_JSON
                : ContentType.APPLICATION_X_PROTOBUF;
    }

    public ContentType systemDefaultContentType() {
        return ContentType.APPLICATION_JSON;
    }

    public Set<ContentType> supportedGeneralContentTypes() {
        return GENERAL_CONTENT_TYPES;
    }

    public MessageDescriptor describe(String messageType, ContentType contentType, String schemaId, int schemaVersion) {
        requireBinding(contentType, schemaId, schemaVersion, messageType);
        return new MessageDescriptor(messageType, contentType, schemaId, schemaVersion, MessageLimits.MAX_PAYLOAD_BYTES);
    }

    private MessageTypeRegistration requireMessageType(String messageType) {
        MessageTypeRegistration registration = byMessageType.get(messageType);
        if (registration == null) {
            throw new MessageContractException(MessageErrorCode.UNKNOWN_MESSAGE_TYPE,
                    "Unknown messageType: " + messageType);
        }
        return registration;
    }

    private CodecBinding requireBinding(ContentType contentType, String schemaId, int schemaVersion, String messageType) {
        CodecBinding binding = byCodecKey.get(new CodecKey(contentType, schemaId, schemaVersion));
        if (binding == null || !binding.messageType().equals(messageType)) {
            throw new MessageContractException(MessageErrorCode.UNKNOWN_SCHEMA,
                    "Unknown schema for " + messageType + ": " + contentType.mediaType()
                            + "/" + schemaId + "@" + schemaVersion);
        }
        return binding;
    }

    public static final class Builder {
        private final Map<String, MessageTypeRegistration> byMessageType = new LinkedHashMap<>();
        private final Map<CodecKey, CodecBinding> byCodecKey = new LinkedHashMap<>();

        public Builder registerJson(String messageType, String schemaId, int schemaVersion, Class<?> type) {
            Objects.requireNonNull(type, "type");
            MessageDescriptor.requireRegistration(messageType, schemaId, schemaVersion);
            putBinding(new CodecKey(ContentType.APPLICATION_JSON, schemaId, schemaVersion),
                    CodecBinding.json(messageType, type));
            MessageTypeRegistration existing = byMessageType.get(messageType);
            byMessageType.put(messageType, existing == null
                    ? new MessageTypeRegistration(messageType, schemaId, schemaVersion, true, false)
                    : existing.withJson(schemaId, schemaVersion));
            return this;
        }

        public Builder registerProtobuf(String messageType, String schemaId, int schemaVersion,
                                        Class<?> type, ProtobufCodec codec) {
            Objects.requireNonNull(codec, "codec");
            MessageDescriptor.requireRegistration(messageType, schemaId, schemaVersion);
            MessageTypeRegistration existing = byMessageType.get(messageType);
            if (existing == null || !existing.jsonSupported()) {
                throw new MessageContractException(MessageErrorCode.MISSING_DEFAULT_CODEC,
                        "JSON default codec required for " + messageType
                                + " before registering protobuf; use registerProtobufOnly for internal messages");
            }
            putBinding(new CodecKey(ContentType.APPLICATION_X_PROTOBUF, schemaId, schemaVersion),
                    CodecBinding.protobuf(messageType, type, codec));
            byMessageType.put(messageType, existing.withProtobuf());
            return this;
        }

        public Builder registerProtobufOnly(String messageType, String schemaId, int schemaVersion,
                                            ProtobufCodec codec) {
            Objects.requireNonNull(codec, "codec");
            MessageDescriptor.requireRegistration(messageType, schemaId, schemaVersion);
            putBinding(new CodecKey(ContentType.APPLICATION_X_PROTOBUF, schemaId, schemaVersion),
                    CodecBinding.protobuf(messageType, byte[].class, codec));
            MessageTypeRegistration existing = byMessageType.get(messageType);
            byMessageType.put(messageType, existing == null
                    ? new MessageTypeRegistration(messageType, schemaId, schemaVersion, false, true)
                    : existing.withProtobuf());
            return this;
        }

        public CodecRegistry build() {
            for (MessageTypeRegistration registration : byMessageType.values()) {
                if (!registration.jsonSupported() && !registration.explicitProtobufOnly()) {
                    throw new MessageContractException(MessageErrorCode.MISSING_DEFAULT_CODEC,
                            "Missing JSON default codec for " + registration.messageType());
                }
            }
            return new CodecRegistry(byMessageType, byCodecKey);
        }

        private void putBinding(CodecKey key, CodecBinding binding) {
            if (byCodecKey.containsKey(key)) {
                throw new MessageContractException(MessageErrorCode.DUPLICATE_REGISTRATION,
                        "Duplicate registration for " + key.contentType().mediaType()
                                + "/" + key.schemaId() + "@" + key.schemaVersion());
            }
            byCodecKey.put(key, binding);
        }
    }

    private record CodecKey(ContentType contentType, String schemaId, int schemaVersion) {
    }

    private record MessageTypeRegistration(
            String messageType,
            String defaultSchemaId,
            int defaultSchemaVersion,
            boolean jsonSupported,
            boolean explicitProtobufOnly
    ) {
        MessageTypeRegistration withJson(String schemaId, int version) {
            return new MessageTypeRegistration(messageType, schemaId, version, true, false);
        }

        MessageTypeRegistration withProtobuf() {
            return new MessageTypeRegistration(
                    messageType, defaultSchemaId, defaultSchemaVersion, jsonSupported, explicitProtobufOnly);
        }
    }

    private record CodecBinding(String messageType, ContentType contentType, Class<?> registeredType, ProtobufCodec codec) {
        static CodecBinding json(String messageType, Class<?> type) {
            return new CodecBinding(messageType, ContentType.APPLICATION_JSON, type, null);
        }

        static CodecBinding protobuf(String messageType, Class<?> type, ProtobufCodec codec) {
            return new CodecBinding(messageType, ContentType.APPLICATION_X_PROTOBUF, type, codec);
        }

        byte[] encode(Object value) {
            if (contentType == ContentType.APPLICATION_JSON) {
                if (!registeredType.isInstance(value)) {
                    throw new MessageContractException(MessageErrorCode.SCHEMA_VALIDATION,
                            "Value type mismatch: expected " + registeredType.getName());
                }
                return JsonMessageCodec.encode(value);
            }
            try {
                return codec.encode(value);
            } catch (MessageContractException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new MessageContractException(MessageErrorCode.SCHEMA_VALIDATION,
                        "Protobuf encode failed: " + exception.getMessage());
            }
        }

        Object decode(byte[] payload, Class<?> type) {
            if (contentType == ContentType.APPLICATION_JSON) {
                return JsonMessageCodec.decode(payload, type);
            }
            try {
                return type.cast(codec.parse(payload));
            } catch (MessageContractException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new MessageContractException(MessageErrorCode.SCHEMA_VALIDATION,
                        "Protobuf decode failed: " + exception.getMessage());
            }
        }
    }
}

package com.roseboard.infrastructure.transport.mqtt;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.util.List;

final class MqttWebSocketCodec extends CombinedChannelDuplexHandler<
        MqttWebSocketCodec.WebSocketFrameDecoder, MqttWebSocketCodec.WebSocketFrameEncoder> {

    MqttWebSocketCodec() {
        super(new WebSocketFrameDecoder(), new WebSocketFrameEncoder());
    }

    static final class WebSocketFrameDecoder extends MessageToMessageDecoder<WebSocketFrame> {
        @Override
        protected void decode(ChannelHandlerContext ctx, WebSocketFrame frame, List<Object> out) {
            if (frame instanceof BinaryWebSocketFrame binary) {
                out.add(binary.retain().content());
            }
        }
    }

    static final class WebSocketFrameEncoder extends MessageToMessageEncoder<ByteBuf> {
        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
            out.add(new BinaryWebSocketFrame(msg.retain()));
        }
    }
}

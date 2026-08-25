package com.roseboard.infrastructure.transport.mqtt;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

final class MqttSslPeerCertificateHandler extends ChannelInboundHandlerAdapter {
    static final io.netty.util.AttributeKey<X509Certificate> PEER_CERTIFICATE =
            io.netty.util.AttributeKey.valueOf("mqttPeerCertificate");

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof SslHandshakeCompletionEvent completionEvent && completionEvent.isSuccess()) {
            capturePeerCertificate(ctx);
        }
        super.userEventTriggered(ctx, event);
    }

    static X509Certificate peerCertificate(ChannelHandlerContext ctx) {
        X509Certificate cached = ctx.channel().attr(PEER_CERTIFICATE).get();
        if (cached != null) {
            return cached;
        }
        capturePeerCertificate(ctx);
        return ctx.channel().attr(PEER_CERTIFICATE).get();
    }

    private static void capturePeerCertificate(ChannelHandlerContext ctx) {
        SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
        if (sslHandler == null) {
            return;
        }
        try {
            Certificate[] chain = sslHandler.engine().getSession().getPeerCertificates();
            if (chain.length > 0 && chain[0] instanceof X509Certificate certificate) {
                ctx.channel().attr(PEER_CERTIFICATE).set(certificate);
            }
        } catch (Exception ignored) {
        }
    }
}

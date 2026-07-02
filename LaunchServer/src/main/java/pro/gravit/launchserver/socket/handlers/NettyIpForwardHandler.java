package pro.gravit.launchserver.socket.handlers;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCounted;
import pro.gravit.launchserver.socket.NettyConnectContext;

import java.util.List;

public class NettyIpForwardHandler extends MessageToMessageDecoder<HttpRequest> {
    private final NettyConnectContext context;

    public NettyIpForwardHandler(NettyConnectContext context) {
        super();
        this.context = context;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, HttpRequest msg, List<Object> out) {
        if (msg instanceof ReferenceCounted referenceCounted) {
            referenceCounted.retain();
        }
        if (context.ip != null) {
            out.add(msg);
            return;
        }
        HttpHeaders headers = msg.headers();
        String realIP = null;
        if (headers.contains("X-Forwarded-For")) {
            // Кожен проксі ДОПИСУЄ адресу свого клієнта в кінець списку, тож довіряти
            // можна лише останньому елементу (його додав найближчий до нас проксі);
            // перші елементи клієнт може підробити власним заголовком.
            String xff = headers.get("X-Forwarded-For");
            int idx = xff.lastIndexOf(',');
            realIP = (idx >= 0 ? xff.substring(idx + 1) : xff).trim();
        }
        if (headers.contains("X-Real-IP")) {
            realIP = headers.get("X-Real-IP");
        }
        // Cloudflare завжди перезаписує цей заголовок реальною адресою клієнта —
        // найвищий пріоритет, підробити його через CF неможливо.
        if (headers.contains("CF-Connecting-IP")) {
            realIP = headers.get("CF-Connecting-IP");
        }
        if (realIP != null) {
            context.ip = realIP;
        }
        out.add(msg);
    }
}

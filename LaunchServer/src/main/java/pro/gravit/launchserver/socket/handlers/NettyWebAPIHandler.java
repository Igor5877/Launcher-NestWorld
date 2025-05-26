package pro.gravit.launchserver.socket.handlers;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.google.gson.JsonSyntaxException;
import pro.gravit.launcher.base.Launcher;
import pro.gravit.launchserver.socket.NettyConnectContext;
import pro.gravit.utils.helper.IOHelper;
import pro.gravit.utils.helper.LogHelper;
import pro.gravit.utils.helper.SecurityHelper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class NettyWebAPIHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final TreeSet<SeverletPathPair> severletList = new TreeSet<>(Comparator.comparingInt((e) -> -e.key.length()));
    private static final DefaultFullHttpResponse ERROR_500 = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR, Unpooled.wrappedBuffer(IOHelper.encode("Internal Server Error 500")));

    static {
        ERROR_500.retain();
    }

    private final NettyConnectContext context;
    private transient final Logger logger = LogManager.getLogger(); // Main logger
    private static final Logger crashReportLogger = LogManager.getLogger("CrashReport"); // Specific logger

    private static final Pattern SAFE_CHARS_PATTERN = Pattern.compile("[^a-zA-Z0-9_.-]");


    public NettyWebAPIHandler(NettyConnectContext context) {
        super();
        this.context = context;
    }

    static {
        addUnsafeSeverlet("/crashreport", NettyWebAPIHandler::handleCrashReportRequest);
    }

    private static String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        // Basic sanitization: replace potentially unsafe characters.
        // Ensure it's not empty after sanitization.
        // Disallow ".." to prevent path traversal.
        if (input.contains("..")) {
            return ""; // Disallow if ".." is present
        }
        String sanitized = SAFE_CHARS_PATTERN.matcher(input).replaceAll("");
        // Limit length to prevent excessively long paths/filenames
        if (sanitized.length() > 128) {
            sanitized = sanitized.substring(0, 128);
        }
        return sanitized;
    }

    public static void handleCrashReportRequest(ChannelHandlerContext ctx, FullHttpRequest msg, NettyConnectContext context) {
        SimpleSeverletHandler handler = (c, m, cc) -> {}; // Dummy for utility methods

        if (!msg.method().equals(HttpMethod.POST)) {
            handler.sendHttpResponse(ctx, handler.simpleResponse(HttpResponseStatus.METHOD_NOT_ALLOWED, "Only POST method is allowed"));
            return;
        }

        try {
            String jsonPayload = msg.content().toString(StandardCharsets.UTF_8);
            CrashReportPayload payload = Launcher.gsonManager.gson.fromJson(jsonPayload, CrashReportPayload.class);

            if (payload == null || payload.username == null || payload.fileName == null || payload.content == null ||
                payload.username.isEmpty() || payload.fileName.isEmpty()) {
                handler.sendHttpResponse(ctx, handler.simpleResponse(HttpResponseStatus.BAD_REQUEST, "Missing fields in payload"));
                return;
            }

            String username = sanitize(payload.username);
            String fileName = sanitize(payload.fileName);

            if (username.isEmpty() || fileName.isEmpty() || fileName.equals(".") || fileName.equals("..")) {
                LogHelper.warning("Invalid or sanitized to empty username or fileName. Original username: '%s', fileName: '%s'", payload.username, payload.fileName);
                handler.sendHttpResponse(ctx, handler.simpleResponse(HttpResponseStatus.BAD_REQUEST, "Invalid username or fileName after sanitization"));
                return;
            }
            
            Path baseDir = Paths.get("Crashreport-Client");
            Path userDir = baseDir.resolve(username);
            Files.createDirectories(userDir);
            Path reportFile = userDir.resolve(fileName);

            // Optional: Prevent overwriting, though client side should ideally generate unique names
            // if (Files.exists(reportFile)) {
            //     handler.sendHttpResponse(ctx, handler.simpleResponse(HttpResponseStatus.CONFLICT, "File already exists"));
            //     return;
            // }

            Files.writeString(reportFile, payload.content, StandardCharsets.UTF_8);

            crashReportLogger.info("Saved crash report {} for user {}", fileName, username); // Use specific logger
            handler.sendHttpResponse(ctx, handler.simpleResponse(HttpResponseStatus.OK, "Crash report received"));

        } catch (JsonSyntaxException e) {
            LogHelper.warning("Invalid JSON received for /crashreport: %s", e.getMessage());
            handler.sendHttpResponse(ctx, handler.simpleResponse(HttpResponseStatus.BAD_REQUEST, "Invalid JSON format"));
        } catch (Exception e) {
            LogHelper.error("Error processing /crashreport request", e);
            handler.sendHttpResponse(ctx, handler.simpleResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal server error"));
        } finally {
            // msg.release(); // FullHttpRequest is auto-released by SimpleChannelInboundHandler if not passed to next handler
        }
    }

    public static void addNewSeverlet(String path, SimpleSeverletHandler callback) {
        SeverletPathPair pair = new SeverletPathPair("/webapi/".concat(path), callback);
        severletList.add(pair);
    }

    public static SeverletPathPair addUnsafeSeverlet(String path, SimpleSeverletHandler callback) {
        SeverletPathPair pair = new SeverletPathPair(path, callback);
        severletList.add(pair);
        return pair;
    }

    public static void removeSeverlet(SeverletPathPair pair) {
        severletList.remove(pair);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
        boolean isNext = true;
        for (SeverletPathPair pair : severletList) {
            if (msg.uri().startsWith(pair.key)) {
                try {
                    pair.callback.handle(ctx, msg, context);
                } catch (Throwable e) {
                    logger.error("WebAPI Error", e);
                    ctx.writeAndFlush(ERROR_500, ctx.voidPromise());
                }
                isNext = false;
                break;
            }
        }
        if (isNext) {
            msg.retain();
            ctx.fireChannelRead(msg);
        }
    }

    @FunctionalInterface
    public interface SimpleSeverletHandler {
        void handle(ChannelHandlerContext ctx, FullHttpRequest msg, NettyConnectContext context);

        default Map<String, String> getParamsFromUri(String uri) {
            int ind = uri.indexOf("?");
            if (ind <= 0) {
                return Map.of();
            }
            String sub = uri.substring(ind + 1);
            String[] result = sub.split("&");
            Map<String, String> map = new HashMap<>();
            for (String s : result) {
                String c = URLDecoder.decode(s, StandardCharsets.UTF_8);
                int index = c.indexOf("=");
                if (index <= 0) {
                    continue;
                }
                String key = c.substring(0, index);
                String value = c.substring(index + 1);
                map.put(key, value);
            }
            return map;
        }

        default FullHttpResponse simpleResponse(HttpResponseStatus status, String body) {
            return new DefaultFullHttpResponse(HTTP_1_1, status, body != null ? Unpooled.wrappedBuffer(IOHelper.encode(body)) : Unpooled.buffer());
        }

        default FullHttpResponse simpleJsonResponse(HttpResponseStatus status, Object body) {
            DefaultFullHttpResponse httpResponse = new DefaultFullHttpResponse(HTTP_1_1, status, body != null ? Unpooled.wrappedBuffer(IOHelper.encode(Launcher.gsonManager.gson.toJson(body))) : Unpooled.buffer());
            httpResponse.headers().add(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
            return httpResponse;
        }

        default void sendHttpResponse(ChannelHandlerContext ctx, FullHttpResponse response) {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    public static class SeverletPathPair {
        public final String key;
        public final SimpleSeverletHandler callback;

        public SeverletPathPair(String key, SimpleSeverletHandler callback) {
            this.key = key;
            this.callback = callback;
        }
    }

    // Static inner class for JSON payload
    static class CrashReportPayload {
        String username;
        String fileName;
        String content;
    }
}

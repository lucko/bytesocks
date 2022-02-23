/*
 * This file is part of bytesocks, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.bytesocks;

import me.lucko.bytesocks.http.CreateHandler;
import me.lucko.bytesocks.http.MetricsHandler;
import me.lucko.bytesocks.http.PreConnectHandler;
import me.lucko.bytesocks.util.RateLimiter;
import me.lucko.bytesocks.util.TokenGenerator;
import me.lucko.bytesocks.ws.ChannelRegistry;
import me.lucko.bytesocks.ws.ConnectHandler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.jooby.Context;
import io.jooby.Cors;
import io.jooby.CorsHandler;
import io.jooby.ExecutionMode;
import io.jooby.Jooby;
import io.jooby.MediaType;
import io.jooby.ServerOptions;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;
import io.jooby.internal.netty.NettyWebSocket;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionException;

public class BytesocksServer extends Jooby {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(BytesocksServer.class);

    private final Map<?, ?> internalSocketsMap;

    public BytesocksServer(String host, int port, boolean metrics, ChannelRegistry channelRegistry, int createRateLimit, RateLimiter connectRateLimiter, TokenGenerator tokenGenerator) {
        ServerOptions serverOpts = new ServerOptions();
        serverOpts.setHost(host);
        serverOpts.setPort(port);
        serverOpts.setCompressionLevel(7);
        setServerOptions(serverOpts);

        setExecutionMode(ExecutionMode.EVENT_LOOP);
        setTrustProxy(true);

        // catch all errors & just return some generic error message
        error((ctx, cause, code) -> {
            Throwable rootCause = cause;
            while (rootCause instanceof CompletionException && rootCause.getCause() != null) {
                rootCause = rootCause.getCause();
            }

            if (rootCause instanceof StatusCodeException) {
                // handle expected errors
                ctx.setResponseCode(((StatusCodeException) rootCause).getStatusCode())
                        .setResponseType(MediaType.TEXT)
                        .send(rootCause.getMessage());
            } else {
                // handle unexpected errors: log stack trace and send a generic response
                LOGGER.error("Error thrown by handler", cause);
                ctx.setResponseCode(StatusCode.SERVER_ERROR)
                        .setResponseType(MediaType.TEXT)
                        .send("Server error occurred");
            }
        });

        // healthcheck endpoint
        get("/health", ctx -> {
            ctx.setResponseHeader("Cache-Control", "no-cache");
            return "{\"status\":\"ok\"}";
        });

        // metrics endpoint
        if (metrics) {
            get("/metrics", new MetricsHandler());
        }

        decorator(new CorsHandler(new Cors()
                .setUseCredentials(false)
                .setMaxAge(Duration.ofDays(1))));

        // define create channel handler
        get("/create", new CreateHandler(channelRegistry, createRateLimit, tokenGenerator));

        // define connect handlers
        before(new PreConnectHandler(channelRegistry, connectRateLimiter));
        ws("/{id:[a-zA-Z0-9]+}", new ConnectHandler(channelRegistry));

        // patch memory leak issue
        Map<?, ?> socketsMap = null;
        try {
            socketsMap = getAllSocketsMap();
        } catch (Exception e) {
            e.printStackTrace();
        }
        this.internalSocketsMap = socketsMap;
    }

    public void cleanupSocketsMap() {
        Map<?, ?> map = this.internalSocketsMap;
        if (map != null) {
            map.clear();
        }
    }

    public static String getIpAddress(Context ctx) {
        String ipAddress = ctx.header("x-real-ip").valueOrNull();
        if (ipAddress == null) {
            ipAddress = ctx.getRemoteAddress();
        }
        return ipAddress;
    }

    public static String getLabel(Context ctx) {
        String origin = ctx.header("Origin").valueOrNull();
        if (origin != null) {
            return origin;
        }

        String userAgent = ctx.header("User-Agent").valueOrNull();
        if (userAgent != null) {
            return userAgent;
        }

        return "unknown";
    }

    public static String describeForLogger(Context ctx) {
        String ipAddress = getIpAddress(ctx);
        String userAgent = ctx.header("User-Agent").value("null");
        String origin = ctx.header("Origin").value("null");

        return "    user agent = " + userAgent + "\n" +
                "    ip = " + ipAddress + "\n" +
                (origin.equals("null") ? "" : "    origin = " + origin + "\n");
    }

    // workaround for a jooby bug where web sockets don't get removed from NettyWebSocket#all
    private static Map<?, ?> getAllSocketsMap() throws Exception {
        Field staticMapField = NettyWebSocket.class.getDeclaredField("all");
        staticMapField.setAccessible(true);
        return (Map<?, ?>) staticMapField.get(null);
    }

}

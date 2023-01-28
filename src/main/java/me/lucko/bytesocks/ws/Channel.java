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

package me.lucko.bytesocks.ws;

import me.lucko.bytesocks.BytesocksServer;
import me.lucko.bytesocks.util.RateLimiter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.jooby.WebSocket;
import io.jooby.WebSocketCloseStatus;
import io.jooby.WebSocketMessage;
import io.jooby.internal.WebSocketMessageImpl;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Summary;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

public class Channel implements WebSocket.OnConnect, WebSocket.OnMessage, WebSocket.OnClose, WebSocket.OnError {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(Channel.class);

    public static final Gauge CLIENTS_GAUGE = Gauge.build()
            .name("bytesocks_clients")
            .help("The number of active clients")
            .labelNames("useragent")
            .register();

    public static final Counter MESSAGES_COUNTER = Counter.build()
            .name("bytesocks_messages_total")
            .help("The number of messages handled")
            .labelNames("useragent")
            .register();

    public static final Summary MESSAGES_SIZE_SUMMARY = Summary.build()
            .name("bytesocks_messages_size_bytes")
            .help("The size of messages processed")
            .labelNames("useragent")
            .register();

    /** The channel registry */
    private final ChannelRegistry registry;

    /** The channel id */
    private final String id;
    /** The ip address of the client that created the channel */
    private final String creatorIpAddress;
    /** The time when the channel was created */
    private final long creationTime = System.currentTimeMillis();
    /** A collection of connected sockets */
    private final Set<WebSocket> sockets = ConcurrentHashMap.newKeySet();
    /** The rate limiter */
    private final RateLimiter rateLimiter;
    /** The max number of clients allowed to connect */
    private final int maxClients;

    public Channel(ChannelRegistry registry, String id, String creatorIpAddress, RateLimiter rateLimiter, int maxClients) {
        this.registry = registry;
        this.id = id;
        this.creatorIpAddress = creatorIpAddress;
        this.rateLimiter = rateLimiter;
        this.maxClients = maxClients;
    }

    public String getId() {
        return this.id;
    }

    public String getCreatorIpAddress() {
        return this.creatorIpAddress;
    }

    public int getConnectedCount() {
        return this.sockets.size();
    }

    public boolean moreClientsAllowed() {
        return this.sockets.size() < this.maxClients;
    }

    public void gracefullyClose() {
        for (WebSocket socket : this.sockets) {
            socket.close(WebSocketCloseStatus.SERVICE_RESTARTED);
        }
    }

    public void audit() {
        if (this.sockets.isEmpty() && (System.currentTimeMillis() - this.creationTime) > TimeUnit.MINUTES.toMillis(5)) {
            close("no joins");
            return;
        }

        for (WebSocket socket : this.sockets) {
            if (!socket.isOpen()) {
                onClose(socket, WebSocketCloseStatus.GOING_AWAY);
            }
        }
    }

    public void close(String reason) {
        LOGGER.info("[CLOSED]\n" +
                "    channel id = " + this.id + "\n" +
                "    reason = " + reason + "\n"
        );
        this.registry.channelClosed(this);
    }

    @Override
    public void onConnect(@Nonnull WebSocket ws) {
        String label = BytesocksServer.getLabel(ws.getContext());
        ws.attribute("label", label);

        if (this.sockets.add(ws)) {
            CLIENTS_GAUGE.labels(label).inc();
        }

        LOGGER.info("[CONNECTED]\n" +
                "    channel id = " + this.id + "\n" +
                "    new connected count = " + this.sockets.size() + "\n" +
                BytesocksServer.describeForLogger(ws.getContext())
        );
    }

    @Override
    public void onClose(@Nonnull WebSocket ws, @Nonnull WebSocketCloseStatus status) {
        if (this.sockets.remove(ws)) {
            String label = ws.attribute("label");
            CLIENTS_GAUGE.labels(label).dec();
        }

        LOGGER.info("[DISCONNECTED]\n" +
                "    channel id = " + this.id + "\n" +
                "    new connected count = " + this.sockets.size() + "\n" +
                "    status = " + status + "\n" +
                BytesocksServer.describeForLogger(ws.getContext())
        );

        if (this.sockets.isEmpty()) {
            close("no clients");
        }
    }

    @Override
    public void onMessage(@Nonnull WebSocket ws, @Nonnull WebSocketMessage message) {
        String ipAddress = BytesocksServer.getIpAddress(ws.getContext());

        // check rate limit
        if (this.rateLimiter.check(ipAddress)) {
            LOGGER.info("[RATELIMIT]\n" +
                    "    type = messages" + "\n" +
                    "    channel id = " + this.id + "\n" +
                    BytesocksServer.describeForLogger(ws.getContext())
            );
            ws.close(WebSocketCloseStatus.POLICY_VIOLATION);
            return;
        }

        byte[] msg = ((WebSocketMessageImpl) message).bytes();

        // forward message
        for (WebSocket socket : this.sockets) {
            if (socket.equals(ws)) {
                continue;
            }
            if (!socket.isOpen()) {
                onClose(socket, WebSocketCloseStatus.GOING_AWAY);
            }

            socket.send(msg);
        }

        String label = ws.attribute("label");
        MESSAGES_COUNTER.labels(label).inc();
        MESSAGES_SIZE_SUMMARY.labels(label).observe(msg.length);
    }

    @Override
    public void onError(@Nonnull WebSocket ws, @Nonnull Throwable cause) {
        LOGGER.error("[ERROR]\n" +
                "    channel id = " + this.id + "\n" +
                "    connected count = " + this.sockets.size() + "\n" +
                BytesocksServer.describeForLogger(ws.getContext()),
                cause
        );
    }

}


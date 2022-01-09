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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.jooby.Context;
import io.jooby.StatusCode;
import io.jooby.WebSocket;
import io.jooby.WebSocketConfigurer;
import io.jooby.exception.StatusCodeException;

import javax.annotation.Nonnull;

public class ConnectHandler implements WebSocket.Initializer {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(ConnectHandler.class);

    private final ChannelRegistry channelRegistry;

    public ConnectHandler(ChannelRegistry channelRegistry) {
        this.channelRegistry = channelRegistry;
    }

    @Override
    public void init(@Nonnull Context ctx, @Nonnull WebSocketConfigurer configurer) {
        String id = ctx.path("id").value();
        Channel channel = this.channelRegistry.getChannel(id);

        if (channel == null) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "Cannot connect to channel");
        }

        LOGGER.info("[CONNECT]\n" +
                "    channel id = " + id + "\n" +
                BytesocksServer.describeForLogger(ctx)
        );

        // delegate all handling to the underlying channel
        configurer.onConnect(channel);
        configurer.onClose(channel);
        configurer.onMessage(channel);
    }

}

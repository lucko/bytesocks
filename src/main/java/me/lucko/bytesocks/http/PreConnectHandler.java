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

package me.lucko.bytesocks.http;

import me.lucko.bytesocks.BytesocksServer;
import me.lucko.bytesocks.util.RateLimiter;
import me.lucko.bytesocks.util.TokenGenerator;
import me.lucko.bytesocks.ws.ChannelRegistry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.jooby.Context;
import io.jooby.Route;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;

import javax.annotation.Nonnull;

public class PreConnectHandler implements Route.Before {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(PreConnectHandler.class);

    private final ChannelRegistry channelRegistry;
    private final RateLimiter rateLimiter;

    public PreConnectHandler(ChannelRegistry channelRegistry, RateLimiter rateLimiter) {
        this.channelRegistry = channelRegistry;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void apply(@Nonnull Context ctx) {
        // get the requested path
        String id = ctx.path("id").value();
        if (id.trim().isEmpty() || id.contains(".") || TokenGenerator.INVALID_TOKEN_PATTERN.matcher(id).find()) {
            throw new StatusCodeException(StatusCode.NOT_FOUND, "Invalid channel id");
        }

        String ipAddress = BytesocksServer.getIpAddress(ctx);

        // check rate limits
        if (this.rateLimiter.check(ipAddress)) {
            LOGGER.info("[RATELIMIT]\n" +
                    "    type = pre-connect" + "\n" +
                    "    channel id = " + id + "\n" +
                    BytesocksServer.describeForLogger(ctx)
            );
            throw new StatusCodeException(StatusCode.TOO_MANY_REQUESTS, "Rate limit exceeded");
        }

        // check if the channel exists
        if (!this.channelRegistry.canConnect(id)) {
            throw new StatusCodeException(StatusCode.BAD_REQUEST, "Cannot connect to channel");
        }
    }

}

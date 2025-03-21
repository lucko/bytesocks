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

import io.jooby.Context;
import io.jooby.MediaType;
import io.jooby.Route;
import io.jooby.StatusCode;
import io.jooby.exception.StatusCodeException;
import me.lucko.bytesocks.BytesocksServer;
import me.lucko.bytesocks.util.TokenGenerator;
import me.lucko.bytesocks.ws.ChannelRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;

public final class CreateHandler implements Route.Handler {

    /** Logger instance */
    private static final Logger LOGGER = LogManager.getLogger(CreateHandler.class);

    private final ChannelRegistry channelRegistry;
    private final int rateLimit;
    private final TokenGenerator tokenGenerator;

    public CreateHandler(ChannelRegistry channelRegistry, int rateLimit, TokenGenerator tokenGenerator) {
        this.channelRegistry = channelRegistry;
        this.rateLimit = rateLimit;
        this.tokenGenerator = tokenGenerator;
    }

    @Override
    public String apply(@Nonnull Context ctx) {
        String ipAddress = BytesocksServer.getIpAddress(ctx);

        // check rate limits
        if (this.channelRegistry.getChannelCount(ipAddress) >= this.rateLimit) {
            LOGGER.info("[RATELIMIT]\n" +
                    "    type = create" + "\n" +
                    BytesocksServer.describeForLogger(ctx)
            );
            throw new StatusCodeException(StatusCode.TOO_MANY_REQUESTS, "Rate limit exceeded");
        }

        // generate a id
        String id = this.tokenGenerator.generate();

        // register a new channel
        this.channelRegistry.registerNewChannel(id, ipAddress);

        LOGGER.info("[CREATE]\n" +
                "    channel id = " + id + "\n" +
                BytesocksServer.describeForLogger(ctx)
        );

        // return the url location as plain content
        ctx.setResponseCode(StatusCode.CREATED);
        ctx.setResponseHeader("Location", id);

        ctx.setResponseType(MediaType.JSON);
        return "{\"key\":\"" + id + "\"}";
    }

}

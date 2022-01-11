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

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import me.lucko.bytesocks.util.Configuration;
import me.lucko.bytesocks.util.Configuration.Option;
import me.lucko.bytesocks.util.EnvVars;
import me.lucko.bytesocks.util.RateLimiter;
import me.lucko.bytesocks.util.TokenGenerator;
import me.lucko.bytesocks.ws.ChannelRegistry;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.io.IoBuilder;

import io.jooby.ExecutionMode;
import io.jooby.Jooby;

import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A websocket server.
 */
public final class Bytesocks implements AutoCloseable {

    /** Logger instance */
    private static final Logger LOGGER;

    static {
        EnvVars.read();
        LOGGER = LogManager.getLogger(Bytesocks.class);
    }

    // Bootstrap
    public static void main(String[] args) throws Exception {
        // setup logging
        System.setOut(IoBuilder.forLogger(LOGGER).setLevel(Level.INFO).buildPrintStream());
        System.setErr(IoBuilder.forLogger(LOGGER).setLevel(Level.ERROR).buildPrintStream());

        // setup a new bytesocks instance
        Configuration config = Configuration.load(Paths.get("config.json"));
        Bytesocks bytesocks = new Bytesocks(config);
        Runtime.getRuntime().addShutdownHook(new Thread(bytesocks::close, "Bytesocks Shutdown Thread"));
    }

    private final ChannelRegistry channelRegistry;

    /** The web server instance */
    private final BytesocksServer server;

    public Bytesocks(Configuration config) {
        // setup simple logger
        LOGGER.info("loading bytesockets...");

        // setup channels
        this.channelRegistry = new ChannelRegistry(
                new RateLimiter(
                    // by default, allow messages at a rate of 30 times every 5 minutes (every 10s)
                    config.getInt(Option.MSG_RATE_LIMIT_PERIOD, 5),
                    config.getInt(Option.MSG_RATE_LIMIT, 30)
                ),
                config.getInt(Option.CHANNEL_MAX_CLIENTS, 5)
        );

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("bytesocks-channel-auditer")
                .build());

        // audit channels every second
        executor.scheduleAtFixedRate(this.channelRegistry::auditChannels, 1, 1, TimeUnit.SECONDS);

        // setup the web server
        this.server = (BytesocksServer) Jooby.createApp(new String[0], ExecutionMode.EVENT_LOOP, () -> new BytesocksServer(
                config.getString(Option.HOST, "0.0.0.0"),
                config.getInt(Option.PORT, 8080),
                this.channelRegistry,
                new RateLimiter(
                        // by default, allow create/connect (2 calls required) at a rate of 10 times every 30 minutes
                        // (you can create and connect to 5 channels every 30 mins)
                        config.getInt(Option.CREATE_RATE_LIMIT_PERIOD, 30),
                        config.getInt(Option.CREATE_RATE_LIMIT, 10)
                ),
                new TokenGenerator(config.getInt(Option.KEY_LENGTH, 7))
        ));
        this.server.start();
    }

    @Override
    public void close() {
        this.channelRegistry.closeAllChannels();
        this.server.stop();
    }

}

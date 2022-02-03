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

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import me.lucko.bytesocks.util.RateLimiter;

import io.prometheus.client.Gauge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A registry of {@link Channel}s.
 */
public class ChannelRegistry {

    public static final Gauge CHANNELS_GAUGE = Gauge.build()
            .name("bytesocks_channels")
            .help("The number of active channels")
            .register();

    /* The channels in the registry */
    private final Map<String, Channel> channelsById = new ConcurrentHashMap<>();
    private final Multimap<String, Channel> channelsByCreatorIpAddress = Multimaps.synchronizedSetMultimap(HashMultimap.create());

    /** The rate limiter used to limit sending messages to a channel */
    private final RateLimiter sendRateLimiter;

    /** Max clients that connect to a channel */
    private final int channelMaxClients;

    public ChannelRegistry(RateLimiter sendRateLimiter, int channelMaxClients) {
        this.sendRateLimiter = sendRateLimiter;
        this.channelMaxClients = channelMaxClients;
    }

    // called when a HTTP GET request is made to /create
    public void registerNewChannel(String id, String ipAddress) {
        Channel channel = new Channel(this, id, ipAddress, this.sendRateLimiter);
        this.channelsById.put(id, channel);
        this.channelsByCreatorIpAddress.put(ipAddress, channel);
        CHANNELS_GAUGE.inc();
    }

    // called to check rate limits
    public int getChannelCount(String ipAddress) {
        return this.channelsByCreatorIpAddress.get(ipAddress).size();
    }

    // called when all sockets disconnect from a channel
    public void channelClosed(Channel channel) {
        this.channelsById.remove(channel.getId());
        this.channelsByCreatorIpAddress.remove(channel.getCreatorIpAddress(), channel);
        CHANNELS_GAUGE.dec();
    }

    // called when the application stops
    public void closeAllChannels() {
        for (Channel channel : this.channelsById.values()) {
            channel.gracefullyClose();
        }
    }

    // checks if a channel exists + hasn't expired
    public boolean canConnect(String id) {
        Channel channel = this.channelsById.get(id);
        return channel != null && channel.getConnectedCount() < this.channelMaxClients;
    }

    // gets a channel if it exists and hasn't expired
    public Channel getChannel(String id) {
        return this.channelsById.get(id);
    }

    // audits channels by removing closed websocket connections
    public void auditChannels() {
        for (Channel channel : this.channelsById.values()) {
            channel.audit();
        }
    }

}

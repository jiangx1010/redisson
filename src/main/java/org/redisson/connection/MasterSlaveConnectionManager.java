/**
 * Copyright 2014 Nikita Koksharov, Nickolay Borbit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.connection;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;

import org.redisson.Config;
import org.redisson.MasterSlaveServersConfig;
import org.redisson.client.BaseRedisPubSubListener;
import org.redisson.client.RedisClient;
import org.redisson.client.RedisConnection;
import org.redisson.client.RedisPubSubConnection;
import org.redisson.client.RedisPubSubListener;
import org.redisson.client.codec.Codec;
import org.redisson.client.protocol.pubsub.PubSubStatusMessage.Type;
import org.redisson.misc.InfinitySemaphoreLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;

/**
 *
 * @author Nikita Koksharov
 *
 */
public class MasterSlaveConnectionManager implements ConnectionManager {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final HashedWheelTimer timer = new HashedWheelTimer();

    protected Codec codec;

    protected EventLoopGroup group;


    protected Class<? extends SocketChannel> socketChannelClass;

    protected final ConcurrentMap<String, PubSubConnectionEntry> name2PubSubConnection = new ConcurrentHashMap<String, PubSubConnectionEntry>();

    protected MasterSlaveServersConfig config;

    protected final NavigableMap<Integer, MasterSlaveEntry> entries = new ConcurrentSkipListMap<Integer, MasterSlaveEntry>();

    private final InfinitySemaphoreLatch shutdownLatch = new InfinitySemaphoreLatch();

    MasterSlaveConnectionManager() {
    }

    @Override
    public HashedWheelTimer getTimer() {
        return timer;
    }

    @Override
    public MasterSlaveServersConfig getConfig() {
        return config;
    }

    @Override
    public Codec getCodec() {
        return codec;
    }

    @Override
    public NavigableMap<Integer, MasterSlaveEntry> getEntries() {
        return entries;
    }

    public MasterSlaveConnectionManager(MasterSlaveServersConfig cfg, Config config) {
        init(cfg, config);
    }

    protected void init(MasterSlaveServersConfig config, Config cfg) {
        init(cfg);
        init(config);
    }

    protected void init(MasterSlaveServersConfig config) {
        this.config = config;

        MasterSlaveEntry entry = new MasterSlaveEntry(codec, this, config);
        entries.put(Integer.MAX_VALUE, entry);
    }

    protected void init(Config cfg) {
        if (cfg.isUseLinuxNativeEpoll()) {
            this.group = new EpollEventLoopGroup(cfg.getThreads());
            this.socketChannelClass = EpollSocketChannel.class;
        } else {
            this.group = new NioEventLoopGroup(cfg.getThreads());
            this.socketChannelClass = NioSocketChannel.class;
        }
        this.codec = cfg.getCodec();
    }

    public RedisClient createClient(String host, int port) {
        return createClient(host, port, config.getTimeout());
    }

    public RedisClient createClient(String host, int port, int timeout) {
        return new RedisClient(group, socketChannelClass, host, port, timeout);
    }

    public <T> FutureListener<T> createReleaseWriteListener(final int slot,
                                    final RedisConnection conn, final Timeout timeout) {
        return new FutureListener<T>() {
            @Override
            public void operationComplete(io.netty.util.concurrent.Future<T> future) throws Exception {
                shutdownLatch.release();
                timeout.cancel();
                releaseWrite(slot, conn);
            }
        };
    }

    public <T> FutureListener<T> createReleaseReadListener(final int slot,
                                    final RedisConnection conn, final Timeout timeout) {
        return new FutureListener<T>() {
            @Override
            public void operationComplete(io.netty.util.concurrent.Future<T> future) throws Exception {
                shutdownLatch.release();
                timeout.cancel();
                releaseRead(slot, conn);
            }
        };
    }

    @Override
    public int calcSlot(String key) {
        if (entries.size() == 1 || key == null) {
            return -1;
        }

        int start = key.indexOf('{');
        if (start != -1) {
            int end = key.indexOf('}');
            key = key.substring(start+1, end);
        }

        int result = CRC16.crc16(key.getBytes()) % 16384;
        log.debug("slot {} for {}", result, key);
        return result;
    }

    @Override
    public PubSubConnectionEntry getEntry(String channelName) {
        return name2PubSubConnection.get(channelName);
    }

    @Override
    public PubSubConnectionEntry subscribe(String channelName) {
        // multiple channel names per PubSubConnections allowed
        PubSubConnectionEntry сonnEntry = name2PubSubConnection.get(channelName);
        if (сonnEntry != null) {
            return сonnEntry;
        }

        Set<PubSubConnectionEntry> entries = new HashSet<PubSubConnectionEntry>(name2PubSubConnection.values());
        for (PubSubConnectionEntry entry : entries) {
            if (entry.tryAcquire()) {
                PubSubConnectionEntry oldEntry = name2PubSubConnection.putIfAbsent(channelName, entry);
                if (oldEntry != null) {
                    entry.release();
                    return oldEntry;
                }

                synchronized (entry) {
                    if (!entry.isActive()) {
                        entry.release();
                        return subscribe(channelName);
                    }
                    entry.subscribe(codec, channelName);
                    return entry;
                }
            }
        }

        int slot = -1;
        RedisPubSubConnection conn = nextPubSubConnection(slot);

        PubSubConnectionEntry entry = new PubSubConnectionEntry(conn, config.getSubscriptionsPerConnection());
        entry.tryAcquire();
        PubSubConnectionEntry oldEntry = name2PubSubConnection.putIfAbsent(channelName, entry);
        if (oldEntry != null) {
            returnSubscribeConnection(slot, entry);
            return oldEntry;
        }

        synchronized (entry) {
            if (!entry.isActive()) {
                entry.release();
                return subscribe(channelName);
            }
            entry.subscribe(codec, channelName);
            return entry;
        }
    }

    @Override
    public PubSubConnectionEntry psubscribe(String channelName) {
        // multiple channel names per PubSubConnections allowed
        PubSubConnectionEntry сonnEntry = name2PubSubConnection.get(channelName);
        if (сonnEntry != null) {
            return сonnEntry;
        }

        Set<PubSubConnectionEntry> entries = new HashSet<PubSubConnectionEntry>(name2PubSubConnection.values());
        for (PubSubConnectionEntry entry : entries) {
            if (entry.tryAcquire()) {
                PubSubConnectionEntry oldEntry = name2PubSubConnection.putIfAbsent(channelName, entry);
                if (oldEntry != null) {
                    entry.release();
                    return oldEntry;
                }

                synchronized (entry) {
                    if (!entry.isActive()) {
                        entry.release();
                        return psubscribe(channelName);
                    }
                    entry.psubscribe(codec, channelName);
                    return entry;
                }
            }
        }

        int slot = -1;
        RedisPubSubConnection conn = nextPubSubConnection(slot);

        PubSubConnectionEntry entry = new PubSubConnectionEntry(conn, config.getSubscriptionsPerConnection());
        entry.tryAcquire();
        PubSubConnectionEntry oldEntry = name2PubSubConnection.putIfAbsent(channelName, entry);
        if (oldEntry != null) {
            returnSubscribeConnection(slot, entry);
            return oldEntry;
        }

        synchronized (entry) {
            if (!entry.isActive()) {
                entry.release();
                return psubscribe(channelName);
            }
            entry.psubscribe(codec, channelName);
            return entry;
        }
    }

    @Override
    public void subscribe(RedisPubSubListener listener, String channelName) {
        PubSubConnectionEntry сonnEntry = name2PubSubConnection.get(channelName);
        if (сonnEntry != null) {
            сonnEntry.subscribe(codec, listener, channelName);
            return;
        }

        Set<PubSubConnectionEntry> entries = new HashSet<PubSubConnectionEntry>(name2PubSubConnection.values());
        for (PubSubConnectionEntry entry : entries) {
            if (entry.tryAcquire()) {
                PubSubConnectionEntry oldEntry = name2PubSubConnection.putIfAbsent(channelName, entry);
                if (oldEntry != null) {
                    entry.release();
                    return;
                }
                synchronized (entry) {
                    if (!entry.isActive()) {
                        entry.release();
                        subscribe(listener, channelName);
                        return;
                    }
                    entry.subscribe(codec, listener, channelName);
                    return;
                }
            }
        }

        int slot = -1;
        RedisPubSubConnection conn = nextPubSubConnection(slot);

        PubSubConnectionEntry entry = new PubSubConnectionEntry(conn, config.getSubscriptionsPerConnection());
        entry.tryAcquire();
        PubSubConnectionEntry oldEntry = name2PubSubConnection.putIfAbsent(channelName, entry);
        if (oldEntry != null) {
            returnSubscribeConnection(slot, entry);
            return;
        }
        synchronized (entry) {
            if (!entry.isActive()) {
                entry.release();
                subscribe(listener, channelName);
                return;
            }
            entry.subscribe(codec, listener, channelName);
            return;
        }
    }

    @Override
    public void unsubscribe(final String channelName) {
        final PubSubConnectionEntry entry = name2PubSubConnection.remove(channelName);
        if (entry == null) {
            return;
        }

        entry.unsubscribe(channelName, new BaseRedisPubSubListener() {

            @Override
            public boolean onStatus(Type type, String channel) {
                if (type == Type.UNSUBSCRIBE && channel.equals(channelName)) {
                    synchronized (entry) {
                        if (entry.tryClose()) {
                            returnSubscribeConnection(-1, entry);
                        }
                    }
                    return true;
                }
                return false;
            }

        });
    }

    @Override
    public void punsubscribe(final String channelName) {
        final PubSubConnectionEntry entry = name2PubSubConnection.remove(channelName);
        if (entry == null) {
            return;
        }

        entry.punsubscribe(channelName, new BaseRedisPubSubListener() {

            @Override
            public boolean onStatus(Type type, String channel) {
                if (type == Type.PUNSUBSCRIBE && channel.equals(channelName)) {
                    synchronized (entry) {
                        if (entry.tryClose()) {
                            returnSubscribeConnection(-1, entry);
                        }
                    }
                    return true;
                }
                return false;
            }

        });
    }

    protected MasterSlaveEntry getEntry() {
        return getEntry(0);
    }

    protected MasterSlaveEntry getEntry(int slot) {
        if (slot == -1) {
            slot = 0;
        }
        return entries.ceilingEntry(slot).getValue();
    }

    protected void slaveDown(int slot, String host, int port) {
        Collection<RedisPubSubConnection> allPubSubConnections = getEntry(slot).slaveDown(host, port);

        // reattach listeners to other channels
        for (Entry<String, PubSubConnectionEntry> mapEntry : name2PubSubConnection.entrySet()) {
            for (RedisPubSubConnection redisPubSubConnection : allPubSubConnections) {
                PubSubConnectionEntry entry = mapEntry.getValue();
                String channelName = mapEntry.getKey();

                if (!entry.getConnection().equals(redisPubSubConnection)) {
                    continue;
                }

                synchronized (entry) {
                    entry.close();

                    Collection<RedisPubSubListener> listeners = entry.getListeners(channelName);
                    unsubscribe(channelName);
                    if (!listeners.isEmpty()) {
                        PubSubConnectionEntry newEntry = subscribe(channelName);
                        for (RedisPubSubListener redisPubSubListener : listeners) {
                            newEntry.addListener(channelName, redisPubSubListener);
                        }
                        log.debug("resubscribed listeners for '{}' channel", channelName);
                    }
                }
            }
        }
    }

    protected void addSlave(String host, int port) {
        getEntry().addSlave(host, port);
    }

    protected void slaveUp(String host, int port) {
        getEntry().slaveUp(host, port);
    }

    protected void changeMaster(int endSlot, String host, int port) {
        getEntry(endSlot).changeMaster(host, port);
    }

    protected MasterSlaveEntry removeMaster(int endSlot) {
        return entries.remove(endSlot);
    }

    @Override
    public RedisConnection connectionWriteOp(int slot) {
        return getEntry(slot).connectionWriteOp();
    }

    @Override
    public RedisConnection connectionReadOp(int slot) {
        return getEntry(slot).connectionReadOp();
    }

    RedisPubSubConnection nextPubSubConnection(int slot) {
        return getEntry(slot).nextPubSubConnection();
    }

    protected void returnSubscribeConnection(int slot, PubSubConnectionEntry entry) {
        this.getEntry(slot).returnSubscribeConnection(entry);
    }

    @Override
    public void releaseWrite(int slot, RedisConnection connection) {
        getEntry(slot).releaseWrite(connection);
    }

    @Override
    public void releaseRead(int slot, RedisConnection connection) {
        getEntry(slot).releaseRead(connection);
    }

    @Override
    public void shutdown() {
        shutdownLatch.closeAndAwaitUninterruptibly();
        for (MasterSlaveEntry entry : entries.values()) {
            entry.shutdown();
        }
        timer.stop();
        group.shutdownGracefully().syncUninterruptibly();
    }

    @Override
    public <R> Promise<R> newPromise() {
        return group.next().newPromise();
    }

    @Override
    public EventLoopGroup getGroup() {
        return group;
    }

    @Override
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        return timer.newTimeout(task, delay, unit);
    }

    public InfinitySemaphoreLatch getShutdownLatch() {
        return shutdownLatch;
    }

}

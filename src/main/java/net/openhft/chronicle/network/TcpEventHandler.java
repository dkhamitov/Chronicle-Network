/*
 * Copyright 2016-2020 Chronicle Software
 *
 * https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.openhft.chronicle.network;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.annotation.PackageLocal;
import net.openhft.chronicle.core.io.AbstractCloseable;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.core.tcp.ISocketChannel;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.network.api.TcpHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.Math.max;
import static net.openhft.chronicle.network.connection.TcpChannelHub.TCP_BUFFER;

public class TcpEventHandler<T extends NetworkContext<T>>
        extends AbstractCloseable
        implements EventHandler, TcpEventHandlerManager<T> {

    public static final int TARGET_WRITE_SIZE = Integer.getInteger("TcpEventHandler.targetWriteSize", 1024);
    private static final int MONITOR_POLL_EVERY_SEC = Integer.getInteger("tcp.event.monitor.secs", 10);
    private static final long NBR_WARNING_NANOS = Long.getLong("tcp.nbr.warning.nanos", 20_000_000);
    private static final long NBW_WARNING_NANOS = Long.getLong("tcp.nbw.warning.nanos", 20_000_000);
    private static final Logger LOG = LoggerFactory.getLogger(TcpEventHandler.class);
    private static final AtomicBoolean FIRST_HANDLER = new AtomicBoolean();
    private static final int DEFAULT_MAX_MESSAGE_SIZE = 1 << 30;
    public static boolean DISABLE_TCP_NODELAY = Jvm.getBoolean("disable.tcp_nodelay");

    static {
        if (DISABLE_TCP_NODELAY) System.out.println("tcpNoDelay disabled");
    }

    private TcpEventHandler.SocketReader reader = new DefaultSocketReader();

    @NotNull
    private final ISocketChannel sc;
    private final String scToString;
    @NotNull
    private final T nc;
    @NotNull
    private final NetworkLog readLog, writeLog;
    @NotNull
    private final Bytes<ByteBuffer> inBBB;
    @NotNull
    private final Bytes<ByteBuffer> outBBB;
    private final boolean fair;

    private final boolean nbWarningEnabled;
    private final StatusMonitorEventHandler statusMonitorEventHandler;

    private int oneInTen;
    @Nullable
    private volatile TcpHandler<T> tcpHandler;
    private long lastTickReadTime = System.currentTimeMillis();

    public TcpEventHandler(@NotNull final T nc) {
        this(nc, false);
    }

    public TcpEventHandler(@NotNull final T nc, final boolean fair) {

        this.sc = ISocketChannel.wrapUnsafe(nc.socketChannel());
        this.scToString = sc.toString();
        this.nc = nc;
        this.fair = fair;
        try {
            sc.configureBlocking(false);
            Socket sock = sc.socket();
            // TODO: should have a strategy for this like ConnectionNotifier
            if (!DISABLE_TCP_NODELAY)
                sock.setTcpNoDelay(true);

            if (TCP_BUFFER >= 64 << 10) {
                sock.setReceiveBufferSize(TCP_BUFFER);
                sock.setSendBufferSize(TCP_BUFFER);

                checkBufSize(sock.getReceiveBufferSize(), "recv");
                checkBufSize(sock.getSendBufferSize(), "send");
            }
        } catch (IOException e) {
            if (isClosed() || !sc.isOpen())
                throw new IORuntimeException(e);
            Jvm.warn().on(getClass(), e);
        }

        //We have to provide back pressure to restrict the buffer growing beyond,2GB because it reverts to
        // being Native bytes, we should also provide back pressure if we are not able to keep up
        inBBB = Bytes.elasticByteBuffer(TCP_BUFFER + OS.pageSize(), max(TCP_BUFFER + OS.pageSize(), DEFAULT_MAX_MESSAGE_SIZE));
        outBBB = Bytes.elasticByteBuffer(TCP_BUFFER, max(TCP_BUFFER, DEFAULT_MAX_MESSAGE_SIZE));

        // must be set after we take a slice();
        outBBB.underlyingObject().limit(0);
        readLog = new NetworkLog(this.sc, "read");
        writeLog = new NetworkLog(this.sc, "write");
        nbWarningEnabled = Jvm.warn().isEnabled(getClass());
        statusMonitorEventHandler = new StatusMonitorEventHandler(getClass());
        if (FIRST_HANDLER.compareAndSet(false, true))
            warmUp();
    }

    @Override
    public void eventLoop(final EventLoop eventLoop) {
        eventLoop.addHandler(statusMonitorEventHandler);
    }

    @Override
    public void resetUsedByThread() {
        super.resetUsedByThread();
        ((AbstractCloseable) nc).resetUsedByThread();
    }

    public void reader(@NotNull final TcpEventHandler.SocketReader reader) {
        throwExceptionIfClosed();

        this.reader = reader;
    }

    @Override
    public synchronized boolean action() throws InvalidEventHandlerException {
        Jvm.optionalSafepoint();

        if (this.isClosed())
            throw new InvalidEventHandlerException();
        Closeable c = sc;
        if (c.isClosed())
            throw new InvalidEventHandlerException();

        if (tcpHandler == null)
            return false;

        if (!sc.isOpen()) {
            tcpHandler.onEndOfConnection(false);
            Closeable.closeQuietly(nc);
            throw new InvalidEventHandlerException("socket is closed");
        }

        statusMonitorEventHandler.incrementSocketPollCount();

        boolean busy = false;
        if (fair || oneInTen++ >= 8) {
            oneInTen = 0;
            try {
                busy = writeAction();
            } catch (Exception e) {
                Jvm.warn().on(getClass(), e);
            }
        }
        try {
            final ByteBuffer inBB = inBBB.underlyingObject();
            final int start = inBB.position();

            assert !sc.isBlocking();
            final long beginNs = System.nanoTime();
            final int read = inBB.remaining() > 0 ? reader.read(sc, inBBB) : Integer.MAX_VALUE;
            //   int read = inBB.remaining() > 0 ? sc.read(inBB) : Integer.MAX_VALUE;
            final long elapsedNs = System.nanoTime() - beginNs;
            if (nbWarningEnabled && elapsedNs > NBR_WARNING_NANOS)
                statusMonitorEventHandler.add(new ThreadLogTypeElapsedRecord(Thread.currentThread(), LogType.READ, elapsedNs));

            if (read == Integer.MAX_VALUE)
                onInBBFul();
            if (read > 0) {
                WanSimulator.dataRead(read);
                tcpHandler.onReadTime(System.nanoTime(), inBB, start, inBB.position());
                lastTickReadTime = System.currentTimeMillis();
                readLog.log(inBB, start, inBB.position());
                if (invokeHandler())
                    oneInTen++;
                busy = true;
            } else if (read == 0) {
                if (outBBB.readRemaining() > 0) {
                    busy |= invokeHandler();
                }

                // check for timeout only here - in other branches we either just read something or are about to close socket anyway
                if (nc.heartbeatTimeoutMs() > 0) {
                    final long tickTime = System.currentTimeMillis();
                    if (tickTime > lastTickReadTime + nc.heartbeatTimeoutMs()) {
                        final HeartbeatListener heartbeatListener = nc.heartbeatListener();
                        if (heartbeatListener != null && heartbeatListener.onMissedHeartbeat()) {
                            // implementer tries to recover - do not disconnect for some time
                            lastTickReadTime += heartbeatListener.lingerTimeBeforeDisconnect();
                        } else {
                            tcpHandler.onEndOfConnection(true);
                            close();
                            throw new InvalidEventHandlerException("heartbeat timeout");
                        }
                    }
                }
            } else {
                // read == -1, socketChannel has reached end-of-stream
                close();
                throw new InvalidEventHandlerException("socket closed " + sc);
            }

            return busy;

        } catch (ClosedChannelException e) {
            close();
            throw new InvalidEventHandlerException(e);
        } catch (IOException e) {
            close();
            handleIOE(e, tcpHandler.hasClientClosed(), nc.heartbeatListener());
            throw new InvalidEventHandlerException();
        } catch (InvalidEventHandlerException e) {
            close();
            throw e;
        } catch (Exception e) {
            close();
            Jvm.warn().on(getClass(), "", e);
            throw new InvalidEventHandlerException(e);
        }
    }

    @Override
    public String toString() {
        return "TcpEventHandler{" +
                "sc=" + scToString + ", " +
                "tcpHandler=" + tcpHandler + ", " +
                "closed=" + isClosed() +
                '}';
    }

    public void warmUp() {
        throwExceptionIfClosed();

        System.out.println(TcpEventHandler.class.getSimpleName() + " - Warming up...");
        final int runs = 12000;
        long beginNs = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            inBBB.readPositionRemaining(8, 1024);
            compactBuffer();
            clearBuffer();
        }
        long elapsedNs = System.nanoTime() - beginNs;
        System.out.println(TcpEventHandler.class.getSimpleName() + " - ... warmed up - took " + (elapsedNs / runs / 1e3) + " us avg");
    }

    private void checkBufSize(final int bufSize, final String name) {
        if (bufSize < TCP_BUFFER) {
            LOG.warn("Attempted to set " + name + " tcp buffer to " + TCP_BUFFER + " but kernel only allowed " + bufSize);
        }
    }

    public ISocketChannel socketChannel() {
        return sc;
    }

    @NotNull
    @Override
    public HandlerPriority priority() {
        final ServerThreadingStrategy sts = nc.serverThreadingStrategy();
        switch (sts) {
            case SINGLE_THREADED:
                return singleThreadedPriority();
            case CONCURRENT:
                return HandlerPriority.CONCURRENT;
            default:
                throw new UnsupportedOperationException("todo");
        }
    }

    @NotNull
    public HandlerPriority singleThreadedPriority() {
        return HandlerPriority.MEDIUM;
    }

    @Nullable
    public TcpHandler<T> tcpHandler() {
        return tcpHandler;
    }

    @Override
    public void tcpHandler(final TcpHandler<T> tcpHandler) {
        throwExceptionIfClosed();

        nc.onHandlerChanged(tcpHandler);
        this.tcpHandler = tcpHandler;
    }

    @Override
    protected boolean threadSafetyCheck() {
        // assume thread safe
        return true;
    }

    @Override
    public void loopFinished() {
        // Release unless already released
        inBBB.releaseLast();
        outBBB.releaseLast();
    }

    public void onInBBFul() {
        LOG.trace("inBB is full, can't read from socketChannel");
    }

    @PackageLocal
    boolean invokeHandler() throws IOException {
        Jvm.optionalSafepoint();
        boolean busy = false;
        final int position = inBBB.underlyingObject().position();
        inBBB.readLimit(position);
        outBBB.writePosition(outBBB.underlyingObject().limit());

        long lastInBBBReadPosition;
        do {
            lastInBBBReadPosition = inBBB.readPosition();
            tcpHandler.process(inBBB, outBBB, nc);

            statusMonitorEventHandler.addBytesRead(inBBB.readPosition() - lastInBBBReadPosition);

            // process method might change the underlying ByteBuffer by resizing it.
            ByteBuffer outBB = outBBB.underlyingObject();
            // did it write something?
            int wpBBB = Maths.toUInt31(outBBB.writePosition());
            int length = wpBBB - outBB.limit();
            if (length >= TARGET_WRITE_SIZE) {
                outBB.limit(wpBBB);
                boolean busy2 = tryWrite(outBB);
                if (busy2)
                    busy = busy2;
                else
                    break;
            }
        } while (lastInBBBReadPosition != inBBB.readPosition());

        final ByteBuffer outBB = outBBB.underlyingObject();
        if (outBBB.writePosition() > outBB.limit() || outBBB.writePosition() >= 4) {
            outBB.limit(Maths.toInt32(outBBB.writePosition()));
            busy |= tryWrite(outBB);
        }

        Jvm.optionalSafepoint();

        if (inBBB.readRemaining() == 0) {
            clearBuffer();

        } else if (inBBB.readPosition() > TCP_BUFFER / 4) {
            compactBuffer();
            busy = true;
        }

        return busy;
    }

    private void clearBuffer() {
        inBBB.clear();
        @Nullable final ByteBuffer inBB = inBBB.underlyingObject();
        inBB.clear();
    }

    private void compactBuffer() {
        // if it read some data compact();
        @Nullable final ByteBuffer inBB = inBBB.underlyingObject();
        inBB.position((int) inBBB.readPosition());
        inBB.limit((int) inBBB.readLimit());
        Jvm.optionalSafepoint();

        inBB.compact();
        Jvm.optionalSafepoint();
        inBBB.readPosition(0);
        inBBB.readLimit(inBB.remaining());
    }

    private void handleIOE(@NotNull final IOException e,
                           final boolean clientIntentionallyClosed,
                           @Nullable final HeartbeatListener heartbeatListener) {
        try {

            if (clientIntentionallyClosed)
                return;
            if (e.getMessage() != null && e.getMessage().startsWith("Connection reset by peer"))
                LOG.trace(e.getMessage(), e);
            else if (e.getMessage() != null && e.getMessage().startsWith("An existing connection " +
                    "was forcibly closed"))
                Jvm.debug().on(getClass(), e.getMessage());

            else if (!(e instanceof ClosedByInterruptException))
                Jvm.warn().on(getClass(), "", e);

            // The remote server has sent you a RST packet, which indicates an immediate dropping of the connection,
            // rather than the usual handshake. This bypasses the normal half-closed state transition.
            // I like this description: "Connection reset by peer" is the TCP/IP equivalent
            // of slamming the phone back on the hook.

            if (heartbeatListener != null)
                heartbeatListener.onMissedHeartbeat();

        } finally {
            close();
        }
    }

    @Override
    protected void performClose() {
        Closeable.closeQuietly(tcpHandler);
        Closeable.closeQuietly(this.nc.networkStatsListener());
        Closeable.closeQuietly(sc);
        Closeable.closeQuietly(nc);

        // NOTE Do not release buffers here as they might be in use. loopFinished() releases them and
        // is called from event loop when it knows that the thread calling "action" is done
        for (int i = 50; i >= 0; i--) {
            if (inBBB.refCount() + outBBB.refCount() == 0)
                break;
            Jvm.pause(5);
            if (i == 0)
                Jvm.debug().on(getClass(), "loopFinished failed to release buffers");
        }
    }

    @PackageLocal
    boolean tryWrite(final ByteBuffer outBB) throws IOException {
        if (outBB.remaining() <= 0)
            return false;
        final int start = outBB.position();
        final long beginNs = System.nanoTime();
        assert !sc.isBlocking();
        int wrote = sc.write(outBB);
        long elapsedNs = System.nanoTime() - beginNs;
        if (nbWarningEnabled && elapsedNs > NBW_WARNING_NANOS)
            statusMonitorEventHandler.add(new ThreadLogTypeElapsedRecord(Thread.currentThread(), LogType.WRITE, elapsedNs));

        tcpHandler.onWriteTime(beginNs, outBB, start, outBB.position());

        statusMonitorEventHandler.addBytesWritten(outBB.position() - start);
        writeLog.log(outBB, start, outBB.position());

        if (wrote < 0) {
            close();
        } else if (wrote > 0) {
            outBB.compact().flip();
            outBBB.writePosition(outBB.limit());
            return true;
        }
        return false;
    }

    public boolean writeAction() {
        resetUsedByThread();

        boolean busy = false;
        try {
            // get more data to write if the buffer was empty
            // or we can write some of what is there
            final ByteBuffer outBB = outBBB.underlyingObject();
            final int remaining = outBB.remaining();
            busy = remaining > 0;
            if (busy)
                tryWrite(outBB);

            // has the remaining changed, i.e. did it write anything?
            if (outBB.remaining() == remaining) {
                busy |= invokeHandler();
                if (!busy)
                    busy = tryWrite(outBB);
            }
        } catch (ClosedChannelException cce) {
            close();

        } catch (IOException e) {
            if (!isClosed())
                handleIOE(e, tcpHandler.hasClientClosed(), nc.heartbeatListener());
        }
        return busy;
    }

    private enum LogType {
        READ("read"), WRITE("write");

        private final String label;

        LogType(@NotNull final String label) {
            this.label = label;
        }

        private String label() {
            return label;
        }

    }

    @FunctionalInterface
    public interface SocketReader {

        /**
         * Reads content from the provided {@code socketChannel} into the provided {@code bytes}.
         *
         * @param socketChannel to read from
         * @param bytes         to which content from the {@code socketChannel} is put
         * @return the number of bytes read from the provided {@code socketChannel}.
         * @throws IOException if there is a problem reading form the provided {@code socketChannel}.
         */
        int read(@NotNull ISocketChannel socketChannel, @NotNull Bytes<ByteBuffer> bytes) throws IOException;
    }

    public static final class DefaultSocketReader implements SocketReader {

        @Override
        public int read(@NotNull final ISocketChannel socketChannel, @NotNull final Bytes<ByteBuffer> bytes) throws IOException {
            return socketChannel.read(bytes.underlyingObject());
        }
    }

    public static class Factory<T extends NetworkContext<T>> implements MarshallableFunction<T, TcpEventHandler<T>> {
        public Factory() {
        }

        @NotNull
        @Override
        public TcpEventHandler<T> apply(@NotNull final T nc) {
            return new TcpEventHandler<T>(nc);
        }
    }

    private static final class ThreadLogTypeElapsedRecord {

        private final Thread thread;
        private final LogType logType;
        private final long elapsedNs;

        public ThreadLogTypeElapsedRecord(@NotNull final Thread thread,
                                          @NotNull final LogType logType,
                                          final long elapsedNs) {
            this.thread = thread;
            this.logType = logType;
            this.elapsedNs = elapsedNs;
        }
    }

    /**
     * EventHandler that handles both network stats listening
     * and printing of messages that otherwise might impact performance.
     */
    private final class StatusMonitorEventHandler implements EventHandler {

        private final String className;
        private final StringBuilder messageBuilder = new StringBuilder();
        private final AtomicInteger socketPollCount = new AtomicInteger();
        private final AtomicLong bytesReadCount = new AtomicLong();
        private final AtomicLong bytesWriteCount = new AtomicLong();
        private final Queue<ThreadLogTypeElapsedRecord> logs = new ConcurrentLinkedQueue<>();

        private long lastMonitor;

        public StatusMonitorEventHandler(@NotNull final Class<?> clazz) {
            this.className = clazz.getSimpleName();
        }

        @Override
        public boolean action() throws InvalidEventHandlerException {
            if (TcpEventHandler.this.isClosed())
                throw InvalidEventHandlerException.reusable();

            if (!logs.isEmpty()) {
                ThreadLogTypeElapsedRecord msg;
                while ((msg = logs.poll()) != null) {
                    messageBuilder.setLength(0);
                    messageBuilder
                            .append("Non blocking ")
                            .append(className)
                            .append(" (")
                            .append(msg.thread.getName())
                            .append(") ")
                            .append(msg.logType.label())
                            .append(" took ")
                            .append(msg.elapsedNs / 1000)
                            .append(" us");

                    Jvm.warn().on(getClass(), messageBuilder.toString());
                }
            }

            final long now = System.currentTimeMillis();
            if (now > lastMonitor + (MONITOR_POLL_EVERY_SEC * 1000)) {
                final NetworkStatsListener<T> networkStatsListener = nc.networkStatsListener();
                if (networkStatsListener != null && !networkStatsListener.isClosed()) {
                    if (lastMonitor == 0) {
                        networkStatsListener.onNetworkStats(0, 0, 0);
                    } else {
                        networkStatsListener.onNetworkStats(
                                bytesWriteCount.get() / MONITOR_POLL_EVERY_SEC,
                                bytesReadCount.get() / MONITOR_POLL_EVERY_SEC,
                                socketPollCount.get() / MONITOR_POLL_EVERY_SEC);
                        bytesWriteCount.set(0);
                        bytesReadCount.set(0);
                        socketPollCount.set(0);
                    }
                }
                lastMonitor = now;
            }
            return false;
        }

        private void incrementSocketPollCount() {
            socketPollCount.incrementAndGet();
        }

        private void addBytesRead(long delta) {
            bytesReadCount.addAndGet(delta);
        }

        private void addBytesWritten(long delta) {
            bytesReadCount.addAndGet(delta);
        }

        private void add(@NotNull TcpEventHandler.ThreadLogTypeElapsedRecord logTypeTimeRecord) {
            logs.add(logTypeTimeRecord);
        }

        @Override
        public @NotNull HandlerPriority priority() {
            return HandlerPriority.MONITOR;
        }
    }

}
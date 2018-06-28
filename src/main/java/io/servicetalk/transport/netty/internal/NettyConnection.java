/*
 * Copyright © 2018 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.transport.netty.internal;

import io.servicetalk.concurrent.Completable.Subscriber;
import io.servicetalk.concurrent.api.Completable;
import io.servicetalk.concurrent.api.CompletableProcessor;
import io.servicetalk.concurrent.api.Publisher;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.transport.api.ConnectionContext;
import io.servicetalk.transport.api.ExecutionContext;
import io.servicetalk.transport.api.FlushStrategy;
import io.servicetalk.transport.api.FlushStrategyHolder;
import io.servicetalk.transport.netty.internal.CloseHandler.CloseEvent;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.ChannelOutputShutdownEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;

import static io.netty.util.ReferenceCountUtil.release;
import static io.servicetalk.concurrent.Cancellable.IGNORE_CANCEL;
import static io.servicetalk.concurrent.internal.ThrowableUtil.unknownStackTrace;
import static io.servicetalk.transport.netty.internal.CloseHandler.NOOP_CLOSE_HANDLER;
import static io.servicetalk.transport.netty.internal.Flush.composeFlushes;
import static io.servicetalk.transport.netty.internal.NettyIoExecutors.toNettyIoExecutor;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.atomic.AtomicReferenceFieldUpdater.newUpdater;

/**
 * Implementation of {@link Connection} backed by a netty {@link Channel}.
 *
 * @param <Read> Type of objects read from this connection.
 * @param <Write> Type of objects written to this connection.
 */
public class NettyConnection<Read, Write> implements Connection<Read, Write> {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyConnection.class);

    private static final TerminalPredicate PIPELINE_UNSUPPORTED_PREDICATE = new TerminalPredicate();

    private static final WritableListener PLACE_HOLDER_WRITABLE_LISTENER = new NoopWritableListener();
    private static final WritableListener SINGLE_ITEM_WRITABLE_LISTENER = new NoopWritableListener();

    private static final ClosedChannelException CLOSED_CHANNEL_INACTIVE =
            unknownStackTrace(new ClosedChannelException(), NettyConnection.class, "channelInactive(..)");
    private static final ClosedChannelException CLOSED_FAIL_ACTIVE =
            unknownStackTrace(new ClosedChannelException(), NettyConnection.class, "failIfWriteActive(..)");
    private static final AtomicReferenceFieldUpdater<NettyConnection, WritableListener> writableListenerUpdater =
            newUpdater(NettyConnection.class, WritableListener.class, "writableListener");
    private volatile WritableListener writableListener = PLACE_HOLDER_WRITABLE_LISTENER;

    private volatile boolean readInProgress;
    @Nullable
    private volatile ReadAwareFlushStrategyHolder<Write> readAwareFlushStrategyHolder;

    private final BooleanSupplier readInProgressSupplier = () -> readInProgress;
    private final Channel channel;
    private final ConnectionContext context;
    private final Publisher<Read> read;
    private final TerminalPredicate<Read> terminalMsgPredicate;
    private final CloseHandler closeHandler;
    private final Completable onClosing;
    /**
     * Potentially contains more information when a protocol or channel level close event was observed.
     */
    @Nullable
    private CloseEvent closeReason;

    /**
     * Create a new instance.
     *
     * @param channel Netty channel which represents the connection.
     * @param context The ServiceTalk entity which represents the connection.
     * @param read {@link Publisher} which emits all data read from the underlying channel.
     */
    @SuppressWarnings("unchecked")
    public NettyConnection(Channel channel, ConnectionContext context, Publisher<Read> read) {
        this(channel, context, read, PIPELINE_UNSUPPORTED_PREDICATE, NOOP_CLOSE_HANDLER);
    }

    /**
     * Create a new instance.
     *
     * @param channel Netty channel which represents the connection.
     * @param context The ServiceTalk entity which represents the connection.
     * @param read {@link Publisher} which emits all data read from the underlying channel.
     * @param terminalMsgPredicate {@link TerminalPredicate} to detect end of a <i>response</i>.
     */
    public NettyConnection(Channel channel, ConnectionContext context, Publisher<Read> read,
                           TerminalPredicate<Read> terminalMsgPredicate) {
        this(channel, context, read, terminalMsgPredicate, NOOP_CLOSE_HANDLER);
    }

    /**
     * Create a new instance.
     * @param channel Netty channel which represents the connection.
     * @param context The ServiceTalk entity which represents the connection.
     * @param read {@link Publisher} which emits all data read from the underlying channel.
     * @param terminalMsgPredicate {@link TerminalPredicate} to detect end of a <i>response</i>.
     * @param closeHandler handles connection closure and half-closure.
     */
    public NettyConnection(Channel channel, ConnectionContext context, Publisher<Read> read,
                           TerminalPredicate<Read> terminalMsgPredicate,
                           CloseHandler closeHandler) {
        this.channel = requireNonNull(channel);
        this.context = requireNonNull(context);
        this.read = requireNonNull(read);
        this.terminalMsgPredicate = requireNonNull(terminalMsgPredicate);
        this.closeHandler = requireNonNull(closeHandler);
        if (closeHandler == NOOP_CLOSE_HANDLER) {
            onClosing = onClose();
        } else {
            onClosing = new CompletableProcessor();
            closeHandler.registerEventHandler(channel, evt -> { // Called from EventLoop only!
                if (closeReason == null) {
                    closeReason = evt;
                    LOGGER.debug("{} Emitted CloseEvent: {}", channel, evt);
                    ((CompletableProcessor) onClosing).onComplete();
                }
            });
        }
        channel.pipeline().addLast(new ChannelInboundHandler() {
            @Override
            public void channelWritabilityChanged(ChannelHandlerContext ctx) {
                if (ctx.channel().isWritable()) {
                    writableListener.channelWritable();
                }
            }

            @Override
            public void handlerAdded(ChannelHandlerContext ctx) {
            }

            @Override
            public void handlerRemoved(ChannelHandlerContext ctx) {
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                LOGGER.error("unexpected exception reached the end of the pipeline for channel={}", ctx.channel(), cause);
            }

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (!readInProgress) {
                    readInProgress = true;
                }
                release(msg);
            }

            @Override
            public void channelReadComplete(ChannelHandlerContext ctx) {
                readInProgress = false;
                ReadAwareFlushStrategyHolder<Write> holder = NettyConnection.this.readAwareFlushStrategyHolder;
                if (holder != null) {
                    holder.readComplete();
                }
            }

            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                if (evt == ChannelInputShutdownReadComplete.INSTANCE) {
                    // ChannelInputShutdownEvent is not always triggered and can get triggered before we tried to read
                    // all the available data. ChannelInputShutdownReadComplete is the one that seems to (at least in
                    // the current netty version) gets triggered reliably at the appropriate time.
                    closeHandler.channelClosedInbound(ctx);
                } else if (evt == ChannelOutputShutdownEvent.INSTANCE) {
                    closeHandler.channelClosedOutbound(ctx);
                }
                release(evt);
            }

            @Override
            public void channelRegistered(ChannelHandlerContext ctx) {
            }

            @Override
            public void channelUnregistered(ChannelHandlerContext ctx) {
            }

            @Override
            public void channelActive(ChannelHandlerContext ctx) {
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                writableListener.channelClosed(CLOSED_CHANNEL_INACTIVE);
            }
        });
    }

    private void cleanupOnWriteTerminated() {
        readAwareFlushStrategyHolder = null;
        writableListener = PLACE_HOLDER_WRITABLE_LISTENER;
    }

    @Override
    public Publisher<Read> read() {
        return read;
    }

    @Override
    public TerminalPredicate<Read> getTerminalMsgPredicate() {
        return terminalMsgPredicate;
    }

    @Override
    public Completable write(Publisher<Write> write, FlushStrategy flushStrategy) {
        return write(write, flushStrategy, RequestNSupplier::newDefaultSupplier);
    }

    @Override
    public Completable write(Publisher<Write> write, FlushStrategy flushStrategy, Supplier<RequestNSupplier> requestNSupplierFactory) {
        return write(flushStrategy.apply(requireNonNull(write)), requestNSupplierFactory);
    }

    private Completable write(FlushStrategyHolder<Write> writeWithFlush, Supplier<RequestNSupplier> requestNSupplierFactory) {
        return cleanupStateWhenDone(new Completable() {
            @Override
            protected void handleSubscribe(Subscriber completableSubscriber) {
                WriteStreamSubscriber subscriber = new WriteStreamSubscriber(channel, requestNSupplierFactory.get(), completableSubscriber);
                if (failIfWriteActive(subscriber, completableSubscriber)) {
                    if (writeWithFlush instanceof ReadAwareFlushStrategyHolder) {
                        ReadAwareFlushStrategyHolder<Write> holder = (ReadAwareFlushStrategyHolder<Write>) writeWithFlush;
                        holder.setReadInProgressSupplier(readInProgressSupplier);
                        readAwareFlushStrategyHolder = holder;
                    }
                    composeFlushes(channel, writeWithFlush.getSource(), writeWithFlush.getFlushSignals())
                            .subscribe(subscriber);
                }
            }
        });
    }

    @Override
    public Completable writeAndFlush(Single<Write> write) {
        requireNonNull(write);
        return cleanupStateWhenDone(new Completable() {
            @Override
            protected void handleSubscribe(Subscriber completableSubscriber) {
                WriteSingleSubscriber subscriber = new WriteSingleSubscriber(channel, requireNonNull(completableSubscriber));
                if (failIfWriteActive(subscriber, completableSubscriber)) {
                    write.subscribe(subscriber);
                }
            }
        });
    }

    @Override
    public Completable writeAndFlush(Write write) {
        requireNonNull(write);
        return cleanupStateWhenDone(new NettyFutureCompletable(() -> {
            if (writableListenerUpdater.compareAndSet(NettyConnection.this, PLACE_HOLDER_WRITABLE_LISTENER, SINGLE_ITEM_WRITABLE_LISTENER)) {
                return channel.writeAndFlush(write);
            }
            return channel.newFailedFuture(new IllegalStateException("A write is already active on this connection."));
        }));
    }

    /**
     * This connection does not allow concurrent writes and so this method can determine if there is a writing pending.
     *
     * @return {@code true} if a write is already active.
     */
    boolean isWriteActive() {
        return writableListener != PLACE_HOLDER_WRITABLE_LISTENER;
    }

    @Override
    public Completable closeAsync() {
        return context.closeAsync();
    }

    @Override
    public Completable closeAsyncGracefully() {
        return new Completable() {
            @Override
            protected void handleSubscribe(final Subscriber subscriber) {
                onClose().subscribe(subscriber);
                EventLoop eventLoop = channel.eventLoop();
                if (eventLoop.inEventLoop()) {
                    invokeUserCloseHandler();
                } else {
                    eventLoop.execute(NettyConnection.this::invokeUserCloseHandler);
                }
            }
        };
    }

    @Override
    public Completable onClose() {
        return context.onClose();
    }

    @Override
    public SocketAddress getLocalAddress() {
        return context.getLocalAddress();
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return context.getRemoteAddress();
    }

    @Override
    public SSLSession getSslSession() {
        return context.getSslSession();
    }

    @Override
    public ExecutionContext getExecutionContext() {
        return context.getExecutionContext();
    }

    @Override
    public NettyIoExecutor getIoExecutor() {
        return toNettyIoExecutor(context.getIoExecutor());
    }

    private void invokeUserCloseHandler() {
        closeHandler.userClosing(channel);
    }

    @Override
    public Completable onClosing() {
        return onClosing;
    }

    @Override
    public String toString() {
        return channel.toString();
    }

    private Completable cleanupStateWhenDone(Completable completable) {
        // This must happen before we actually trigger the original Subscribers methods so using doBefore* variants.
        return completable.doBeforeFinally(this::cleanupOnWriteTerminated);
    }

    private boolean failIfWriteActive(WritableListener newWritableListener, Subscriber subscriber) {
        if (writableListenerUpdater.compareAndSet(this, PLACE_HOLDER_WRITABLE_LISTENER, newWritableListener)) {
            // It is possible that we have set the writeSubscriber, then the channel becomes inactive, and we will
            // never notify the write writeSubscriber of the inactive event. So if the channel is inactive we notify
            // the writeSubscriber.
            if (!channel.isActive()) {
                newWritableListener.channelClosed(CLOSED_FAIL_ACTIVE);
                return false;
            }
            return true;
        }
        subscriber.onSubscribe(IGNORE_CANCEL);
        subscriber.onError(new IllegalStateException("A write is already active on this connection."));
        return false;
    }

    interface WritableListener {
        /**
         * Notification that the writability of the channel has changed.
         * <p>
         * Always called from the event loop thread.
         */
        void channelWritable();

        /**
         * Notification that the channel has been closed.
         * <p>
         * This may not always be called from the event loop. For example if the channel is closed when a new write
         * happens then this method will be called from the writer thread.
         *
         * @param closedException the exception which describes the close rational.
         */
        void channelClosed(Throwable closedException);
    }

    private static final class NoopWritableListener implements WritableListener {
        @Override
        public void channelWritable() {
        }

        @Override
        public void channelClosed(Throwable closedException) {
        }
    }
}

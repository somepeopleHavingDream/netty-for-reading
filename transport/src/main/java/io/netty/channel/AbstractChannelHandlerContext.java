/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ResourceLeakHint;
import io.netty.util.concurrent.AbstractEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.OrderedEventExecutor;
import io.netty.util.internal.*;
import io.netty.util.internal.ObjectPool.Handle;
import io.netty.util.internal.ObjectPool.ObjectCreator;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static io.netty.channel.ChannelHandlerMask.*;

abstract class AbstractChannelHandlerContext implements ChannelHandlerContext, ResourceLeakHint {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractChannelHandlerContext.class);
    volatile AbstractChannelHandlerContext next;
    volatile AbstractChannelHandlerContext prev;

    /**
     * 处理者状态更新器
     */
    private static final AtomicIntegerFieldUpdater<AbstractChannelHandlerContext> HANDLER_STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(AbstractChannelHandlerContext.class, "handlerState");

    /**
     * {@link ChannelHandler#handlerAdded(ChannelHandlerContext)} is about to be called.
     *
     * 通道处理者的添加处理者将被调用。
     */
    private static final int ADD_PENDING = 1;

    /**
     * {@link ChannelHandler#handlerAdded(ChannelHandlerContext)} was called.
     *
     * 通道处理者的添加处理者被调用。
     */
    private static final int ADD_COMPLETE = 2;

    /**
     * {@link ChannelHandler#handlerRemoved(ChannelHandlerContext)} was called.
     *
     * 处理者移除方法被调用。
     */
    private static final int REMOVE_COMPLETE = 3;
    /**
     * Neither {@link ChannelHandler#handlerAdded(ChannelHandlerContext)}
     * nor {@link ChannelHandler#handlerRemoved(ChannelHandlerContext)} was called.
     */
    private static final int INIT = 0;

    private final DefaultChannelPipeline pipeline;
    private final String name;
    private final boolean ordered;
    private final int executionMask;

    // Will be set to null if no child executor should be used, otherwise it will be set to the
    // child executor.

    /**
     * 如果没有子执行器应该被使用，将被设置为null，否则它将被设置为子执行器。
     */
    final EventExecutor executor;
    private ChannelFuture succeededFuture;

    // Lazily instantiated tasks used to trigger events to a handler with different executor.
    // There is no need to make this volatile as at worse it will just create a few more instances then needed.
    private Tasks invokeTasks;

    /**
     * 处理者状态，初始值为INIT
     */
    private volatile int handlerState = INIT;

    AbstractChannelHandlerContext(DefaultChannelPipeline pipeline, EventExecutor executor,
                                  String name, Class<? extends ChannelHandler> handlerClass) {
        // 设置通道处理者上下文的名称、流水线、执行器、执行掩码
        this.name = ObjectUtil.checkNotNull(name, "name");
        this.pipeline = pipeline;
        this.executor = executor;
        this.executionMask = mask(handlerClass);

        // Its ordered if its driven by the EventLoop or the given Executor is an instanceof OrderedEventExecutor.
        // 如果事件循环或者给定执行器是顺序事件执行器的实例来驱动它，则是顺序的。
        ordered = executor == null || executor instanceof OrderedEventExecutor;
    }

    @Override
    public Channel channel() {
        return pipeline.channel();
    }

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public ByteBufAllocator alloc() {
        return channel().config().getAllocator();
    }

    @Override
    public EventExecutor executor() {
        // 如果当前通道处理者上下文的执行器不存在，则返回该通道的事件循环，否则就直接返回事件执行器
        if (executor == null) {
            return channel().eventLoop();
        } else {
            return executor;
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ChannelHandlerContext fireChannelRegistered() {
        invokeChannelRegistered(findContextInbound(MASK_CHANNEL_REGISTERED));
        return this;
    }

    static void invokeChannelRegistered(final AbstractChannelHandlerContext next) {
        // 拿到通道处理者上下文的事件执行器
        EventExecutor executor = next.executor();
        // 如果事件执行器处于事件循环
        if (executor.inEventLoop()) {
            // 调用注册通道方法
            next.invokeChannelRegistered();
        } else {
            /*
                以下不细究
             */
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelRegistered();
                }
            });
        }
    }

    /**
     * 调用注册通道
     */
    private void invokeChannelRegistered() {
        // 如果能调用处理者
        if (invokeHandler()) {
            try {
                // 获得处理者，强转为通道入境处理者，再调用处理者的注册通道方法（一般为用户代码）
                ((ChannelInboundHandler) handler()).channelRegistered(this);
            } catch (Throwable t) {
                // 以下不细究
                invokeExceptionCaught(t);
            }
        } else {
            // 不细究
            fireChannelRegistered();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelUnregistered() {
        invokeChannelUnregistered(findContextInbound(MASK_CHANNEL_UNREGISTERED));
        return this;
    }

    static void invokeChannelUnregistered(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelUnregistered();
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelUnregistered();
                }
            });
        }
    }

    private void invokeChannelUnregistered() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelUnregistered(this);
            } catch (Throwable t) {
                invokeExceptionCaught(t);
            }
        } else {
            fireChannelUnregistered();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelActive() {
        // 调用激活通道方法
        invokeChannelActive(findContextInbound(MASK_CHANNEL_ACTIVE));
        return this;
    }

    /**
     * 调用激活通道方法
     *
     * @param next 通道处理者上下文
     */
    static void invokeChannelActive(final AbstractChannelHandlerContext next) {
        // 获得入参通道处理者上下文的事件执行器
        EventExecutor executor = next.executor();
        // 如果该事件执行器处于事件循环中，则由该事件执行器调用激活通道方法
        if (executor.inEventLoop()) {
            // 通道处理者上下文调用激活通道方法
            next.invokeChannelActive();
        } else {
            // 不细究
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelActive();
                }
            });
        }
    }

    /**
     * 调用激活通道
     */
    private void invokeChannelActive() {
        // 如果能调用处理者
        if (invokeHandler()) {
            try {
                // 获得处理者，强转为通道入境处理者，调用激活通道方法
                ((ChannelInboundHandler) handler()).channelActive(this);
            } catch (Throwable t) {
                // 不细究
                invokeExceptionCaught(t);
            }
        } else {
            // 不细究
            fireChannelActive();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelInactive() {
        invokeChannelInactive(findContextInbound(MASK_CHANNEL_INACTIVE));
        return this;
    }

    static void invokeChannelInactive(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelInactive();
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelInactive();
                }
            });
        }
    }

    private void invokeChannelInactive() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelInactive(this);
            } catch (Throwable t) {
                invokeExceptionCaught(t);
            }
        } else {
            fireChannelInactive();
        }
    }

    @Override
    public ChannelHandlerContext fireExceptionCaught(final Throwable cause) {
        // 调用异常捕获
        invokeExceptionCaught(findContextInbound(MASK_EXCEPTION_CAUGHT), cause);
        // 返回当前通道处理者上下文
        return this;
    }

    /**
     * 调用异常捕获
     *
     * @param next 通道处理者上下文
     * @param cause 可抛出实例
     */
    static void invokeExceptionCaught(final AbstractChannelHandlerContext next, final Throwable cause) {
        // 检查入参可抛出实例不为null
        ObjectUtil.checkNotNull(cause, "cause");

        // 获得入参通道处理者上下文的执行器
        EventExecutor executor = next.executor();
        // 如果当前线程处于事件循环
        if (executor.inEventLoop()) {
            // 通道处理者上下文调用异常处理
            next.invokeExceptionCaught(cause);
        } else {
            try {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        next.invokeExceptionCaught(cause);
                    }
                });
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("Failed to submit an exceptionCaught() event.", t);
                    logger.warn("The exceptionCaught() event that was failed to submit was:", cause);
                }
            }
        }
    }

    /**
     * 调用异常捕捉
     *
     * @param cause 可抛出实例
     */
    private void invokeExceptionCaught(final Throwable cause) {
        // 如果能调用处理者
        if (invokeHandler()) {
            try {
                // 获得通道处理者，调用异常捕获方法
                handler().exceptionCaught(this, cause);
            } catch (Throwable error) {
                if (logger.isDebugEnabled()) {
                    logger.debug(
                        "An exception {}" +
                        "was thrown by a user handler's exceptionCaught() " +
                        "method while handling the following exception:",
                        ThrowableUtil.stackTraceToString(error), cause);
                } else if (logger.isWarnEnabled()) {
                    logger.warn(
                        "An exception '{}' [enable DEBUG level for full stacktrace] " +
                        "was thrown by a user handler's exceptionCaught() " +
                        "method while handling the following exception:", error, cause);
                }
            }
        } else {
            fireExceptionCaught(cause);
        }
    }

    @Override
    public ChannelHandlerContext fireUserEventTriggered(final Object event) {
        invokeUserEventTriggered(findContextInbound(MASK_USER_EVENT_TRIGGERED), event);
        return this;
    }

    static void invokeUserEventTriggered(final AbstractChannelHandlerContext next, final Object event) {
        ObjectUtil.checkNotNull(event, "event");
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeUserEventTriggered(event);
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeUserEventTriggered(event);
                }
            });
        }
    }

    private void invokeUserEventTriggered(Object event) {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).userEventTriggered(this, event);
            } catch (Throwable t) {
                invokeExceptionCaught(t);
            }
        } else {
            fireUserEventTriggered(event);
        }
    }

    @Override
    public ChannelHandlerContext fireChannelRead(final Object msg) {
        // 先找到入境通道处理者上下文，调用读通道方法
        invokeChannelRead(findContextInbound(MASK_CHANNEL_READ), msg);
        return this;
    }

    /**
     * 调用通道读
     *
     * @param next 当前通道处理者上下文
     * @param msg 消息
     */
    static void invokeChannelRead(final AbstractChannelHandlerContext next, Object msg) {
        // 入参通道处理者上下文所在的流水线做触摸操作，获得触摸后的消息对象
        final Object m = next.pipeline.touch(ObjectUtil.checkNotNull(msg, "msg"), next);
        // 获得当前通道处理者上下文的执行器
        EventExecutor executor = next.executor();
        // 如果当前通道处理者上下文环境的执行器处于事件循环中
        if (executor.inEventLoop()) {
            // 入参通道处理者上下文调用读通道方法
            next.invokeChannelRead(m);
        } else {
            /*
                以下不细究
             */
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelRead(m);
                }
            });
        }
    }

    /**
     * 调用读通道方法
     *
     * @param msg 消息
     */
    private void invokeChannelRead(Object msg) {
        // 当前通道处理者上下文是否能调用处理者
        if (invokeHandler()) {
            try {
                // 获得当前通道处理者上下文的通道处理者，使用该通道处理者调用读通道方法
                ((ChannelInboundHandler) handler()).channelRead(this, msg);
            } catch (Throwable t) {
                // 调用异常捕捉
                invokeExceptionCaught(t);
            }
        } else {
            // 不细究
            fireChannelRead(msg);
        }
    }

    @Override
    public ChannelHandlerContext fireChannelReadComplete() {
        invokeChannelReadComplete(findContextInbound(MASK_CHANNEL_READ_COMPLETE));
        return this;
    }

    /**
     * 调用通道读完成方法
     *
     * @param next 通道处理者上下文
     */
    static void invokeChannelReadComplete(final AbstractChannelHandlerContext next) {
        // 获得入参通道处理者上下文的事件执行器
        EventExecutor executor = next.executor();
        // 如果事件执行器处于事件循环之中
        if (executor.inEventLoop()) {
            // 通道处理者上下文调用通道读完成方法
            next.invokeChannelReadComplete();
        } else {
            Tasks tasks = next.invokeTasks;
            if (tasks == null) {
                next.invokeTasks = tasks = new Tasks(next);
            }
            executor.execute(tasks.invokeChannelReadCompleteTask);
        }
    }

    /**
     * 调用通道读完成
     */
    private void invokeChannelReadComplete() {
        // 如果能调用处理者
        if (invokeHandler()) {
            try {
                // 获得当前通道处理者，再调用该通道的读完成方法
                ((ChannelInboundHandler) handler()).channelReadComplete(this);
            } catch (Throwable t) {
                // 不细究
                invokeExceptionCaught(t);
            }
        } else {
            // 不细究
            fireChannelReadComplete();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelWritabilityChanged() {
        invokeChannelWritabilityChanged(findContextInbound(MASK_CHANNEL_WRITABILITY_CHANGED));
        return this;
    }

    static void invokeChannelWritabilityChanged(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelWritabilityChanged();
        } else {
            Tasks tasks = next.invokeTasks;
            if (tasks == null) {
                next.invokeTasks = tasks = new Tasks(next);
            }
            executor.execute(tasks.invokeChannelWritableStateChangedTask);
        }
    }

    private void invokeChannelWritabilityChanged() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelWritabilityChanged(this);
            } catch (Throwable t) {
                invokeExceptionCaught(t);
            }
        } else {
            fireChannelWritabilityChanged();
        }
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return bind(localAddress, newPromise());
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return connect(remoteAddress, newPromise());
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return connect(remoteAddress, localAddress, newPromise());
    }

    @Override
    public ChannelFuture disconnect() {
        return disconnect(newPromise());
    }

    @Override
    public ChannelFuture close() {
        return close(newPromise());
    }

    @Override
    public ChannelFuture deregister() {
        return deregister(newPromise());
    }

    @Override
    public ChannelFuture bind(final SocketAddress localAddress, final ChannelPromise promise) {
        // 检查本地地址
        ObjectUtil.checkNotNull(localAddress, "localAddress");
        // 如果不是有效的通道承诺，则直接返回入参通道承诺
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        // 找到出境上下文
        final AbstractChannelHandlerContext next = findContextOutbound(MASK_BIND);
        // 获得出境上下文的事件执行器
        EventExecutor executor = next.executor();
        // 如果事件执行器处于事件循环
        if (executor.inEventLoop()) {
            // 通道处理者上下文调用绑定方法
            next.invokeBind(localAddress, promise);
        } else {
            /*
                以下不细究
             */
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeBind(localAddress, promise);
                }
            }, promise, null, false);
        }
        return promise;
    }

    /**
     * 调用绑定
     *
     * @param localAddress 本地地址
     * @param promise 通道承诺
     */
    private void invokeBind(SocketAddress localAddress, ChannelPromise promise) {
        // 如果能调用处理者
        if (invokeHandler()) {
            try {
                // 获得通道处理者，强转为通道出境处理者，做绑定操作
                ((ChannelOutboundHandler) handler()).bind(this, localAddress, promise);
            } catch (Throwable t) {
                // 不细究
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            // 不细究
            bind(localAddress, promise);
        }
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return connect(remoteAddress, null, promise);
    }

    @Override
    public ChannelFuture connect(
            final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
        ObjectUtil.checkNotNull(remoteAddress, "remoteAddress");

        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        final AbstractChannelHandlerContext next = findContextOutbound(MASK_CONNECT);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeConnect(remoteAddress, localAddress, promise);
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeConnect(remoteAddress, localAddress, promise);
                }
            }, promise, null, false);
        }
        return promise;
    }

    private void invokeConnect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).connect(this, remoteAddress, localAddress, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            connect(remoteAddress, localAddress, promise);
        }
    }

    @Override
    public ChannelFuture disconnect(final ChannelPromise promise) {
        if (!channel().metadata().hasDisconnect()) {
            // Translate disconnect to close if the channel has no notion of disconnect-reconnect.
            // So far, UDP/IP is the only transport that has such behavior.
            return close(promise);
        }
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        final AbstractChannelHandlerContext next = findContextOutbound(MASK_DISCONNECT);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeDisconnect(promise);
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeDisconnect(promise);
                }
            }, promise, null, false);
        }
        return promise;
    }

    private void invokeDisconnect(ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).disconnect(this, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            disconnect(promise);
        }
    }

    @Override
    public ChannelFuture close(final ChannelPromise promise) {
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        final AbstractChannelHandlerContext next = findContextOutbound(MASK_CLOSE);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeClose(promise);
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeClose(promise);
                }
            }, promise, null, false);
        }

        return promise;
    }

    private void invokeClose(ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).close(this, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            close(promise);
        }
    }

    @Override
    public ChannelFuture deregister(final ChannelPromise promise) {
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        final AbstractChannelHandlerContext next = findContextOutbound(MASK_DEREGISTER);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeDeregister(promise);
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeDeregister(promise);
                }
            }, promise, null, false);
        }

        return promise;
    }

    private void invokeDeregister(ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).deregister(this, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            deregister(promise);
        }
    }

    @Override
    public ChannelHandlerContext read() {
        // 找到出境上下文，获得通道处理者上下文
        final AbstractChannelHandlerContext next = findContextOutbound(MASK_READ);
        // 拿到出境上下文的事件执行器
        EventExecutor executor = next.executor();
        // 如果当前的通道处理者上下文的执行器处于事件循环中
        if (executor.inEventLoop()) {
            // 通道处理者上下文调用读方法
            next.invokeRead();
        } else {
            /*
                以下不细究
             */
            Tasks tasks = next.invokeTasks;
            if (tasks == null) {
                next.invokeTasks = tasks = new Tasks(next);
            }
            executor.execute(tasks.invokeReadTask);
        }

        return this;
    }

    /**
     * 调用读方法
     */
    private void invokeRead() {
        // 如果能调用处理者
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).read(this);
            } catch (Throwable t) {
                invokeExceptionCaught(t);
            }
        } else {
            read();
        }
    }

    @Override
    public ChannelFuture write(Object msg) {
        return write(msg, newPromise());
    }

    @Override
    public ChannelFuture write(final Object msg, final ChannelPromise promise) {
        // 做写消息操作
        write(msg, false, promise);

        // 返回承诺
        return promise;
    }

    /**
     * 调用写方法
     *
     * @param msg 消息
     * @param promise 通道承诺
     */
    void invokeWrite(Object msg, ChannelPromise promise) {
        // 如果能调用处理者
        if (invokeHandler()) {
            invokeWrite0(msg, promise);
        } else {
            // 不细究
            write(msg, promise);
        }
    }

    /**
     * 调用写方法
     *
     * @param msg 消息
     * @param promise 通道承诺
     */
    private void invokeWrite0(Object msg, ChannelPromise promise) {
        try {
            ((ChannelOutboundHandler) handler()).write(this, msg, promise);
        } catch (Throwable t) {
            notifyOutboundHandlerException(t, promise);
        }
    }

    @Override
    public ChannelHandlerContext flush() {
        final AbstractChannelHandlerContext next = findContextOutbound(MASK_FLUSH);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeFlush();
        } else {
            Tasks tasks = next.invokeTasks;
            if (tasks == null) {
                next.invokeTasks = tasks = new Tasks(next);
            }
            safeExecute(executor, tasks.invokeFlushTask, channel().voidPromise(), null, false);
        }

        return this;
    }

    private void invokeFlush() {
        if (invokeHandler()) {
            invokeFlush0();
        } else {
            flush();
        }
    }

    private void invokeFlush0() {
        try {
            ((ChannelOutboundHandler) handler()).flush(this);
        } catch (Throwable t) {
            invokeExceptionCaught(t);
        }
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        write(msg, true, promise);
        return promise;
    }

    void invokeWriteAndFlush(Object msg, ChannelPromise promise) {
        if (invokeHandler()) {
            invokeWrite0(msg, promise);
            invokeFlush0();
        } else {
            writeAndFlush(msg, promise);
        }
    }

    /**
     * 写
     * 
     * @param msg 需要写入的消息
     * @param flush 写入后是否做冲刷操作
     * @param promise 通道承诺
     */
    private void write(Object msg, boolean flush, ChannelPromise promise) {
        // 检查入参消息是否不为null
        ObjectUtil.checkNotNull(msg, "msg");
        try {
            // 如果不是一个有效的承诺
            if (isNotValidPromise(promise, true)) {
                /*
                    以下不细究
                 */
                ReferenceCountUtil.release(msg);
                // cancelled
                return;
            }
        } catch (RuntimeException e) {
            /*
                以下不细究
             */
            ReferenceCountUtil.release(msg);
            throw e;
        }

        // 找到出境上下文
        final AbstractChannelHandlerContext next = findContextOutbound(flush ?
                (MASK_WRITE | MASK_FLUSH) : MASK_WRITE);
        // 触摸消息
        final Object m = pipeline.touch(msg, next);
        // 获得出境上下文的事件执行器
        EventExecutor executor = next.executor();
        // 如果当前线程处在事件执行器中
        if (executor.inEventLoop()) {
            // 是否刷新消息
            if (flush) {
                next.invokeWriteAndFlush(m, promise);
            } else {
                // 通道处理者上下文调用写方法
                next.invokeWrite(m, promise);
            }
        } else {
            /*
                以下不细究
             */
            final WriteTask task = WriteTask.newInstance(next, m, promise, flush);
            if (!safeExecute(executor, task, promise, m, !flush)) {
                // We failed to submit the WriteTask. We need to cancel it so we decrement the pending bytes
                // and put it back in the Recycler for re-use later.
                //
                // See https://github.com/netty/netty/issues/8343.
                task.cancel();
            }
        }
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        return writeAndFlush(msg, newPromise());
    }

    private static void notifyOutboundHandlerException(Throwable cause, ChannelPromise promise) {
        // Only log if the given promise is not of type VoidChannelPromise as tryFailure(...) is expected to return
        // false.
        PromiseNotificationUtil.tryFailure(promise, cause, promise instanceof VoidChannelPromise ? null : logger);
    }

    @Override
    public ChannelPromise newPromise() {
        // 实例化并返回默认通道承诺
        return new DefaultChannelPromise(channel(), executor());
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
        return new DefaultChannelProgressivePromise(channel(), executor());
    }

    @Override
    public ChannelFuture newSucceededFuture() {
        ChannelFuture succeededFuture = this.succeededFuture;
        if (succeededFuture == null) {
            this.succeededFuture = succeededFuture = new SucceededChannelFuture(channel(), executor());
        }
        return succeededFuture;
    }

    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        return new FailedChannelFuture(channel(), executor(), cause);
    }

    /**
     * 是否不是有效的承诺
     *
     * 不细究
     *
     * @param promise 通道承诺
     * @param allowVoidPromise 允许void承诺
     * @return 是否不是有效的承诺
     */
    private boolean isNotValidPromise(ChannelPromise promise, boolean allowVoidPromise) {
        // 检查入参通道承诺
        ObjectUtil.checkNotNull(promise, "promise");

        // 如果通道承诺已经完成
        if (promise.isDone()) {
            // Check if the promise was cancelled and if so signal that the processing of the operation
            // should not be performed.
            //
            // See https://github.com/netty/netty/issues/2349
            if (promise.isCancelled()) {
                return true;
            }
            throw new IllegalArgumentException("promise already done: " + promise);
        }

        if (promise.channel() != channel()) {
            throw new IllegalArgumentException(String.format(
                    "promise.channel does not match: %s (expected: %s)", promise.channel(), channel()));
        }

        if (promise.getClass() == DefaultChannelPromise.class) {
            return false;
        }

        if (!allowVoidPromise && promise instanceof VoidChannelPromise) {
            throw new IllegalArgumentException(
                    StringUtil.simpleClassName(VoidChannelPromise.class) + " not allowed for this operation");
        }

        if (promise instanceof AbstractChannel.CloseFuture) {
            throw new IllegalArgumentException(
                    StringUtil.simpleClassName(AbstractChannel.CloseFuture.class) + " not allowed in a pipeline");
        }
        return false;
    }

    /**
     * 找到入境通道处理者上下文
     *
     * @param mask 掩码
     * @return 通道处理者上下文
     */
    private AbstractChannelHandlerContext findContextInbound(int mask) {
        // 获得当前通道处理者上下文和当前的事件执行器
        AbstractChannelHandlerContext ctx = this;
        EventExecutor currentExecutor = executor();

        // 找到符合条件的通道处理者上下文
        do {
            ctx = ctx.next;
        } while (skipContext(ctx, currentExecutor, mask, MASK_ONLY_INBOUND));

        // 返回通道处理者上下文
        return ctx;
    }

    /**
     * 找到出境上下文
     *
     * @param mask 掩码
     * @return 出境上下文
     */
    private AbstractChannelHandlerContext findContextOutbound(int mask) {
        // 拿到当前通道处理者上下文
        AbstractChannelHandlerContext ctx = this;
        // 拿到执行器
        EventExecutor currentExecutor = executor();
        do {
            ctx = ctx.prev;
            // 跳过上下文（暂不细究）
        } while (skipContext(ctx, currentExecutor, mask, MASK_ONLY_OUTBOUND));

        // 返回出境上下文
        return ctx;
    }

    /**
     * 跳过上下文
     *
     * （该方法不细究）
     *
     * @param ctx 通道处理者上下文
     * @param currentExecutor 当前执行器
     * @param mask 掩码
     * @param onlyMask 唯一掩码
     * @return 是否能跳过上下文
     */
    private static boolean skipContext(
            AbstractChannelHandlerContext ctx, EventExecutor currentExecutor, int mask, int onlyMask) {
        // Ensure we correctly handle MASK_EXCEPTION_CAUGHT which is not included in the MASK_EXCEPTION_CAUGHT
        return (ctx.executionMask & (onlyMask | mask)) == 0 ||
                // We can only skip if the EventExecutor is the same as otherwise we need to ensure we offload
                // everything to preserve ordering.
                //
                // See https://github.com/netty/netty/issues/10067
                (ctx.executor() == currentExecutor && (ctx.executionMask & mask) == 0);
    }

    @Override
    public ChannelPromise voidPromise() {
        return channel().voidPromise();
    }

    final void setRemoved() {
        handlerState = REMOVE_COMPLETE;
    }

    /**
     * 设置添加完成
     *
     * @return 是否设置添加完成成功
     */
    final boolean setAddComplete() {
        for (;;) {
            // 获得现在的处理者状态
            int oldState = handlerState;
            // 如果处理者已被移除，则返回假
            if (oldState == REMOVE_COMPLETE) {
                return false;
            }

            // Ensure we never update when the handlerState is REMOVE_COMPLETE already.
            // oldState is usually ADD_PENDING but can also be REMOVE_COMPLETE when an EventExecutor is used that is not
            // exposing ordering guarantees.
            /*
                当处理者状态已经是移除完成，确保我们不更新。
                旧的状态通常是添加待办中，但是也可以是移除完成，当使用的事件执行器不保证公开有序时。
             */
            if (HANDLER_STATE_UPDATER.compareAndSet(this, oldState, ADD_COMPLETE)) {
                // 如果更改状态为添加完成成功，则返回真
                return true;
            }
        }
    }

    /**
     * 设置添加待办状态
     */
    final void setAddPending() {
        // 将通道处理者上下文的状态从初始化改为添加待办
        boolean updated = HANDLER_STATE_UPDATER.compareAndSet(this, INIT, ADD_PENDING);

        // 断言：这应该总是真，因为它必须在设置添加完成或者设置移除之前被调用。
        assert updated; // This should always be true as it MUST be called before setAddComplete() or setRemoved().
    }

    /**
     * 调用添加处理者
     *
     * @throws Exception 异常
     */
    final void callHandlerAdded() throws Exception {
        // We must call setAddComplete before calling handlerAdded. Otherwise if the handlerAdded method generates
        // any pipeline events ctx.handler() will miss them because the state will not allow it.
        /*
            我们必须在调用添加处理者之前调用设置添加完成。
            否则，如果添加通道方法生成任务流水线事件，通道处理者上下文将丢失它们，因为状态不允许。
         */
        // 设置添加完成
        if (setAddComplete()) {
            // 如果设置添加完成成功，则获得该通道处理者，执行添加处理者方法
            handler().handlerAdded(this);
        }
    }

    final void callHandlerRemoved() throws Exception {
        try {
            // Only call handlerRemoved(...) if we called handlerAdded(...) before.
            if (handlerState == ADD_COMPLETE) {
                handler().handlerRemoved(this);
            }
        } finally {
            // Mark the handler as removed in any case.
            setRemoved();
        }
    }

    /**
     * Makes best possible effort to detect if {@link ChannelHandler#handlerAdded(ChannelHandlerContext)} was called
     * yet. If not return {@code false} and if called or could not detect return {@code true}.
     *
     * If this method returns {@code false} we will not invoke the {@link ChannelHandler} but just forward the event.
     * This is needed as {@link DefaultChannelPipeline} may already put the {@link ChannelHandler} in the linked-list
     * but not called {@link ChannelHandler#handlerAdded(ChannelHandlerContext)}.
     */
    private boolean invokeHandler() {
        // Store in local variable to reduce volatile reads.
        // 存进本地变量，以减少易变读。
        int handlerState = this.handlerState;

        /*
         * 如果处理者状态满足以下任一两种条件之一，就返回真
         * 1 处理者状态为添加完成
         * 2 无序，并且处理者状态为添加待办
         */
        return handlerState == ADD_COMPLETE || (!ordered && handlerState == ADD_PENDING);
    }

    @Override
    public boolean isRemoved() {
        return handlerState == REMOVE_COMPLETE;
    }

    @Override
    public <T> Attribute<T> attr(AttributeKey<T> key) {
        return channel().attr(key);
    }

    @Override
    public <T> boolean hasAttr(AttributeKey<T> key) {
        return channel().hasAttr(key);
    }

    private static boolean safeExecute(EventExecutor executor, Runnable runnable,
            ChannelPromise promise, Object msg, boolean lazy) {
        try {
            if (lazy && executor instanceof AbstractEventExecutor) {
                ((AbstractEventExecutor) executor).lazyExecute(runnable);
            } else {
                executor.execute(runnable);
            }
            return true;
        } catch (Throwable cause) {
            try {
                if (msg != null) {
                    ReferenceCountUtil.release(msg);
                }
            } finally {
                promise.setFailure(cause);
            }
            return false;
        }
    }

    @Override
    public String toHintString() {
        return '\'' + name + "' will handle the message from this point.";
    }

    @Override
    public String toString() {
        return StringUtil.simpleClassName(ChannelHandlerContext.class) + '(' + name + ", " + channel() + ')';
    }

    static final class WriteTask implements Runnable {
        private static final ObjectPool<WriteTask> RECYCLER = ObjectPool.newPool(new ObjectCreator<WriteTask>() {
            @Override
            public WriteTask newObject(Handle<WriteTask> handle) {
                return new WriteTask(handle);
            }
        });

        static WriteTask newInstance(AbstractChannelHandlerContext ctx,
                Object msg, ChannelPromise promise, boolean flush) {
            WriteTask task = RECYCLER.get();
            init(task, ctx, msg, promise, flush);
            return task;
        }

        private static final boolean ESTIMATE_TASK_SIZE_ON_SUBMIT =
                SystemPropertyUtil.getBoolean("io.netty.transport.estimateSizeOnSubmit", true);

        // Assuming compressed oops, 12 bytes obj header, 4 ref fields and one int field
        private static final int WRITE_TASK_OVERHEAD =
                SystemPropertyUtil.getInt("io.netty.transport.writeTaskSizeOverhead", 32);

        private final Handle<WriteTask> handle;
        private AbstractChannelHandlerContext ctx;
        private Object msg;
        private ChannelPromise promise;
        private int size; // sign bit controls flush

        @SuppressWarnings("unchecked")
        private WriteTask(Handle<? extends WriteTask> handle) {
            this.handle = (Handle<WriteTask>) handle;
        }

        protected static void init(WriteTask task, AbstractChannelHandlerContext ctx,
                                   Object msg, ChannelPromise promise, boolean flush) {
            task.ctx = ctx;
            task.msg = msg;
            task.promise = promise;

            if (ESTIMATE_TASK_SIZE_ON_SUBMIT) {
                task.size = ctx.pipeline.estimatorHandle().size(msg) + WRITE_TASK_OVERHEAD;
                ctx.pipeline.incrementPendingOutboundBytes(task.size);
            } else {
                task.size = 0;
            }
            if (flush) {
                task.size |= Integer.MIN_VALUE;
            }
        }

        @Override
        public void run() {
            try {
                decrementPendingOutboundBytes();
                if (size >= 0) {
                    ctx.invokeWrite(msg, promise);
                } else {
                    ctx.invokeWriteAndFlush(msg, promise);
                }
            } finally {
                recycle();
            }
        }

        void cancel() {
            try {
                decrementPendingOutboundBytes();
            } finally {
                recycle();
            }
        }

        private void decrementPendingOutboundBytes() {
            if (ESTIMATE_TASK_SIZE_ON_SUBMIT) {
                ctx.pipeline.decrementPendingOutboundBytes(size & Integer.MAX_VALUE);
            }
        }

        private void recycle() {
            // Set to null so the GC can collect them directly
            ctx = null;
            msg = null;
            promise = null;
            handle.recycle(this);
        }
    }

    private static final class Tasks {
        private final AbstractChannelHandlerContext next;
        private final Runnable invokeChannelReadCompleteTask = new Runnable() {
            @Override
            public void run() {
                next.invokeChannelReadComplete();
            }
        };
        private final Runnable invokeReadTask = new Runnable() {
            @Override
            public void run() {
                next.invokeRead();
            }
        };
        private final Runnable invokeChannelWritableStateChangedTask = new Runnable() {
            @Override
            public void run() {
                next.invokeChannelWritabilityChanged();
            }
        };
        private final Runnable invokeFlushTask = new Runnable() {
            @Override
            public void run() {
                next.invokeFlush();
            }
        };

        Tasks(AbstractChannelHandlerContext next) {
            this.next = next;
        }
    }
}

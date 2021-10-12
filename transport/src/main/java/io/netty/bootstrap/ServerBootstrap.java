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
package io.netty.bootstrap;

import io.netty.channel.*;
import io.netty.util.AttributeKey;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * {@link Bootstrap} sub-class which allows easy bootstrap of {@link ServerChannel}
 *
 * 引导类的子类，
 * 允许服务端通道的更简单引导。
 */
public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ServerBootstrap.class);

    // The order in which child ChannelOptions are applied is important they may depend on each other for validation
    // purposes.
    /**
     * 应用子通道选项的顺序是很重要的，它们也许彼此依赖以达到校验目的。
     */
    private final Map<ChannelOption<?>, Object> childOptions = new LinkedHashMap<ChannelOption<?>, Object>();

    /**
     * 为每个子通道设置的属性映射
     */
    private final Map<AttributeKey<?>, Object> childAttrs = new ConcurrentHashMap<AttributeKey<?>, Object>();

    /**
     * 服务引导配置
     */
    private final ServerBootstrapConfig config = new ServerBootstrapConfig(this);

    /**
     * 子事件循环组
     */
    private volatile EventLoopGroup childGroup;

    /**
     * 子处理者
     */
    private volatile ChannelHandler childHandler;

    public ServerBootstrap() { }

    private ServerBootstrap(ServerBootstrap bootstrap) {
        super(bootstrap);
        childGroup = bootstrap.childGroup;
        childHandler = bootstrap.childHandler;
        synchronized (bootstrap.childOptions) {
            childOptions.putAll(bootstrap.childOptions);
        }
        childAttrs.putAll(bootstrap.childAttrs);
    }

    /**
     * Specify the {@link EventLoopGroup} which is used for the parent (acceptor) and the child (client).
     */
    @Override
    public ServerBootstrap group(EventLoopGroup group) {
        return group(group, group);
    }

    /**
     * Set the {@link EventLoopGroup} for the parent (acceptor) and the child (client). These
     * {@link EventLoopGroup}'s are used to handle all the events and IO for {@link ServerChannel} and
     * {@link Channel}'s.
     *
     * 为父（接收器）和子（客户端）设置事件循环组。
     * 这些事件循环组被用来处理所有的事件和用于服务端通道、客户端通道的输入输出。
     */
    public ServerBootstrap group(EventLoopGroup parentGroup, EventLoopGroup childGroup) {
        // 设置父事件循环组
        super.group(parentGroup);

        // 如果当前服务端引导类实例的子事件循环组不为null，则抛出违规状态异常
        if (this.childGroup != null) {
            throw new IllegalStateException("childGroup set already");
        }

        // 设置子事件循环组，并返回自身
        this.childGroup = ObjectUtil.checkNotNull(childGroup, "childGroup");
        return this;
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they get created
     * (after the acceptor accepted the {@link Channel}). Use a value of {@code null} to remove a previous set
     * {@link ChannelOption}.
     *
     * 允许指定用于通道实例的通道选项，
     * 一旦它们获得创建（在接收器接收了通道之后）。
     * 使用null值以移除先前设置的通道选项。
     */
    public <T> ServerBootstrap childOption(ChannelOption<T> childOption, T value) {
        // 检查入参子选项不能为null
        ObjectUtil.checkNotNull(childOption, "childOption");

        // 锁住子选项映射
        synchronized (childOptions) {
            // 如果入参值为null，则移除原先设置的子选项
            if (value == null) {
                childOptions.remove(childOption);
            } else {
                // 为子选项设置新值
                childOptions.put(childOption, value);
            }
        }

        // 返回引导类实例本身
        return this;
    }

    /**
     * Set the specific {@link AttributeKey} with the given value on every child {@link Channel}. If the value is
     * {@code null} the {@link AttributeKey} is removed
     *
     * 在每个子通道上，
     * 用给定值设置指定的属性键。
     * 如果值为null，
     * 则属性键将被移除。
     */
    public <T> ServerBootstrap childAttr(AttributeKey<T> childKey, T value) {
        // 检查子属性键不能为null
        ObjectUtil.checkNotNull(childKey, "childKey");

        // 如果入参值为null，则移除该属性键
        if (value == null) {
            childAttrs.remove(childKey);
        } else {
            // 否则，为该属性键设置该属性值
            childAttrs.put(childKey, value);
        }

        // 返回引导类实例本身
        return this;
    }

    /**
     * Set the {@link ChannelHandler} which is used to serve the request for the {@link Channel}'s.
     *
     * 设置通道处理者，
     * 该通道处理者被用于服务通道请求。
     */
    public ServerBootstrap childHandler(ChannelHandler childHandler) {
        // 检查子通道处理者不能为null
        this.childHandler = ObjectUtil.checkNotNull(childHandler, "childHandler");
        // 返回引导类实例本身
        return this;
    }

    @Override
    void init(Channel channel) {
        // 设置通道选项（一般是空的，暂不细究）
        setChannelOptions(channel, newOptionsArray(), logger);
        // 设置属性（一般是空的，暂不细究）
        setAttributes(channel, newAttributesArray());

        // 获得通道流水线
        ChannelPipeline p = channel.pipeline();

        // 子事件循环组
        final EventLoopGroup currentChildGroup = childGroup;
        // 子处理者（一般是匿名类实现）
        final ChannelHandler currentChildHandler = childHandler;

        // 子选项数组
        final Entry<ChannelOption<?>, Object>[] currentChildOptions = newOptionsArray(childOptions);
        // 子属性数组
        final Entry<AttributeKey<?>, Object>[] currentChildAttrs = newAttributesArray(childAttrs);

        // 通道流水线添加通道处理者
        p.addLast(new ChannelInitializer<Channel>() {
            @Override
            public void initChannel(final Channel ch) {
                // 获得通道流水线
                final ChannelPipeline pipeline = ch.pipeline();

                // 通道处理者
                ChannelHandler handler = config.handler();
                if (handler != null) {
                    // 如果通道处理者不为null，则添加至流水线的最后
                    pipeline.addLast(handler);
                }

                // 获取通道的事件循环，事件循环执行入参匿名方法
                ch.eventLoop().execute(new Runnable() {
                    @Override
                    public void run() {
                        //
                        pipeline.addLast(new ServerBootstrapAcceptor(
                                ch, currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs));
                    }
                });
            }
        });
    }

    @Override
    public ServerBootstrap validate() {
        super.validate();
        if (childHandler == null) {
            throw new IllegalStateException("childHandler not set");
        }
        if (childGroup == null) {
            logger.warn("childGroup is not set. Using parentGroup instead.");
            childGroup = config.group();
        }
        return this;
    }

    private static class ServerBootstrapAcceptor extends ChannelInboundHandlerAdapter {

        private final EventLoopGroup childGroup;
        private final ChannelHandler childHandler;
        private final Entry<ChannelOption<?>, Object>[] childOptions;
        private final Entry<AttributeKey<?>, Object>[] childAttrs;
        private final Runnable enableAutoReadTask;

        ServerBootstrapAcceptor(
                final Channel channel, EventLoopGroup childGroup, ChannelHandler childHandler,
                Entry<ChannelOption<?>, Object>[] childOptions, Entry<AttributeKey<?>, Object>[] childAttrs) {
            this.childGroup = childGroup;
            this.childHandler = childHandler;
            this.childOptions = childOptions;
            this.childAttrs = childAttrs;

            // Task which is scheduled to re-enable auto-read.
            // It's important to create this Runnable before we try to submit it as otherwise the URLClassLoader may
            // not be able to load the class because of the file limit it already reached.
            //
            // See https://github.com/netty/netty/issues/1328
            enableAutoReadTask = new Runnable() {
                @Override
                public void run() {
                    channel.config().setAutoRead(true);
                }
            };
        }

        @Override
        @SuppressWarnings("unchecked")
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            final Channel child = (Channel) msg;

            child.pipeline().addLast(childHandler);

            setChannelOptions(child, childOptions, logger);
            setAttributes(child, childAttrs);

            try {
                childGroup.register(child).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            forceClose(child, future.cause());
                        }
                    }
                });
            } catch (Throwable t) {
                forceClose(child, t);
            }
        }

        private static void forceClose(Channel child, Throwable t) {
            child.unsafe().closeForcibly();
            logger.warn("Failed to register an accepted channel: {}", child, t);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            final ChannelConfig config = ctx.channel().config();
            if (config.isAutoRead()) {
                // stop accept new connections for 1 second to allow the channel to recover
                // See https://github.com/netty/netty/issues/1328
                config.setAutoRead(false);
                ctx.channel().eventLoop().schedule(enableAutoReadTask, 1, TimeUnit.SECONDS);
            }
            // still let the exceptionCaught event flow through the pipeline to give the user
            // a chance to do something with it
            ctx.fireExceptionCaught(cause);
        }
    }

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public ServerBootstrap clone() {
        return new ServerBootstrap(this);
    }

    /**
     * Return the configured {@link EventLoopGroup} which will be used for the child channels or {@code null}
     * if non is configured yet.
     *
     * @deprecated Use {@link #config()} instead.
     */
    @Deprecated
    public EventLoopGroup childGroup() {
        return childGroup;
    }

    final ChannelHandler childHandler() {
        return childHandler;
    }

    final Map<ChannelOption<?>, Object> childOptions() {
        synchronized (childOptions) {
            return copiedMap(childOptions);
        }
    }

    final Map<AttributeKey<?>, Object> childAttrs() {
        return copiedMap(childAttrs);
    }

    @Override
    public final ServerBootstrapConfig config() {
        return config;
    }
}

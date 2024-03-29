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
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link AbstractBootstrap} is a helper class that makes it easy to bootstrap a {@link Channel}. It support
 * method-chaining to provide an easy way to configure the {@link AbstractBootstrap}.
 *
 * <p>When not used in a {@link ServerBootstrap} context, the {@link #bind()} methods are useful for connectionless
 * transports such as datagram (UDP).</p>
 */
public abstract class AbstractBootstrap<B extends AbstractBootstrap<B, C>, C extends Channel> implements Cloneable {

    /**
     * 空的通道选项数组
     */
    @SuppressWarnings("unchecked")
    private static final Map.Entry<ChannelOption<?>, Object>[] EMPTY_OPTION_ARRAY = new Map.Entry[0];

    /**
     * 空的属性数组
     */
    @SuppressWarnings("unchecked")
    private static final Map.Entry<AttributeKey<?>, Object>[] EMPTY_ATTRIBUTE_ARRAY = new Map.Entry[0];

    /**
     * 用于当前引导类的事件循环组
     */
    volatile EventLoopGroup group;

    /**
     * 用于此引导的通道工厂
     */
    @SuppressWarnings("deprecation")
    private volatile ChannelFactory<? extends C> channelFactory;

    /**
     * 此引导绑定的套接字地址
     */
    private volatile SocketAddress localAddress;

    // The order in which ChannelOptions are applied is important they may depend on each other for validation
    // purposes.
    /**
     * 应用子通道选项的顺序是很重要的，它们也许彼此依赖以达到校验目的。
     */
    private final Map<ChannelOption<?>, Object> options = new LinkedHashMap<ChannelOption<?>, Object>();

    /**
     * 属性映射关系
     */
    private final Map<AttributeKey<?>, Object> attrs = new ConcurrentHashMap<AttributeKey<?>, Object>();

    private volatile ChannelHandler handler;

    AbstractBootstrap() {
        // Disallow extending from a different package.
    }

    AbstractBootstrap(AbstractBootstrap<B, C> bootstrap) {
        group = bootstrap.group;
        channelFactory = bootstrap.channelFactory;
        handler = bootstrap.handler;
        localAddress = bootstrap.localAddress;
        synchronized (bootstrap.options) {
            options.putAll(bootstrap.options);
        }
        attrs.putAll(bootstrap.attrs);
    }

    /**
     * The {@link EventLoopGroup} which is used to handle all the events for the to-be-created
     * {@link Channel}
     *
     * 被用来处理所有用于将被创建的通道的事件循环组。
     */
    public B group(EventLoopGroup group) {
        // 检查事件循环组，事件循环组不能为null
        ObjectUtil.checkNotNull(group, "group");

        // 如果当前引导类实例的事件循环组不为null，则抛出违规参数异常
        if (this.group != null) {
            throw new IllegalStateException("group set already");
        }

        // 设置事件循环组
        this.group = group;
        return self();
    }

    @SuppressWarnings("unchecked")
    private B self() {
        // 返回自身引用，B是子类类型
        return (B) this;
    }

    /**
     * The {@link Class} which is used to create {@link Channel} instances from.
     * You either use this or {@link #channelFactory(io.netty.channel.ChannelFactory)} if your
     * {@link Channel} implementation has no no-args constructor.
     *
     * 将被用来从中创建通道实例的类对象。
     * 你要么使用本身，
     * 要么使用通道工厂方法，
     * 如果你的通道实现没有无参构造器。
     */
    public B channel(Class<? extends C> channelClass) {
        return channelFactory(new ReflectiveChannelFactory<C>(
                ObjectUtil.checkNotNull(channelClass, "channelClass")
        ));
    }

    /**
     * @deprecated Use {@link #channelFactory(io.netty.channel.ChannelFactory)} instead.
     */
    @Deprecated
    public B channelFactory(ChannelFactory<? extends C> channelFactory) {
        // 检查通道工厂不能为null
        ObjectUtil.checkNotNull(channelFactory, "channelFactory");

        // 如果当前引导类实例的通道工厂不为null，则抛出违规状态异常
        if (this.channelFactory != null) {
            throw new IllegalStateException("channelFactory set already");
        }

        // 设置通道工厂
        this.channelFactory = channelFactory;

        // 返回自身实例
        return self();
    }

    /**
     * {@link io.netty.channel.ChannelFactory} which is used to create {@link Channel} instances from
     * when calling {@link #bind()}. This method is usually only used if {@link #channel(Class)}
     * is not working for you because of some more complex needs. If your {@link Channel} implementation
     * has a no-args constructor, its highly recommend to just use {@link #channel(Class)} to
     * simplify your code.
     */
    @SuppressWarnings({ "unchecked", "deprecation" })
    public B channelFactory(io.netty.channel.ChannelFactory<? extends C> channelFactory) {
        return channelFactory((ChannelFactory<C>) channelFactory);
    }

    /**
     * The {@link SocketAddress} which is used to bind the local "end" to.
     */
    public B localAddress(SocketAddress localAddress) {
        this.localAddress = localAddress;
        return self();
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(int inetPort) {
        return localAddress(new InetSocketAddress(inetPort));
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(String inetHost, int inetPort) {
        return localAddress(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(InetAddress inetHost, int inetPort) {
        return localAddress(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they got
     * created. Use a value of {@code null} to remove a previous set {@link ChannelOption}.
     */
    public <T> B option(ChannelOption<T> option, T value) {
        ObjectUtil.checkNotNull(option, "option");
        synchronized (options) {
            if (value == null) {
                options.remove(option);
            } else {
                options.put(option, value);
            }
        }
        return self();
    }

    /**
     * Allow to specify an initial attribute of the newly created {@link Channel}.  If the {@code value} is
     * {@code null}, the attribute of the specified {@code key} is removed.
     */
    public <T> B attr(AttributeKey<T> key, T value) {
        ObjectUtil.checkNotNull(key, "key");
        if (value == null) {
            attrs.remove(key);
        } else {
            attrs.put(key, value);
        }
        return self();
    }

    /**
     * Validate all the parameters. Sub-classes may override this, but should
     * call the super method in that case.
     *
     * 校验所有参数。
     * 子类可能覆盖它，
     * 但是在那种情况下，
     * 应该调用父类方法。
     */
    public B validate() {
        // 如果事件循环组为null，则抛出违规参数异常
        if (group == null) {
            throw new IllegalStateException("group not set");
        }
        // 如果通道工厂为null，则抛出违规参数异常
        if (channelFactory == null) {
            throw new IllegalStateException("channel or channelFactory not set");
        }

        // 返回引导类实例本身
        return self();
    }

    /**
     * Returns a deep clone of this bootstrap which has the identical configuration.  This method is useful when making
     * multiple {@link Channel}s with similar settings.  Please note that this method does not clone the
     * {@link EventLoopGroup} deeply but shallowly, making the group a shared resource.
     */
    @Override
    @SuppressWarnings("CloneDoesntDeclareCloneNotSupportedException")
    public abstract B clone();

    /**
     * Create a new {@link Channel} and register it with an {@link EventLoop}.
     */
    public ChannelFuture register() {
        validate();
        return initAndRegister();
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind() {
        validate();
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            throw new IllegalStateException("localAddress not set");
        }
        return doBind(localAddress);
    }

    /**
     * Create a new {@link Channel} and bind it.
     *
     * 创建一个新的通道，
     * 并且绑定它。
     */
    public ChannelFuture bind(int inetPort) {
        return bind(new InetSocketAddress(inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(String inetHost, int inetPort) {
        return bind(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(InetAddress inetHost, int inetPort) {
        return bind(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     *
     * 创建一个新的通道并且绑定它。
     */
    public ChannelFuture bind(SocketAddress localAddress) {
        validate();

        // 若本地地址不为null，则做绑定操作
        return doBind(ObjectUtil.checkNotNull(localAddress, "localAddress"));
    }

    /**
     * 绑定本地套接字地址
     *
     * @param localAddress 本地套接字地址
     * @return 通道未来
     */
    private ChannelFuture doBind(final SocketAddress localAddress) {
        // 初始化和注册，获得一个注册的通道未来
        final ChannelFuture regFuture = initAndRegister();
        // 获得注册通道未来的通道
        final Channel channel = regFuture.channel();

        // 如果注册通道未来是失败的，则返回注册通道未来
        if (regFuture.cause() != null) {
            return regFuture;
        }

        // 如果注册通道未来已经完成
        if (regFuture.isDone()) {
            /*
                以下不细究
             */
            // At this point we know that the registration was complete and successful.
            ChannelPromise promise = channel.newPromise();
            doBind0(regFuture, channel, localAddress, promise);
            return promise;
        } else {
            // Registration future is almost always fulfilled already, but just in case it's not.
            // 注册未来大部分总是完成的，但仅在这种情况下不是。
            // 实例化一个待办注册承诺
            final PendingRegistrationPromise promise = new PendingRegistrationPromise(channel);
            // 注册通道未来添加监听者
            regFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    Throwable cause = future.cause();
                    // 如果通道未来是失败的
                    if (cause != null) {
                        /*
                            以下不细究
                         */
                        // Registration on the EventLoop failed so fail the ChannelPromise directly to not cause an
                        // IllegalStateException once we try to access the EventLoop of the Channel.
                        promise.setFailure(cause);
                    } else {
                        // Registration was successful, so set the correct executor to use.
                        // See https://github.com/netty/netty/issues/2586
                        // 注册是成功的，所以设置正确的执行器以使用。
                        promise.registered();

                        // 处理绑定
                        doBind0(regFuture, channel, localAddress, promise);
                    }
                }
            });

            // 返回待办注册承诺
            return promise;
        }
    }

    /**
     * 初始化并且注册
     *
     * @return 通道未来
     */
    final ChannelFuture initAndRegister() {
        /*
            做初始化操作
         */
        Channel channel = null;
        try {
            // 从通道工厂中实例化一个通道（jdk的通道，一般是NioServerSocketChannel）
            channel = channelFactory.newChannel();
            // 初始化通道
            init(channel);
        } catch (Throwable t) {
            /*
                异常处理（不细究）
             */

            if (channel != null) {
                // channel can be null if newChannel crashed (eg SocketException("too many open files"))
                channel.unsafe().closeForcibly();
                // as the Channel is not registered yet we need to force the usage of the GlobalEventExecutor
                return new DefaultChannelPromise(channel, GlobalEventExecutor.INSTANCE).setFailure(t);
            }
            // as the Channel is not registered yet we need to force the usage of the GlobalEventExecutor
            return new DefaultChannelPromise(new FailedChannel(), GlobalEventExecutor.INSTANCE).setFailure(t);
        }

        /*
            做注册操作
         */
        // boss事件循环者组注册通道，获得注册通道未来，即该注册过程可能未完成（nio事件循环组注册通道，异步完成）
        ChannelFuture regFuture = config().group().register(channel);
        if (regFuture.cause() != null) {
            /*
                以下不细究
             */
            if (channel.isRegistered()) {
                channel.close();
            } else {
                channel.unsafe().closeForcibly();
            }
        }

        // If we are here and the promise is not failed, it's one of the following cases:
        // 1) If we attempted registration from the event loop, the registration has been completed at this point.
        //    i.e. It's safe to attempt bind() or connect() now because the channel has been registered.
        // 2) If we attempted registration from the other thread, the registration request has been successfully
        //    added to the event loop's task queue for later execution.
        //    i.e. It's safe to attempt bind() or connect() now:
        //         because bind() or connect() will be executed *after* the scheduled registration task is executed
        //         because register(), bind(), and connect() are all bound to the same thread.

        /*
            如果我们到达这并且承诺未失败，则有以下几种情况：
            1）如果我们尝试从事件循环注册，则在此时注册注册已经完成。
                比如：现在尝试绑定或者连接操作是安全的，因为已经注册了通道。
            2）如果我们尝试从其他线程注册，注册请求已经被成功地添加到事件循环任务队列里，以用于之后的执行。
                比如：现在尝试去绑定或者连接是安全的：
                    因为绑定或者连接将在调度注册任务被执行后执行，
                    因为注册、绑定和连接都绑定在相同的线程。
         */

        // 返回未来
        return regFuture;
    }

    /**
     * 由子类实现初始化
     *
     * @param channel 通道
     * @throws Exception 异常
     */
    abstract void init(Channel channel) throws Exception;

    /**
     * 处理绑定
     *
     * @param regFuture 注册通道未来
     * @param channel 通道
     * @param localAddress 套接字地址
     * @param promise 通道承诺
     */
    private static void doBind0(
            final ChannelFuture regFuture, final Channel channel,
            final SocketAddress localAddress, final ChannelPromise promise) {

        // This method is invoked before channelRegistered() is triggered.  Give user handlers a chance to set up
        // the pipeline in its channelRegistered() implementation.
        /*
           此方法在通道注册之前被触发。
           给用户处理者一个机会去在通道注册实现里设置流水线。
         */
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                // 如果注册通道未来是成功的
                if (regFuture.isSuccess()) {
                    // 做绑定操作，再添加监听者
                    channel.bind(localAddress, promise).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                } else {
                    // 不细究
                    promise.setFailure(regFuture.cause());
                }
            }
        });
    }

    /**
     * the {@link ChannelHandler} to use for serving the requests.
     *
     * 用于服务请求的通道处理者。
     */
    public B handler(ChannelHandler handler) {
        // 检查入参客户端处理者不能为null
        this.handler = ObjectUtil.checkNotNull(handler, "handler");
        // 返回引导实例本身
        return self();
    }

    /**
     * Returns the configured {@link EventLoopGroup} or {@code null} if non is configured yet.
     *
     * 返回配置了的事件循环组或者null，如果还未配置的话。
     *
     * @deprecated Use {@link #config()} instead.
     */
    @Deprecated
    public final EventLoopGroup group() {
        return group;
    }

    /**
     * Returns the {@link AbstractBootstrapConfig} object that can be used to obtain the current config
     * of the bootstrap.
     *
     * 返回能被用来获取当前引导配置的抽象引导配置。
     */
    public abstract AbstractBootstrapConfig<B, C> config();

    /**
     * 实例化一个选项数组
     *
     * @return 选项数组
     */
    final Map.Entry<ChannelOption<?>, Object>[] newOptionsArray() {
        // 实例一个选项数组
        return newOptionsArray(options);
    }

    /**
     * 实例一个通道选项数组
     *
     * @param options 当前通道选项数组
     * @return 通道选项数组
     */
    static Map.Entry<ChannelOption<?>, Object>[] newOptionsArray(Map<ChannelOption<?>, Object> options) {
        synchronized (options) {
            return new LinkedHashMap<ChannelOption<?>, Object>(options).entrySet().toArray(EMPTY_OPTION_ARRAY);
        }
    }

    /**
     * 实例化属性数组
     *
     * @return 属性数组
     */
    final Map.Entry<AttributeKey<?>, Object>[] newAttributesArray() {
        // 实例属性数组
        return newAttributesArray(attrs0());
    }

    /**
     * 实例化属性数组
     *
     * @param attributes 属性映射
     * @return 属性数组
     */
    static Map.Entry<AttributeKey<?>, Object>[] newAttributesArray(Map<AttributeKey<?>, Object> attributes) {
        return attributes.entrySet().toArray(EMPTY_ATTRIBUTE_ARRAY);
    }

    final Map<ChannelOption<?>, Object> options0() {
        return options;
    }

    final Map<AttributeKey<?>, Object> attrs0() {
        return attrs;
    }

    final SocketAddress localAddress() {
        return localAddress;
    }

    @SuppressWarnings("deprecation")
    final ChannelFactory<? extends C> channelFactory() {
        return channelFactory;
    }

    final ChannelHandler handler() {
        return handler;
    }

    final Map<ChannelOption<?>, Object> options() {
        synchronized (options) {
            return copiedMap(options);
        }
    }

    final Map<AttributeKey<?>, Object> attrs() {
        return copiedMap(attrs);
    }

    static <K, V> Map<K, V> copiedMap(Map<K, V> map) {
        if (map.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new HashMap<K, V>(map));
    }

    /**
     * 设置属性集
     *
     * @param channel 通道
     * @param attrs 属性
     */
    static void setAttributes(Channel channel, Map.Entry<AttributeKey<?>, Object>[] attrs) {
        // 依次遍历
        for (Map.Entry<AttributeKey<?>, Object> e: attrs) {
            // 拿到属性键
            @SuppressWarnings("unchecked")
            AttributeKey<Object> key = (AttributeKey<Object>) e.getKey();
            // 属性键设置属性值
            channel.attr(key).set(e.getValue());
        }
    }

    /**
     * 设置通道选项
     *
     * @param channel 通道
     * @param options 通道选项数组
     * @param logger 日志器
     */
    static void setChannelOptions(
            Channel channel, Map.Entry<ChannelOption<?>, Object>[] options, InternalLogger logger) {
        for (Map.Entry<ChannelOption<?>, Object> e: options) {
            // 为每个通道选项进行设置
            setChannelOption(channel, e.getKey(), e.getValue(), logger);
        }
    }

    /**
     * 设置通道选项
     *
     * @param channel 通道
     * @param option 通道选项
     * @param value 通道选项值
     * @param logger 日志器
     */
    @SuppressWarnings("unchecked")
    private static void setChannelOption(
            Channel channel, ChannelOption<?> option, Object value, InternalLogger logger) {
        try {
            if (!channel.config().setOption((ChannelOption<Object>) option, value)) {
                logger.warn("Unknown channel option '{}' for channel '{}'", option, channel);
            }
        } catch (Throwable t) {
            logger.warn(
                    "Failed to set channel option '{}' with value '{}' for channel '{}'", option, value, channel, t);
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder()
            .append(StringUtil.simpleClassName(this))
            .append('(').append(config()).append(')');
        return buf.toString();
    }

    /**
     * 待办注册承诺
     */
    static final class PendingRegistrationPromise extends DefaultChannelPromise {

        // Is set to the correct EventExecutor once the registration was successful. Otherwise it will
        // stay null and so the GlobalEventExecutor.INSTANCE will be used for notifications.
        /**
         * 一旦注册成功，就被设置为正确的事件执行器。
         * 否则它将保持null，因此全局事件执行器实例将被用于通知。
         */
        private volatile boolean registered;

        PendingRegistrationPromise(Channel channel) {
            // 设置通道
            super(channel);
        }

        /**
         * 设置待办处理承诺的状态为已注册
         */
        void registered() {
            registered = true;
        }

        @Override
        protected EventExecutor executor() {
            if (registered) {
                // If the registration was a success executor is set.
                //
                // See https://github.com/netty/netty/issues/2586
                return super.executor();
            }
            // The registration failed so we can only use the GlobalEventExecutor as last resort to notify.
            return GlobalEventExecutor.INSTANCE;
        }
    }
}

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
package io.netty.channel.nio;

import io.netty.channel.*;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    private static final boolean DISABLE_KEY_SET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;

    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {

            return selectNow();
        }
    };

    // Workaround for JDK NIO bug.
    //
    // See:
    // - https://bugs.java.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    static {
        // 获取故障级别
        final String key = "sun.nio.ch.bugLevel";
        final String bugLevel = SystemPropertyUtil.get(key);

        // 如果故障级别为null，则设置故障级别
        if (bugLevel == null) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        // 设置故障级别属性值
                        System.setProperty(key, "");
                        return null;
                    }
                });
            } catch (final SecurityException e) {
                // 不细究
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }

        // 获得选择器自动重建阈值，默认为512
        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        // 设置选择器自动重建阈值
        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        // 打印日志
        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     *
     * Nio选择器
     */
    private Selector selector;

    /**
     * 展开的选择器
     */
    private Selector unwrappedSelector;

    /**
     * 选定的选择键集
     */
    private SelectedSelectionKeySet selectedKeys;

    private final SelectorProvider provider;

    private static final long AWAKE = -1L;
    private static final long NONE = Long.MAX_VALUE;

    // nextWakeupNanos is:
    //    AWAKE            when EL is awake
    //    NONE             when EL is waiting with no wakeup scheduled
    //    other value T    when EL is waiting with wakeup scheduled at time T
    /**
     * 下次醒来的时刻是：
     *  AWAKE，当事件循环是唤醒状态，
     *  NONE，当事件循环正在等待且未安排唤醒时，
     *  其他值，当事件循环正在等待，并且被安排在时刻t被唤醒。
     */
    private final AtomicLong nextWakeupNanos = new AtomicLong(AWAKE);

    /**
     * 当前nio事件循环的选择策略
     */
    private final SelectStrategy selectStrategy;

    /**
     * 输入输出率，默认为50
     */
    private volatile int ioRatio = 50;

    /**
     * 取消的键的个数
     */
    private int cancelledKeys;

    /**
     * 是否需要再次选择
     */
    private boolean needsToSelectAgain;

    /**
     * nio事件循环的构造方法
     *
     * @param parent nio事件循环组
     * @param executor 执行器
     * @param selectorProvider 选择器提供者
     * @param strategy 选择策略
     * @param rejectedExecutionHandler 拒绝的执行处理者
     * @param taskQueueFactory 任务队列工厂
     * @param tailTaskQueueFactory 尾任务队列工厂
     */
    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler,
                 EventLoopTaskQueueFactory taskQueueFactory, EventLoopTaskQueueFactory tailTaskQueueFactory) {
        super(parent, executor, false, newTaskQueue(taskQueueFactory), newTaskQueue(tailTaskQueueFactory),
                rejectedExecutionHandler);
        this.provider = ObjectUtil.checkNotNull(selectorProvider, "selectorProvider");
        // 设置选择策略
        this.selectStrategy = ObjectUtil.checkNotNull(strategy, "selectStrategy");
        // 打开选择器，获得选择器元祖，分别设置选择器和未包装选择器
        final SelectorTuple selectorTuple = openSelector();
        this.selector = selectorTuple.selector;
        this.unwrappedSelector = selectorTuple.unwrappedSelector;
    }

    /**
     * 实例化一个任务队列
     *
     * @param queueFactory 队列工厂
     * @return 存储可执行任务的队列
     */
    private static Queue<Runnable> newTaskQueue(
            EventLoopTaskQueueFactory queueFactory) {
        // 如果队列工厂为null
        if (queueFactory == null) {
            // 实例化一个任务队列
            return newTaskQueue0(DEFAULT_MAX_PENDING_TASKS);
        }
        return queueFactory.newTaskQueue(DEFAULT_MAX_PENDING_TASKS);
    }

    private static final class SelectorTuple {

        /**
         * 未包装的选择器
         */
        final Selector unwrappedSelector;

        /**
         * 包装的选择器
         */
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    /**
     * 打开选择器
     *
     * @return 选择器元祖
     */
    private SelectorTuple openSelector() {
        // 未包装的选择器
        final Selector unwrappedSelector;
        try {
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        // 如果关闭键集优化
        if (DISABLE_KEY_SET_OPTIMIZATION) {
            // 不细究
            return new SelectorTuple(unwrappedSelector);
        }

        // 获得可能的选择器实现类实例（一般是系统类加载器）
        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });

        // 该条件体不细究
        if (!(maybeSelectorImplClass instanceof Class) ||
            // ensure the current selector implementation is what we can instrument.
            !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        // 记录可能的选择器实现类，实例化已选择的选择键集
        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        // 获得可能的异常
        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    // 分别拿到两个字段：selectedKeys、publicSelectedKeys
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    // 此条件体不细究
                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                        // This allows us to also do this in Java9+ without any extra flags.
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset =
                                PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            PlatformDependent.putObject(
                                    unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(
                                    unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                        // We could not retrieve the offset, lets try reflection as last-resort.
                    }

                    // 分别设置两个字段为可访问权限
                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }

                    // 给展开选择器的两个字段设值
                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        // 此条件体不细究
        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }

        // 设置已选键集
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);

        // 返回选择器元组
        return new SelectorTuple(unwrappedSelector,
                                 new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return newTaskQueue0(maxPendingTasks);
    }

    /**
     * 实例化任务队列
     *
     * @param maxPendingTasks 最大待办任务数
     * @return 任务队列
     */
    private static Queue<Runnable> newTaskQueue0(int maxPendingTasks) {
        // This event loop never calls takeTask()
        // 此事件循环从不调用取出任务方法
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        ObjectUtil.checkNotNull(ch, "ch");
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        ObjectUtil.checkNotNull(task, "task");

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        if (inEventLoop()) {
            register0(ch, interestOps, task);
        } else {
            try {
                // Offload to the EventLoop as otherwise java.nio.channels.spi.AbstractSelectableChannel.register
                // may block for a long time while trying to obtain an internal lock that may be hold while selecting.
                submit(new Runnable() {
                    @Override
                    public void run() {
                        register0(ch, interestOps, task);
                    }
                }).sync();
            } catch (InterruptedException ignore) {
                // Even if interrupted we did schedule it so just mark the Thread as interrupted.
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop. Value range from 1-100.
     * The default value is {@code 50}, which means the event loop will try to spend the same amount of time for I/O
     * as for non-I/O tasks. The lower the number the more time can be spent on non-I/O tasks. If value set to
     * {@code 100}, this feature will be disabled and event loop will not attempt to balance I/O and non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     *
     * 用新创建的选择器代替当前事件循环的选择器，已解决臭名昭著的epoll 100%中央处理单元故障。
     */
    public void rebuildSelector() {
        // 如果当前线程未处于事件循环中
        if (!inEventLoop()) {
            // 包装成任务，异步执行
            execute(new Runnable() {
                @Override
                public void run() {
                    // 重建选择器
                    rebuildSelector0();
                }
            });
            return;
        }

        // 重建选择器
        rebuildSelector0();
    }

    @Override
    public int registeredChannels() {
        return selector.keys().size() - cancelledKeys;
    }

    /**
     * 重建选择器
     */
    private void rebuildSelector0() {
        // 获得当前nio事件循环的选择器
        final Selector oldSelector = selector;
        // 新的选择器元组
        final SelectorTuple newSelectorTuple;

        // 如果当前nio事件循环的选择器为null，则直接返回
        if (oldSelector == null) {
            return;
        }

        try {
            // 打开选择器，获得一个新的选择器元组
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            /*
                以下不细究
             */
            // 如果捕捉到任何异常，则记录日志（创建新的选择器失败），返回
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        // 将所有的通道注册都新的选择器上。
        int nChannels = 0;
        // 遍历当前选择器上的所有选择键
        for (SelectionKey key: oldSelector.keys()) {
            // 获得当前选择键的附加物
            Object a = key.attachment();
            try {
                // 如果键不是有效的，或者该键的通道已经注册到新选择器上，则跳过本轮循环
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                // 获得当前选择键的感兴趣操作
                int interestOps = key.interestOps();
                // 取消当前键
                key.cancel();
                // 将当前键的通道和对应的感兴趣事件及附加物注册到新的选择器上，获得新的选择键
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);

                // 如果附加物是抽象nio通道的实例
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    // 更新选择键
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                // 通道数加1
                nChannels ++;
            } catch (Exception e) {
                /*
                    以下不细究
                 */
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        // 设置当前nio事件循环的选择器和未包装选择器
        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            // 是时候关闭旧选择器了，因为其他所有事情被注册到新的选择器上了
            oldSelector.close();
        } catch (Throwable t) {
            /*
                以下不细究
             */
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        // 打印迁移日志
        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    @Override
    protected void run() {
        // 进行了多少次选择操作
        int selectCnt = 0;
        for (;;) {
            try {
                int strategy;
                try {
                    // 计算出一个选择策略
                    strategy = selectStrategy.calculateStrategy(selectNowSupplier, hasTasks());
                    switch (strategy) {
                    case SelectStrategy.CONTINUE:
                        // 以下不细究
                        continue;

                    case SelectStrategy.BUSY_WAIT:
                        // 以下不细究
                        // fall-through to SELECT since the busy-wait is not supported with NIO

                    case SelectStrategy.SELECT:
                        // 获得当前调度任务的截止时间
                        long curDeadlineNanos = nextScheduledTaskDeadlineNanos();
                        if (curDeadlineNanos == -1L) {
                            // 日历中什么都没有，即任务没有截止时间，当事件循环正在等待且未安排唤醒时，
                            curDeadlineNanos = NONE; // nothing on the calendar
                        }
                        // 设置下一次事件循环的唤醒时间
                        nextWakeupNanos.set(curDeadlineNanos);

                        try {
                            // 如果没有任务，做选择操作（有可能是阻塞的选择操作，导致该线程被阻塞了）
                            if (!hasTasks()) {
                                // 做选择操作
                                strategy = select(curDeadlineNanos);
                            }
                        } finally {
                            // This update is just to help block unnecessary selector wakeups
                            // so use of lazySet is ok (no race condition)
                            /*
                                此更新只是为了帮助阻止非必要的选择器更新，因此可以使用懒集（没有竞争条件）
                             */
                            nextWakeupNanos.lazySet(AWAKE);
                        }
                        // fall through
                    default:
                    }
                } catch (IOException e) {
                    // If we receive an IOException here its because the Selector is messed up. Let's rebuild
                    // the selector and retry. https://github.com/netty/netty/issues/8566
                    /*
                        如果我们在这收到了输入输出异常，是因为它的选择器搞砸了。
                        让我们重新构建选择器，并且再次尝试。
                     */
                    // 重新构建选择器
                    rebuildSelector0();
                    // 将进行选择操作的计数清零
                    selectCnt = 0;
                    // 处理循环异常
                    handleLoopException(e);
                    // 跳过本轮循环
                    continue;
                }

                // 更新一些值
                selectCnt++;
                cancelledKeys = 0;
                needsToSelectAgain = false;

                // 获得输入输出率
                final int ioRatio = this.ioRatio;
                boolean ranTasks;

                // 如果输入输出率为100
                if (ioRatio == 100) {
                    /*
                        以下不细究
                     */
                    try {
                        if (strategy > 0) {
                            processSelectedKeys();
                        }
                    } finally {
                        // Ensure we always run tasks.
                        ranTasks = runAllTasks();
                    }
                } else if (strategy > 0) {
                    // 记录输入输出开始时间
                    final long ioStartTime = System.nanoTime();
                    try {
                        // 处理被选择的键
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        // 确保我们总是运行任务。
                        final long ioTime = System.nanoTime() - ioStartTime;
                        // 运行所有任务
                        ranTasks = runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                } else {
                    // 这将运行任务的最小数量
                    ranTasks = runAllTasks(0); // This will run the minimum number of tasks
                }

                // 如果运行了任务并且策略值大于0
                if (ranTasks || strategy > 0) {
                    // 如果选择的次数大于最小过早选择器返回次数，则记录日志
                    if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS && logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                                selectCnt - 1, selector);
                    }
                    // 重置选择次数
                    selectCnt = 0;
                } else if (unexpectedSelectorWakeup(selectCnt)) { // Unexpected wakeup (unusual case)
                    // 重置选择次数
                    selectCnt = 0;
                }
            } catch (CancelledKeyException e) {
                /*
                    以下不细究
                 */
                // Harmless exception - log anyway
                if (logger.isDebugEnabled()) {
                    logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                            selector, e);
                }
            } catch (Error e) {
                // 不细究
                throw e;
            } catch (Throwable t) {
                // 处理循环异常
                handleLoopException(t);
            } finally {
                // Always handle shutdown even if the loop processing threw an exception.
                // 即使循环处理抛出了异常，也总是处理关闭。
                try {
                    // 如果当前nio事件循环正在关闭
                    if (isShuttingDown()) {
                        /*
                            以下不细究
                         */
                        closeAll();
                        if (confirmShutdown()) {
                            return;
                        }
                    }
                } catch (Error e) {
                    // 不细究
                    throw e;
                } catch (Throwable t) {
                    // 处理循环异常
                    handleLoopException(t);
                }
            }
        }
    }

    // returns true if selectCnt should be reset

    /**
     * 如果选择次数应该被重置，则返回真
     *
     * @param selectCnt 选择次数
     * @return 选择次数是否应该被重置
     */
    private boolean unexpectedSelectorWakeup(int selectCnt) {
        // 如果当前线程被中断了
        if (Thread.interrupted()) {
            // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
            // As this is most likely a bug in the handler of the user or it's client library we will
            // also log it.
            //
            // See https://github.com/netty/netty/issues/2426
            /*
                线程被中断了，所以重置选择键并打断，使我们不再进入繁忙循环。
                因为这最有可能是用户处理者或客户链接里的故障，我们也要记录它。
             */
            if (logger.isDebugEnabled()) {
                logger.debug("Selector.select() returned prematurely because " +
                        "Thread.currentThread().interrupt() was called. Use " +
                        "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
            }
            return true;
        }

        // 如果选择器自动重建阈值大于0，并且选择的次数大于等于选择器自动重建阈值
        if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
            // The selector returned prematurely many times in a row.
            // Rebuild the selector to work around the problem.
            /*
                选择器连续多次提前返回。
                重建选择器以解决问题。
             */
            // 记录日志，重建选择器，并返回真
            logger.warn("Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                    selectCnt, selector);
            rebuildSelector();
            return true;
        }
        return false;
    }

    /**
     * 处理循环异常
     *
     * @param t 可抛出的异常
     */
    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        // 阻止可能会导致过多中央处理单元消耗的连续立即失败。
        try {
            // 休眠一秒
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
            // 忽略异常
        }
    }

    /**
     * 处理所有的键
     */
    private void processSelectedKeys() {
        // 如果被选择的键不为null
        if (selectedKeys != null) {
            // 处理优化的选择键
            processSelectedKeysOptimized();
        } else {
            // 不细究
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys ++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }

        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            i.remove();

            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (!i.hasNext()) {
                break;
            }

            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    /**
     * 处理优化的选择键
     */
    private void processSelectedKeysOptimized() {
        // 依次遍历处理
        for (int i = 0; i < selectedKeys.size; ++i) {
            // 获得当前选择键
            final SelectionKey k = selectedKeys.keys[i];

            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            /*
                通道关闭后，清空数组中的条目以允许它垃圾回收结束。
             */
            selectedKeys.keys[i] = null;

            // 获得该选择键上的附加物
            final Object a = k.attachment();

            // 根据附加对象的不同，做不同处理
            if (a instanceof AbstractNioChannel) {
                // 强转附加对象为nio通道，处理选择键
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                /*
                    以下不细究
                 */
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            // 如果需要去再次选择
            if (needsToSelectAgain) {
                /*
                    以下不细究
                 */
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }
        }
    }

    /**
     * 处理选择键
     *
     * @param k 选择键
     * @param ch Nio通道
     */
    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        // 获得入参nio通道的不安全实例
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        // 如果选择键不是有效的
        if (!k.isValid()) {
            /*
                以下不细究
             */
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            if (eventLoop == this) {
                // close the channel if the key is not valid anymore
                unsafe.close(unsafe.voidPromise());
            }
            return;
        }

        try {
            // 获得入参选择键的就绪操作
            int readyOps = k.readyOps();

            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            /*
                在尝试去触发读方法和写方法之前，我们首先需要去调用关闭连接方法，否则nio的jdk通道实现可能会抛出还未连接异常。
             */
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                /*
                    以下不细究
                 */

                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                /*
                    移除连接操作，否则选择器的选择方法将总是不阻塞地返回
                 */
                // 获得入参选择键的感兴趣操作，重新设置入参选择键的感兴趣操作
                int ops = k.interestOps();
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);

                // 不安全实例做结束连接操作
                unsafe.finishConnect();
            }

            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            // 首先处理写入操作，因为我们也能够写入一些排队缓冲，并且释放内存。
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                /*
                    以下不细究
                 */
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                ch.unsafe().forceFlush();
            }

            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            // 也要检查0的就绪集，已解决可能导致自旋循环的jdk bug。
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                // 通道不安全实例做读操作
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            // 以下不细究
            unsafe.close(unsafe.voidPromise());
        }
    }

    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
            case 0:
                k.cancel();
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            default:
                 break;
            }
        }
    }

    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop && nextWakeupNanos.getAndSet(AWAKE) != AWAKE) {
            selector.wakeup();
        }
    }

    @Override
    protected boolean beforeScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    @Override
    protected boolean afterScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    /**
     * 返回原装选择器
     *
     * @return 原装选择器
     */
    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    /**
     * 立刻做选择操作
     *
     * @return 选择操作的结果
     * @throws IOException 输入输出异常
     */
    int selectNow() throws IOException {
        return selector.selectNow();
    }

    /**
     * 做选择操作
     *
     * @param deadlineNanos 截止时间（纳秒）
     * @return 就绪操作设置为已更新的键集的数量
     * @throws IOException 输入输出异常
     */
    private int select(long deadlineNanos) throws IOException {
        // 如果没设置选择结束时间，则做阻塞的选择操作
        if (deadlineNanos == NONE) {
            // 做阻塞的选择操作，直到选择器有选择返回
            return selector.select();
        }

        /*
            以下不细究
         */
        // Timeout will only be 0 if deadline is within 5 microsecs
        long timeoutMillis = deadlineToDelayNanos(deadlineNanos + 995000L) / 1000000L;
        return timeoutMillis <= 0 ? selector.selectNow() : selector.select(timeoutMillis);
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}

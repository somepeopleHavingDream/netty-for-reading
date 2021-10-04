/*
 * Copyright 2019 The Netty Project
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
package io.netty.util.internal;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FastThreadLocal;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * Allow to retrieve the {@link EventExecutor} for the calling {@link Thread}.
 *
 * 允许为调用线程取出一个事件执行器。
 */
public final class ThreadExecutorMap {

    /**
     * 快速线程本地，存储了当前使用的事件执行器（该事件执行器一般为NioEventLoop）
     */
    private static final FastThreadLocal<EventExecutor> mappings = new FastThreadLocal<EventExecutor>();

    private ThreadExecutorMap() { }

    /**
     * Returns the current {@link EventExecutor} that uses the {@link Thread}, or {@code null} if none / unknown.
     */
    public static EventExecutor currentExecutor() {
        return mappings.get();
    }

    /**
     * Set the current {@link EventExecutor} that is used by the {@link Thread}.
     *
     * 设置被当前线程使用的事件执行器。
     */
    private static void setCurrentEventExecutor(EventExecutor executor) {
        mappings.set(executor);
    }

    /**
     * Decorate the given {@link Executor} and ensure {@link #currentExecutor()} will return {@code eventExecutor}
     * when called from within the {@link Runnable} during execution.
     *
     * 装饰给定执行器，并且确保当从执行时的可运行中调用时，当前执行器将返回事件执行器。
     */
    public static Executor apply(final Executor executor, final EventExecutor eventExecutor) {
        // 检查入参执行器和事件执行器，executor一般是ThreadPerTaskExecutor实例，eventExecutor一般是NioEventLoop实例
        ObjectUtil.checkNotNull(executor, "executor");
        ObjectUtil.checkNotNull(eventExecutor, "eventExecutor");

        // 实例化并且返回执行器
        return new Executor() {
            @Override
            public void execute(final Runnable command) {
                // 申请并执行任务
                executor.execute(apply(command, eventExecutor));
            }
        };
    }

    /**
     * Decorate the given {@link Runnable} and ensure {@link #currentExecutor()} will return {@code eventExecutor}
     * when called from within the {@link Runnable} during execution.
     *
     * 装饰给定的可运行实例，并且确保当前执行器在执行期从可运行实例中调用时，将返回事件执行器。
     */
    public static Runnable apply(final Runnable command, final EventExecutor eventExecutor) {
        // 检查可运行实例和时间执行器
        ObjectUtil.checkNotNull(command, "command");
        ObjectUtil.checkNotNull(eventExecutor, "eventExecutor");

        // 实例化并返回一个可运行实例
        return new Runnable() {
            @Override
            public void run() {
                // 设置当前事件执行器
                setCurrentEventExecutor(eventExecutor);
                try {
                    command.run();
                } finally {
                    setCurrentEventExecutor(null);
                }
            }
        };
    }

    /**
     * Decorate the given {@link ThreadFactory} and ensure {@link #currentExecutor()} will return {@code eventExecutor}
     * when called from within the {@link Runnable} during execution.
     */
    public static ThreadFactory apply(final ThreadFactory threadFactory, final EventExecutor eventExecutor) {
        ObjectUtil.checkNotNull(threadFactory, "command");
        ObjectUtil.checkNotNull(eventExecutor, "eventExecutor");
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return threadFactory.newThread(apply(r, eventExecutor));
            }
        };
    }
}

/*
 * Copyright 2013 The Netty Project
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
package io.netty.util.concurrent;

import io.netty.util.internal.*;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class DefaultPromise<V> extends AbstractFuture<V> implements Promise<V> {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultPromise.class);
    private static final InternalLogger rejectedExecutionLogger =
            InternalLoggerFactory.getInstance(DefaultPromise.class.getName() + ".rejectedExecution");
    private static final int MAX_LISTENER_STACK_DEPTH = Math.min(8,
            SystemPropertyUtil.getInt("io.netty.defaultPromise.maxListenerStackDepth", 8));

    /**
     * 计算结果更新器
     */
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultPromise, Object> RESULT_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(DefaultPromise.class, Object.class, "result");

    /**
     * 代表计算成功的对象实例
     */
    private static final Object SUCCESS = new Object();

    /**
     * 不可取消
     */
    private static final Object UNCANCELLABLE = new Object();

    private static final CauseHolder CANCELLATION_CAUSE_HOLDER = new CauseHolder(
            StacklessCancellationException.newInstance(DefaultPromise.class, "cancel(...)"));
    private static final StackTraceElement[] CANCELLATION_STACK = CANCELLATION_CAUSE_HOLDER.cause.getStackTrace();

    /**
     * 当前承诺的结果
     */
    private volatile Object result;

    /**
     * 当前承诺的事件执行器
     */
    private final EventExecutor executor;

    /**
     * One or more listeners. Can be a {@link GenericFutureListener} or a {@link DefaultFutureListeners}.
     * If {@code null}, it means either 1) no listeners were added yet or 2) all listeners were notified.
     *
     * 一个或多个监听者。
     * 可以是通用未来监听者或者默认未来监听者。
     * 如果为null，则意味着，要么至今为止没有监听者被添加，要么所有的监听者都被通知。
     *
     * Threading - synchronized(this). We must support adding listeners when there is no EventExecutor.
     *
     * 线程的 - 同步当前实例。
     * 当没有事件执行器时，我们必须支持添加监听者。
     */
    private Object listeners;

    /**
     * Threading - synchronized(this). We are required to hold the monitor to use Java's underlying wait()/notifyAll().
     *
     * 线程的 - 同步此实例。
     * 我们需要拥有一个监视器以使用java底层的等待/通知所有方法。
     */
    private short waiters;

    /**
     * Threading - synchronized(this). We must prevent concurrent notification and FIFO listener notification if the
     * executor changes.
     *
     * 线程的 - 同步（当前对象）。
     * 如果执行器改变，我们必须阻止并发通知并且先进先出的通知监听者。
     */
    private boolean notifyingListeners;

    /**
     * Creates a new instance.
     *
     * 创建一个新的实例。
     *
     * It is preferable to use {@link EventExecutor#newPromise()} to create a new promise
     *
     * 最好使用事件执行器的实例承诺方法去创建一个新的实例
     *
     * @param executor
     *        the {@link EventExecutor} which is used to notify the promise once it is complete.
     *        It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     *        The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     *        depth exceeds a threshold.
     *
     */
    public DefaultPromise(EventExecutor executor) {
        // 设置执行器
        this.executor = checkNotNull(executor, "executor");
    }

    /**
     * See {@link #executor()} for expectations of the executor.
     */
    protected DefaultPromise() {
        // only for subclasses
        executor = null;
    }

    @Override
    public Promise<V> setSuccess(V result) {
        if (setSuccess0(result)) {
            return this;
        }
        throw new IllegalStateException("complete already: " + this);
    }

    @Override
    public boolean trySuccess(V result) {
        return setSuccess0(result);
    }

    @Override
    public Promise<V> setFailure(Throwable cause) {
        if (setFailure0(cause)) {
            return this;
        }
        throw new IllegalStateException("complete already: " + this, cause);
    }

    @Override
    public boolean tryFailure(Throwable cause) {
        return setFailure0(cause);
    }

    @Override
    public boolean setUncancellable() {
        if (RESULT_UPDATER.compareAndSet(this, null, UNCANCELLABLE)) {
            return true;
        }

        /*
            以下不细究
         */

        Object result = this.result;
        return !isDone0(result) || !isCancelled0(result);
    }

    @Override
    public boolean isSuccess() {
        // 获得通道承诺的计算结果
        Object result = this.result;
        // 如果通道承诺的计算结果存在，且该结果不是不可需要的，并且该计算结果也不是原因拥有者实例，则返回真，代表成功
        return result != null && result != UNCANCELLABLE && !(result instanceof CauseHolder);
    }

    @Override
    public boolean isCancellable() {
        return result == null;
    }

    private static final class LeanCancellationException extends CancellationException {
        private static final long serialVersionUID = 2794674970981187807L;

        // Suppress a warning since the method doesn't need synchronization
        @Override
        public Throwable fillInStackTrace() {   // lgtm[java/non-sync-override]
            setStackTrace(CANCELLATION_STACK);
            return this;
        }

        @Override
        public String toString() {
            return CancellationException.class.getName();
        }
    }

    @Override
    public Throwable cause() {
        return cause0(result);
    }

    private Throwable cause0(Object result) {
        // 如果结果不是原因持有者，则返回null
        if (!(result instanceof CauseHolder)) {
            return null;
        }
        /*
            以下不细究
         */
        if (result == CANCELLATION_CAUSE_HOLDER) {
            CancellationException ce = new LeanCancellationException();
            if (RESULT_UPDATER.compareAndSet(this, CANCELLATION_CAUSE_HOLDER, new CauseHolder(ce))) {
                return ce;
            }
            result = this.result;
        }
        return ((CauseHolder) result).cause;
    }

    @Override
    public Promise<V> addListener(GenericFutureListener<? extends Future<? super V>> listener) {
        // 检查监听器是否为null
        checkNotNull(listener, "listener");

        // 锁住当前对象
        synchronized (this) {
            // 添加监听者
            addListener0(listener);
        }

        // 如果该承诺已经完成，则通知所有监听者
        if (isDone()) {
            // 通知所有监听者
            notifyListeners();
        }

        return this;
    }

    @Override
    public Promise<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners) {
        checkNotNull(listeners, "listeners");

        synchronized (this) {
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                if (listener == null) {
                    break;
                }
                addListener0(listener);
            }
        }

        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

    @Override
    public Promise<V> removeListener(final GenericFutureListener<? extends Future<? super V>> listener) {
        checkNotNull(listener, "listener");

        synchronized (this) {
            removeListener0(listener);
        }

        return this;
    }

    @Override
    public Promise<V> removeListeners(final GenericFutureListener<? extends Future<? super V>>... listeners) {
        checkNotNull(listeners, "listeners");

        synchronized (this) {
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                if (listener == null) {
                    break;
                }
                removeListener0(listener);
            }
        }

        return this;
    }

    @Override
    public Promise<V> await() throws InterruptedException {
        // 如果当前承诺是完成的，则返回承诺实例本身
        if (isDone()) {
            return this;
        }

        // 当前线程是否被中断，若被中断，则抛出异常
        if (Thread.interrupted()) {
            throw new InterruptedException(toString());
        }

        // 检查死锁
        checkDeadLock();

        // 锁住当前实例
        synchronized (this) {
            // 当当前承诺未完成，则不退出循环
            while (!isDone()) {
                // 增加等待者
                incWaiters();
                try {
                    // 放开对当前实例的锁，等待唤醒通知
                    wait();
                } finally {
                    // 减少等待者
                    decWaiters();
                }
            }
        }

        // 返回当前承诺实例
        return this;
    }

    @Override
    public Promise<V> awaitUninterruptibly() {
        if (isDone()) {
            return this;
        }

        checkDeadLock();

        boolean interrupted = false;
        synchronized (this) {
            while (!isDone()) {
                incWaiters();
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Interrupted while waiting.
                    interrupted = true;
                } finally {
                    decWaiters();
                }
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        return this;
    }

    @Override
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return await0(unit.toNanos(timeout), true);
    }

    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
        return await0(MILLISECONDS.toNanos(timeoutMillis), true);
    }

    @Override
    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        try {
            return await0(unit.toNanos(timeout), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    @Override
    public boolean awaitUninterruptibly(long timeoutMillis) {
        try {
            return await0(MILLISECONDS.toNanos(timeoutMillis), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public V getNow() {
        Object result = this.result;
        if (result instanceof CauseHolder || result == SUCCESS || result == UNCANCELLABLE) {
            return null;
        }
        return (V) result;
    }

    @SuppressWarnings("unchecked")
    @Override
    public V get() throws InterruptedException, ExecutionException {
        Object result = this.result;
        if (!isDone0(result)) {
            await();
            result = this.result;
        }
        if (result == SUCCESS || result == UNCANCELLABLE) {
            return null;
        }
        Throwable cause = cause0(result);
        if (cause == null) {
            return (V) result;
        }
        if (cause instanceof CancellationException) {
            throw (CancellationException) cause;
        }
        throw new ExecutionException(cause);
    }

    @SuppressWarnings("unchecked")
    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        Object result = this.result;
        if (!isDone0(result)) {
            if (!await(timeout, unit)) {
                throw new TimeoutException();
            }
            result = this.result;
        }
        if (result == SUCCESS || result == UNCANCELLABLE) {
            return null;
        }
        Throwable cause = cause0(result);
        if (cause == null) {
            return (V) result;
        }
        if (cause instanceof CancellationException) {
            throw (CancellationException) cause;
        }
        throw new ExecutionException(cause);
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (RESULT_UPDATER.compareAndSet(this, null, CANCELLATION_CAUSE_HOLDER)) {
            if (checkNotifyWaiters()) {
                notifyListeners();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean isCancelled() {
        return isCancelled0(result);
    }

    @Override
    public boolean isDone() {
        // 返回当前默认承诺是否已经完成
        return isDone0(result);
    }

    @Override
    public Promise<V> sync() throws InterruptedException {
        // 等待
        await();
        // 如果失败的话，则再抛出异常
        rethrowIfFailed();
        // 返回当前承诺实例本身
        return this;
    }

    @Override
    public Promise<V> syncUninterruptibly() {
        awaitUninterruptibly();
        rethrowIfFailed();
        return this;
    }

    @Override
    public String toString() {
        return toStringBuilder().toString();
    }

    protected StringBuilder toStringBuilder() {
        StringBuilder buf = new StringBuilder(64)
                .append(StringUtil.simpleClassName(this))
                .append('@')
                .append(Integer.toHexString(hashCode()));

        Object result = this.result;
        if (result == SUCCESS) {
            buf.append("(success)");
        } else if (result == UNCANCELLABLE) {
            buf.append("(uncancellable)");
        } else if (result instanceof CauseHolder) {
            buf.append("(failure: ")
                    .append(((CauseHolder) result).cause)
                    .append(')');
        } else if (result != null) {
            buf.append("(success: ")
                    .append(result)
                    .append(')');
        } else {
            buf.append("(incomplete)");
        }

        return buf;
    }

    /**
     * Get the executor used to notify listeners when this promise is complete.
     * <p>
     * It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     * The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     * depth exceeds a threshold.
     * @return The executor used to notify listeners when this promise is complete.
     */
    protected EventExecutor executor() {
        return executor;
    }

    protected void checkDeadLock() {
        // 获得当前承诺的事件执行器
        EventExecutor e = executor();
        // 如果当前承诺的事件执行器存在，并且仍存在于事件循环中，则抛出阻塞操作异常
        if (e != null && e.inEventLoop()) {
            throw new BlockingOperationException(toString());
        }
    }

    /**
     * Notify a listener that a future has completed.
     * <p>
     * This method has a fixed depth of {@link #MAX_LISTENER_STACK_DEPTH} that will limit recursion to prevent
     * {@link StackOverflowError} and will stop notifying listeners added after this threshold is exceeded.
     * @param eventExecutor the executor to use to notify the listener {@code listener}.
     * @param future the future that is complete.
     * @param listener the listener to notify.
     */
    protected static void notifyListener(
            EventExecutor eventExecutor, final Future<?> future, final GenericFutureListener<?> listener) {
        notifyListenerWithStackOverFlowProtection(
                checkNotNull(eventExecutor, "eventExecutor"),
                checkNotNull(future, "future"),
                checkNotNull(listener, "listener"));
    }

    /**
     * 通知监听者
     */
    private void notifyListeners() {
        // 获得当前承诺的事件执行器
        EventExecutor executor = executor();

        // 如果该事件执行器处于事件循环中
        if (executor.inEventLoop()) {
            // 获得内部线程本地映射
            final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
            // 通过内部线程本地映射获得栈深度
            final int stackDepth = threadLocals.futureListenerStackDepth();

            // 如果栈深度小于最大监听者栈深度
            if (stackDepth < MAX_LISTENER_STACK_DEPTH) {
                // 内部线程本地映射设置未来监听者栈深度
                threadLocals.setFutureListenerStackDepth(stackDepth + 1);
                try {
                    // 马上通知监听者
                    notifyListenersNow();
                } finally {
                    // 内部线程本地映射设置为原来的未来监听者栈深度
                    threadLocals.setFutureListenerStackDepth(stackDepth);
                }
                return;
            }
        }

        // 安全执行
        safeExecute(executor, new Runnable() {
            @Override
            public void run() {
                // 马上通知监听者
                notifyListenersNow();
            }
        });
    }

    /**
     * The logic in this method should be identical to {@link #notifyListeners()} but
     * cannot share code because the listener(s) cannot be cached for an instance of {@link DefaultPromise} since the
     * listener(s) may be changed and is protected by a synchronized operation.
     */
    private static void notifyListenerWithStackOverFlowProtection(final EventExecutor executor,
                                                                  final Future<?> future,
                                                                  final GenericFutureListener<?> listener) {
        if (executor.inEventLoop()) {
            final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
            final int stackDepth = threadLocals.futureListenerStackDepth();
            if (stackDepth < MAX_LISTENER_STACK_DEPTH) {
                threadLocals.setFutureListenerStackDepth(stackDepth + 1);
                try {
                    notifyListener0(future, listener);
                } finally {
                    threadLocals.setFutureListenerStackDepth(stackDepth);
                }
                return;
            }
        }

        safeExecute(executor, new Runnable() {
            @Override
            public void run() {
                notifyListener0(future, listener);
            }
        });
    }

    /**
     * 马上通知所有监听者
     */
    private void notifyListenersNow() {
        Object listeners;

        // 锁住当前实例
        synchronized (this) {
            // Only proceed if there are listeners to notify and we are not already notifying listeners.
            // 当且仅当有要通知的监听者，并且我们还未通知监听者，我们才处理。
            if (notifyingListeners || this.listeners == null) {
                return;
            }
            // 设置通知监听者标记
            notifyingListeners = true;
            // 获得监听者，并清空该承诺的监听者
            listeners = this.listeners;
            this.listeners = null;
        }

        for (;;) {
            // 如果监听者是默认未来监听者实例
            if (listeners instanceof DefaultFutureListeners) {
                // 以下不细究
                notifyListeners0((DefaultFutureListeners) listeners);
            } else {
                // 如果监听者不是默认未来监听者实例，通知监听者
                notifyListener0(this, (GenericFutureListener<?>) listeners);
            }

            // 锁住当前实例，更改监听者的一些信息
            synchronized (this) {
                if (this.listeners == null) {
                    // Nothing can throw from within this method, so setting notifyingListeners back to false does not
                    // need to be in a finally block.
                    notifyingListeners = false;
                    return;
                }
                listeners = this.listeners;
                this.listeners = null;
            }
        }
    }

    private void notifyListeners0(DefaultFutureListeners listeners) {
        GenericFutureListener<?>[] a = listeners.listeners();
        int size = listeners.size();
        for (int i = 0; i < size; i ++) {
            notifyListener0(this, a[i]);
        }
    }

    /**
     * 通知监听者
     *
     * @param future 未来
     * @param l 通用未来监听者
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyListener0(Future future, GenericFutureListener l) {
        try {
            // 入参通用未来监听者操作完成
            l.operationComplete(future);
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationComplete()", t);
            }
        }
    }

    /**
     * 对当前承诺添加监听者
     *
     * @param listener 通用未来监听者
     */
    private void addListener0(GenericFutureListener<? extends Future<? super V>> listener) {
        // 如果当前还没有监听者，则设置监听者
        if (listeners == null) {
            listeners = listener;
        } else if (listeners instanceof DefaultFutureListeners) {
            // 以下不细究
            ((DefaultFutureListeners) listeners).add(listener);
        } else {
            // 以下不细究
            listeners = new DefaultFutureListeners((GenericFutureListener<?>) listeners, listener);
        }
    }

    private void removeListener0(GenericFutureListener<? extends Future<? super V>> listener) {
        if (listeners instanceof DefaultFutureListeners) {
            ((DefaultFutureListeners) listeners).remove(listener);
        } else if (listeners == listener) {
            listeners = null;
        }
    }

    /**
     * 设置成功
     *
     * @param result 计算结果
     * @return 是否设置成功
     */
    private boolean setSuccess0(V result) {
        return setValue0(result == null ? SUCCESS : result);
    }

    private boolean setFailure0(Throwable cause) {
        // 实例化并设置原因持有器
        return setValue0(new CauseHolder(checkNotNull(cause, "cause")));
    }

    /**
     * 设置值
     *
     * @param objResult 对象结果
     * @return 是否设置成功
     */
    private boolean setValue0(Object objResult) {
        if (RESULT_UPDATER.compareAndSet(this, null, objResult) ||
            RESULT_UPDATER.compareAndSet(this, UNCANCELLABLE, objResult)) {
            // 检查通知等待者
            if (checkNotifyWaiters()) {
                // 通知监听者
                notifyListeners();
            }
            return true;
        }
        return false;
    }

    /**
     * Check if there are any waiters and if so notify these.
     * @return {@code true} if there are any listeners attached to the promise, {@code false} otherwise.
     *
     * 检查是否有等待者，如果有的话则通知。
     * 如果有附加在承诺之上的监听器，则返回真，否则返回假。
     */
    private synchronized boolean checkNotifyWaiters() {
        // 如果存在等待者，则通知所有等待者
        if (waiters > 0) {
            // 对等待当前实例的所有线程进行通知
            notifyAll();
        }

        // 如果监听者不为null，则返回真，否则返回假
        return listeners != null;
    }

    private void incWaiters() {
        // 如果等待者的数量到达短整形的最大值，则抛出违规状态异常
        if (waiters == Short.MAX_VALUE) {
            throw new IllegalStateException("too many waiters: " + this);
        }

        // 等待者数量加1
        ++waiters;
    }

    private void decWaiters() {
        // 等待者数减1
        --waiters;
    }

    private void rethrowIfFailed() {
        // 获得可抛出原因
        Throwable cause = cause();
        // 如果可抛出原因不存在，则直接返回
        if (cause == null) {
            return;
        }

        // 以下不细究
        PlatformDependent.throwException(cause);
    }

    private boolean await0(long timeoutNanos, boolean interruptable) throws InterruptedException {
        if (isDone()) {
            return true;
        }

        if (timeoutNanos <= 0) {
            return isDone();
        }

        if (interruptable && Thread.interrupted()) {
            throw new InterruptedException(toString());
        }

        checkDeadLock();

        // Start counting time from here instead of the first line of this method,
        // to avoid/postpone performance cost of System.nanoTime().
        final long startTime = System.nanoTime();
        synchronized (this) {
            boolean interrupted = false;
            try {
                long waitTime = timeoutNanos;
                while (!isDone() && waitTime > 0) {
                    incWaiters();
                    try {
                        wait(waitTime / 1000000, (int) (waitTime % 1000000));
                    } catch (InterruptedException e) {
                        if (interruptable) {
                            throw e;
                        } else {
                            interrupted = true;
                        }
                    } finally {
                        decWaiters();
                    }
                    // Check isDone() in advance, try to avoid calculating the elapsed time later.
                    if (isDone()) {
                        return true;
                    }
                    // Calculate the elapsed time here instead of in the while condition,
                    // try to avoid performance cost of System.nanoTime() in the first loop of while.
                    waitTime = timeoutNanos - (System.nanoTime() - startTime);
                }
                return isDone();
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Notify all progressive listeners.
     * <p>
     * No attempt is made to ensure notification order if multiple calls are made to this method before
     * the original invocation completes.
     * <p>
     * This will do an iteration over all listeners to get all of type {@link GenericProgressiveFutureListener}s.
     * @param progress the new progress.
     * @param total the total progress.
     */
    @SuppressWarnings("unchecked")
    void notifyProgressiveListeners(final long progress, final long total) {
        final Object listeners = progressiveListeners();
        if (listeners == null) {
            return;
        }

        final ProgressiveFuture<V> self = (ProgressiveFuture<V>) this;

        EventExecutor executor = executor();
        if (executor.inEventLoop()) {
            if (listeners instanceof GenericProgressiveFutureListener[]) {
                notifyProgressiveListeners0(
                        self, (GenericProgressiveFutureListener<?>[]) listeners, progress, total);
            } else {
                notifyProgressiveListener0(
                        self, (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners, progress, total);
            }
        } else {
            if (listeners instanceof GenericProgressiveFutureListener[]) {
                final GenericProgressiveFutureListener<?>[] array =
                        (GenericProgressiveFutureListener<?>[]) listeners;
                safeExecute(executor, new Runnable() {
                    @Override
                    public void run() {
                        notifyProgressiveListeners0(self, array, progress, total);
                    }
                });
            } else {
                final GenericProgressiveFutureListener<ProgressiveFuture<V>> l =
                        (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners;
                safeExecute(executor, new Runnable() {
                    @Override
                    public void run() {
                        notifyProgressiveListener0(self, l, progress, total);
                    }
                });
            }
        }
    }

    /**
     * Returns a {@link GenericProgressiveFutureListener}, an array of {@link GenericProgressiveFutureListener}, or
     * {@code null}.
     */
    private synchronized Object progressiveListeners() {
        Object listeners = this.listeners;
        if (listeners == null) {
            // No listeners added
            return null;
        }

        if (listeners instanceof DefaultFutureListeners) {
            // Copy DefaultFutureListeners into an array of listeners.
            DefaultFutureListeners dfl = (DefaultFutureListeners) listeners;
            int progressiveSize = dfl.progressiveSize();
            switch (progressiveSize) {
                case 0:
                    return null;
                case 1:
                    for (GenericFutureListener<?> l: dfl.listeners()) {
                        if (l instanceof GenericProgressiveFutureListener) {
                            return l;
                        }
                    }
                    return null;
            }

            GenericFutureListener<?>[] array = dfl.listeners();
            GenericProgressiveFutureListener<?>[] copy = new GenericProgressiveFutureListener[progressiveSize];
            for (int i = 0, j = 0; j < progressiveSize; i ++) {
                GenericFutureListener<?> l = array[i];
                if (l instanceof GenericProgressiveFutureListener) {
                    copy[j ++] = (GenericProgressiveFutureListener<?>) l;
                }
            }

            return copy;
        } else if (listeners instanceof GenericProgressiveFutureListener) {
            return listeners;
        } else {
            // Only one listener was added and it's not a progressive listener.
            return null;
        }
    }

    private static void notifyProgressiveListeners0(
            ProgressiveFuture<?> future, GenericProgressiveFutureListener<?>[] listeners, long progress, long total) {
        for (GenericProgressiveFutureListener<?> l: listeners) {
            if (l == null) {
                break;
            }
            notifyProgressiveListener0(future, l, progress, total);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyProgressiveListener0(
            ProgressiveFuture future, GenericProgressiveFutureListener l, long progress, long total) {
        try {
            l.operationProgressed(future, progress, total);
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationProgressed()", t);
            }
        }
    }

    private static boolean isCancelled0(Object result) {
        return result instanceof CauseHolder && ((CauseHolder) result).cause instanceof CancellationException;
    }

    /**
     * 该承诺是否已经完成
     *
     * @param result 计算结果
     * @return 该承诺是否已经完成
     */
    private static boolean isDone0(Object result) {
        // 如果结果不为null，并且结果不是不可取消对象实例，则返回真
        return result != null && result != UNCANCELLABLE;
    }

    private static final class CauseHolder {
        final Throwable cause;
        CauseHolder(Throwable cause) {
            this.cause = cause;
        }
    }

    /**
     * 安全执行
     *
     * @param executor 事件执行器
     * @param task 可运行任务
     */
    private static void safeExecute(EventExecutor executor, Runnable task) {
        try {
            executor.execute(task);
        } catch (Throwable t) {
            rejectedExecutionLogger.error("Failed to submit a listener notification task. Event loop shut down?", t);
        }
    }

    private static final class StacklessCancellationException extends CancellationException {

        private static final long serialVersionUID = -2974906711413716191L;

        private StacklessCancellationException() { }

        // Override fillInStackTrace() so we not populate the backtrace via a native call and so leak the
        // Classloader.
        @Override
        public Throwable fillInStackTrace() {
            return this;
        }

        static StacklessCancellationException newInstance(Class<?> clazz, String method) {
            return ThrowableUtil.unknownStackTrace(new StacklessCancellationException(), clazz, method);
        }
    }
}

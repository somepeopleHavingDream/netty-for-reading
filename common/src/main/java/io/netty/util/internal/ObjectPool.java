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

import io.netty.util.Recycler;

/**
 * Light-weight object pool.
 *
 * 轻量级对象池。
 *
 * @param <T> the type of the pooled object
 */
public abstract class ObjectPool<T> {

    ObjectPool() { }

    /**
     * Get a {@link Object} from the {@link ObjectPool}. The returned {@link Object} may be created via
     * {@link ObjectCreator#newObject(Handle)} if no pooled {@link Object} is ready to be reused.
     *
     * 从对象池中获得对象。
     * 如果没有就绪于重复使用的池化对象，则可通过对象创建器的实例化对象方法创建返回对象。
     */
    public abstract T get();

    /**
     * Handle for an pooled {@link Object} that will be used to notify the {@link ObjectPool} once it can
     * reuse the pooled {@link Object} again.
     * @param <T>
     */
    public interface Handle<T> {
        /**
         * Recycle the {@link Object} if possible and so make it ready to be reused.
         */
        void recycle(T self);
    }

    /**
     * Creates a new Object which references the given {@link Handle} and calls {@link Handle#recycle(Object)} once
     * it can be re-used.
     *
     * 创建一个引用给定处理器的新对象，并且一旦能重复使用就调用处理的回收方法。
     *
     * @param <T> the type of the pooled object
     */
    public interface ObjectCreator<T> {

        /**
         * Creates an returns a new {@link Object} that can be used and later recycled via
         * {@link Handle#recycle(Object)}.
         */
        T newObject(Handle<T> handle);
    }

    /**
     * Creates a new {@link ObjectPool} which will use the given {@link ObjectCreator} to create the {@link Object}
     * that should be pooled.
     *
     * 创建一个新的对象池，该对象池使用给定的对象创建器去创建应被池化的对象。
     */
    public static <T> ObjectPool<T> newPool(final ObjectCreator<T> creator) {
        // 实例化并返回回收器对象池
        return new RecyclerObjectPool<T>(ObjectUtil.checkNotNull(creator, "creator"));
    }

    /**
     * 回收器对象池
     *
     * @param <T>
     */
    private static final class RecyclerObjectPool<T> extends ObjectPool<T> {

        /**
         * 用于该回收器对象池的回收器
         */
        private final Recycler<T> recycler;

        /**
         * 回收器对象池的构造方法
         *
         * @param creator 对象创建器
         */
        RecyclerObjectPool(final ObjectCreator<T> creator) {
            // 实例化并设置回收器
             recycler = new Recycler<T>() {
                @Override
                protected T newObject(Handle<T> handle) {
                    return creator.newObject(handle);
                }
            };
        }

        @Override
        public T get() {
            // 回收器获得对象
            return recycler.get();
        }
    }
}

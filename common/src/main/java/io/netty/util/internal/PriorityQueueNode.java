/*
 * Copyright 2015 The Netty Project
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

/**
 * Provides methods for {@link DefaultPriorityQueue} to maintain internal state. These methods should generally not be
 * used outside the scope of {@link DefaultPriorityQueue}.
 *
 * 为默认优先级队列提供方法以维持内部状态。
 * 这些方法通常不应该在默认优先队列外部被使用。
 */
public interface PriorityQueueNode {

    /**
     * This should be used to initialize the storage returned by {@link #priorityQueueIndex(DefaultPriorityQueue)}.
     *
     * 这应该被用来初始化由优先队列索引方法返回的存储。
     *
     * 不在队列中的索引。
     */
    int INDEX_NOT_IN_QUEUE = -1;

    /**
     * Get the last value set by {@link #priorityQueueIndex(DefaultPriorityQueue, int)} for the value corresponding to
     * {@code queue}.
     *
     * 获得为队列对应的值由优先队列索引所设置的最后一个值。
     *
     * <p>
     * Throwing exceptions from this method will result in undefined behavior.
     *
     * 从此方法抛出的异常将引起未定义的行为。
     */
    int priorityQueueIndex(DefaultPriorityQueue<?> queue);

    /**
     * Used by {@link DefaultPriorityQueue} to maintain state for an element in the queue.
     * <p>
     * Throwing exceptions from this method will result in undefined behavior.
     * @param queue The queue for which the index is being set.
     * @param i The index as used by {@link DefaultPriorityQueue}.
     */
    void priorityQueueIndex(DefaultPriorityQueue<?> queue, int i);
}

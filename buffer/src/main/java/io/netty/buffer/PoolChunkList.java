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

package io.netty.buffer;

import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static java.lang.Math.max;
import static java.lang.Math.min;

final class PoolChunkList<T> implements PoolChunkListMetric {
    private static final Iterator<PoolChunkMetric> EMPTY_METRICS = Collections.<PoolChunkMetric>emptyList().iterator();

    private final PoolArena<T> arena;

    private final PoolChunkList<T> nextList;

    private final int minUsage;

    private final int maxUsage;

    private final int maxCapacity;

    private PoolChunk<T> head;
    private final int freeMinThreshold;
    private final int freeMaxThreshold;

    // This is only update once when create the linked like list of PoolChunkList in PoolArena constructor.
    private PoolChunkList<T> prevList;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunkList(PoolArena<T> arena, PoolChunkList<T> nextList, int minUsage, int maxUsage, int chunkSize) {
        // 断言：最小使用量必定小于等于最大使用量
        assert minUsage <= maxUsage;

        // 设置当前池块列表的竞技场数、下一个池块列表、最小使用量、最大使用量、块大小
        this.arena = arena;
        this.nextList = nextList;
        this.minUsage = minUsage;
        this.maxUsage = maxUsage;

        // 计算出并设置当前池块列表的最大容量
        maxCapacity = calculateMaxCapacity(minUsage, chunkSize);

        // the thresholds are aligned with PoolChunk.usage() logic:
        // 1) basic logic: usage() = 100 - freeBytes * 100L / chunkSize
        //    so, for example: (usage() >= maxUsage) condition can be transformed in the following way:
        //      100 - freeBytes * 100L / chunkSize >= maxUsage
        //      freeBytes <= chunkSize * (100 - maxUsage) / 100
        //      let freeMinThreshold = chunkSize * (100 - maxUsage) / 100, then freeBytes <= freeMinThreshold
        //
        //  2) usage() returns an int value and has a floor rounding during a calculation,
        //     to be aligned absolute thresholds should be shifted for "the rounding step":
        //       freeBytes * 100 / chunkSize < 1
        //       the condition can be converted to: freeBytes < 1 * chunkSize / 100
        //     this is why we have + 0.99999999 shifts. A example why just +1 shift cannot be used:
        //       freeBytes = 16777216 == freeMaxThreshold: 16777216, usage = 0 < minUsage: 1, chunkSize: 16777216
        //     At the same time we want to have zero thresholds in case of (maxUsage == 100) and (minUsage == 100).
        //

        // 设置当前池化块列表的释放最小和最大阈值
        freeMinThreshold = (maxUsage == 100) ? 0 : (int) (chunkSize * (100.0 - maxUsage + 0.99999999) / 100L);
        freeMaxThreshold = (minUsage == 100) ? 0 : (int) (chunkSize * (100.0 - minUsage + 0.99999999) / 100L);
    }

    /**
     * Calculates the maximum capacity of a buffer that will ever be possible to allocate out of the {@link PoolChunk}s
     * that belong to the {@link PoolChunkList} with the given {@code minUsage} and {@code maxUsage} settings.
     */
    private static int calculateMaxCapacity(int minUsage, int chunkSize) {
        // 计算出最小用量
        minUsage = minUsage0(minUsage);

        // 如果最小用量为100
        if (minUsage == 100) {
            // If the minUsage is 100 we can not allocate anything out of this list.
            // 如果最小值是100，我们将不再此列表里分配任何东西。
            return 0;
        }

        // Calculate the maximum amount of bytes that can be allocated from a PoolChunk in this PoolChunkList.
        //
        // As an example:
        // - If a PoolChunkList has minUsage == 25 we are allowed to allocate at most 75% of the chunkSize because
        //   this is the maximum amount available in any PoolChunk in this PoolChunkList.
        /*
            计算能从此池块列表的池块中被分配出的字节最大数。
         */
        return  (int) (chunkSize * (100L - minUsage) / 100L);
    }

    /**
     * 将入参池化块列表设置为当前池化块列表的上一列表
     *
     * @param prevList 将要被设置为上一链表的池化块列表
     */
    void prevList(PoolChunkList<T> prevList) {
        // 断言：入参池化块列表必不为null
        assert this.prevList == null;
        // 将入参池化块列表设置为当前池化块列表的上一列表
        this.prevList = prevList;
    }

    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int sizeIdx, PoolThreadCache threadCache) {
        // 从当前池块列表所在的池竞技场中，获得对应标准容量
        int normCapacity = arena.sizeIdx2size(sizeIdx);
        // 如果标准容量大于最大容量
        if (normCapacity > maxCapacity) {
            /*
                以下不细究
             */
            // Either this PoolChunkList is empty or the requested capacity is larger then the capacity which can
            // be handled by the PoolChunks that are contained in this PoolChunkList.
            return false;
        }

        // 遍历当前池块列表
        for (PoolChunk<T> cur = head; cur != null; cur = cur.next) {
            // 如果当前池块分配成功
            if (cur.allocate(buf, reqCapacity, sizeIdx, threadCache)) {
                // 如果当前池块的释放字节数小于等于最小可释放阈值
                if (cur.freeBytes <= freeMinThreshold) {
                    /*
                        以下不细究
                     */
                    remove(cur);
                    nextList.add(cur);
                }

                // 返回真，代表当前池块列表分配成功
                return true;
            }
        }

        // 返回假，代表当前池块列表分配失败
        return false;
    }

    boolean free(PoolChunk<T> chunk, long handle, int normCapacity, ByteBuffer nioBuffer) {
        chunk.free(handle, normCapacity, nioBuffer);
        if (chunk.freeBytes > freeMaxThreshold) {
            remove(chunk);
            // Move the PoolChunk down the PoolChunkList linked-list.
            return move0(chunk);
        }
        return true;
    }

    private boolean move(PoolChunk<T> chunk) {
        assert chunk.usage() < maxUsage;

        if (chunk.freeBytes > freeMaxThreshold) {
            // Move the PoolChunk down the PoolChunkList linked-list.
            return move0(chunk);
        }

        // PoolChunk fits into this PoolChunkList, adding it here.
        add0(chunk);
        return true;
    }

    /**
     * Moves the {@link PoolChunk} down the {@link PoolChunkList} linked-list so it will end up in the right
     * {@link PoolChunkList} that has the correct minUsage / maxUsage in respect to {@link PoolChunk#usage()}.
     */
    private boolean move0(PoolChunk<T> chunk) {
        if (prevList == null) {
            // There is no previous PoolChunkList so return false which result in having the PoolChunk destroyed and
            // all memory associated with the PoolChunk will be released.
            assert chunk.usage() == 0;
            return false;
        }
        return prevList.move(chunk);
    }

    void add(PoolChunk<T> chunk) {
        // 如果入参池块的释放字节数小于等于当前池块列表最小释放阈值
        if (chunk.freeBytes <= freeMinThreshold) {
            /*
                以下不细究
             */
            nextList.add(chunk);
            return;
        }

        // 将入参池块添加到当前池块列表中
        add0(chunk);
    }

    /**
     * Adds the {@link PoolChunk} to this {@link PoolChunkList}.
     */
    void add0(PoolChunk<T> chunk) {
        // 将入参池块的池块列表设值为当前池块列表
        chunk.parent = this;

        // 如果当前池块列表的头结点不存在
        if (head == null) {
            // 将当前池块列表的头结点设值为入参池块
            head = chunk;
            // 前驱和后继置为null
            chunk.prev = null;
            chunk.next = null;
        } else {
            // 如果当前池块列表的头结点存在，使用头插法将入参池块插入到当前池块列表头结点的前面，重置头结点为入参池块
            chunk.prev = null;
            chunk.next = head;
            head.prev = chunk;
            head = chunk;
        }
    }

    private void remove(PoolChunk<T> cur) {
        if (cur == head) {
            head = cur.next;
            if (head != null) {
                head.prev = null;
            }
        } else {
            PoolChunk<T> next = cur.next;
            cur.prev.next = next;
            if (next != null) {
                next.prev = cur.prev;
            }
        }
    }

    @Override
    public int minUsage() {
        return minUsage0(minUsage);
    }

    @Override
    public int maxUsage() {
        return min(maxUsage, 100);
    }

    private static int minUsage0(int value) {
        return max(1, value);
    }

    @Override
    public Iterator<PoolChunkMetric> iterator() {
        synchronized (arena) {
            if (head == null) {
                return EMPTY_METRICS;
            }
            List<PoolChunkMetric> metrics = new ArrayList<PoolChunkMetric>();
            for (PoolChunk<T> cur = head;;) {
                metrics.add(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
            }
            return metrics.iterator();
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        synchronized (arena) {
            if (head == null) {
                return "none";
            }

            for (PoolChunk<T> cur = head;;) {
                buf.append(cur);
                cur = cur.next;
                if (cur == null) {
                    break;
                }
                buf.append(StringUtil.NEWLINE);
            }
        }
        return buf.toString();
    }

    void destroy(PoolArena<T> arena) {
        PoolChunk<T> chunk = head;
        while (chunk != null) {
            arena.destroyChunk(chunk);
            chunk = chunk.next;
        }
        head = null;
    }
}

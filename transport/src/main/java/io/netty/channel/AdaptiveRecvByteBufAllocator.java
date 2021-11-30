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

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    /**
     * 该自适应接收字节缓冲分配器的默认最小值
     */
    static final int DEFAULT_MINIMUM = 64;

    // Use an initial value that is bigger than the common MTU of 1500
    /**
     * 使用大于1500的常见最大传输单元的初始值
     */
    static final int DEFAULT_INITIAL = 2048;

    /**
     * 该自适应接收字节缓冲分配器的默认最大值
     */
    static final int DEFAULT_MAXIMUM = 65536;

    private static final int INDEX_INCREMENT = 4;
    private static final int INDEX_DECREMENT = 1;

    /**
     * 大小表
     */
    private static final int[] SIZE_TABLE;

    static {
        // 实例化大小表，并依次赋值
        List<Integer> sizeTable = new ArrayList<Integer>();
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        // Suppress a warning since i becomes negative when an integer overflow happens
        // 自从当整型溢出发生，i变成负数，取消警告
        for (int i = 512; i > 0; i <<= 1) { // lgtm[java/constant-comparison]
            sizeTable.add(i);
        }

        // 给成员变量大小表赋值
        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    /**
     * 获得入参大小在大小表中的索引
     *
     * @param size 大小值
     * @return 入参大小值在大小表中的索引
     */
    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    /**
     * 处理实现
     */
    private final class HandleImpl extends MaxMessageHandle {
        private final int minIndex;
        private final int maxIndex;

        /**
         * 在大小表中的索引
         */
        private int index;

        private int nextReceiveBufferSize;

        /**
         * 是否现在减少
         */
        private boolean decreaseNow;

        HandleImpl(int minIndex, int maxIndex, int initial) {
            // 设置最小索引和最大索引
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;

            // 计算出初始位置的索引，也即初始容量大小所在大小表中的索引
            index = getSizeTableIndex(initial);
            // 设置下一个接收缓冲大小
            nextReceiveBufferSize = SIZE_TABLE[index];
        }

        @Override
        public void lastBytesRead(int bytes) {
            // If we read as much as we asked for we should check if we need to ramp up the size of our next guess.
            // This helps adjust more quickly when large amounts of data is pending and can avoid going back to
            // the selector to check for more data. Going back to the selector can add significant latency for large
            // data transfers.
            if (bytes == attemptedBytesRead()) {
                record(bytes);
            }
            super.lastBytesRead(bytes);
        }

        @Override
        public int guess() {
            return nextReceiveBufferSize;
        }

        /**
         * 记录实际读取的字节数
         *
         * @param actualReadBytes 实际读取的字节数
         */
        private void record(int actualReadBytes) {
            // 经过一些条件判断，做一些标记
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT)]) {
                // 如果现在减少
                if (decreaseNow) {
                    /*
                        以下不细究
                     */
                    index = max(index - INDEX_DECREMENT, minIndex);
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    // 将当前减少标记修改为真
                    decreaseNow = true;
                }
            } else if (actualReadBytes >= nextReceiveBufferSize) {
                /*
                    以下不细究
                 */
                index = min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }

        @Override
        public void readComplete() {
            // 记录总共读取的字节数
            record(totalBytesRead());
        }
    }

    /**
     * 最小索引
     */
    private final int minIndex;

    /**
     * 最大索引
     */
    private final int maxIndex;

    /**
     * 初始值
     */
    private final int initial;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     *
     * 用默认参数创建一个新的预测器。
     * 用默认参数，期盼的缓冲区大小从1024开始，不会低于64，也不会高于65536。
     */
    public AdaptiveRecvByteBufAllocator() {
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * 用给定参数创建新的预测器。
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        // 检查入参
        checkPositive(minimum, "minimum");
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        // 设置最小索引
        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        // 设置最大索引
        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        // 设置初始容量大小
        this.initial = initial;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Handle newHandle() {
        // 实例化一个处理实现并返回
        return new HandleImpl(minIndex, maxIndex, initial);
    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}

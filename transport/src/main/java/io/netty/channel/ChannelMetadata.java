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

import java.net.SocketAddress;

import static io.netty.util.internal.ObjectUtil.checkPositive;

/**
 * Represents the properties of a {@link Channel} implementation.
 *
 * 代表通道实现的属性。
 */
public final class ChannelMetadata {

    private final boolean hasDisconnect;

    /**
     * 每次读的默认最大消息数
     */
    private final int defaultMaxMessagesPerRead;

    /**
     * Create a new instance
     *
     * @param hasDisconnect     {@code true} if and only if the channel has the {@code disconnect()} operation
     *                          that allows a user to disconnect and then call {@link Channel#connect(SocketAddress)}
     *                          again, such as UDP/IP.
     */
    public ChannelMetadata(boolean hasDisconnect) {
        this(hasDisconnect, 1);
    }

    /**
     * Create a new instance
     *
     * 创建一个新实例
     *
     * @param hasDisconnect     {@code true} if and only if the channel has the {@code disconnect()} operation
     *                          that allows a user to disconnect and then call {@link Channel#connect(SocketAddress)}
     *                          again, such as UDP/IP.
     * @param defaultMaxMessagesPerRead If a {@link MaxMessagesRecvByteBufAllocator} is in use, then this value will be
     * set for {@link MaxMessagesRecvByteBufAllocator#maxMessagesPerRead()}. Must be {@code > 0}.
     */
    public ChannelMetadata(boolean hasDisconnect, int defaultMaxMessagesPerRead) {
        // 检查每次读的默认最大消息数
        checkPositive(defaultMaxMessagesPerRead, "defaultMaxMessagesPerRead");

        // 设置已断开连接和默认每次读的最大消息数
        this.hasDisconnect = hasDisconnect;
        this.defaultMaxMessagesPerRead = defaultMaxMessagesPerRead;
    }

    /**
     * Returns {@code true} if and only if the channel has the {@code disconnect()} operation
     * that allows a user to disconnect and then call {@link Channel#connect(SocketAddress)} again,
     * such as UDP/IP.
     */
    public boolean hasDisconnect() {
        return hasDisconnect;
    }

    /**
     * If a {@link MaxMessagesRecvByteBufAllocator} is in use, then this is the default value for
     * {@link MaxMessagesRecvByteBufAllocator#maxMessagesPerRead()}.
     *
     * 如果最大消息接收字节缓冲分配器处于使用中，这是对于最大消息接收字节缓冲分配器的每次读的最大消息数方法的默认值。
     */
    public int defaultMaxMessagesPerRead() {
        return defaultMaxMessagesPerRead;
    }
}

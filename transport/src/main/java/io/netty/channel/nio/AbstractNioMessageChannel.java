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

import java.io.IOException;
import java.net.PortUnreachableException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on messages.
 *
 * 用于通道来操作消息的抽象非阻塞输入输出通道基类。
 */
public abstract class AbstractNioMessageChannel extends AbstractNioChannel {

    /**
     * 当前nio消息通道的输入流是否已关闭
     */
    boolean inputShutdown;

    /**
     * @see AbstractNioChannel#AbstractNioChannel(Channel, SelectableChannel, int)
     */
    protected AbstractNioMessageChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        super(parent, ch, readInterestOp);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        // 实例化并返回一个nio消息不安全实例
        return new NioMessageUnsafe();
    }

    @Override
    protected void doBeginRead() throws Exception {
        // 如果当前通道的输入流已关闭，则直接返回
        if (inputShutdown) {
            return;
        }
        super.doBeginRead();
    }

    /**
     * 当前nio消息通道是否能持续读
     *
     * @param allocHandle 已分配的处理
     * @return 当前nio消息通道是否能持续读
     */
    protected boolean continueReading(RecvByteBufAllocator.Handle allocHandle) {
        return allocHandle.continueReading();
    }

    /**
     * nio消息不安全实例
     */
    private final class NioMessageUnsafe extends AbstractNioUnsafe {

        /**
         * 读缓冲
         */
        private final List<Object> readBuf = new ArrayList<Object>();

        @Override
        public void read() {
            // 断言，当前线程处于事件循环中
            assert eventLoop().inEventLoop();

            // 通道配置
            final ChannelConfig config = config();
            // 通道流水线
            final ChannelPipeline pipeline = pipeline();
            // 接收字节缓冲分配器的处理者
            final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();

            // 接收字节缓冲分配器处理重新设置
            allocHandle.reset(config);

            boolean closed = false;
            Throwable exception = null;
            try {
                try {
                    do {
                        // 处理读消息，获得读消息数
                        int localRead = doReadMessages(readBuf);

                        // 如果读消息数为0，则直接退出当前循环
                        if (localRead == 0) {
                            break;
                        }

                        // 如果读消息数小于0
                        if (localRead < 0) {
                            /*
                                以下不细究
                             */
                            closed = true;
                            break;
                        }

                        // 增加读消息数
                        allocHandle.incMessagesRead(localRead);

                        // 如果能继续读，则再次循环
                    } while (continueReading(allocHandle));
                } catch (Throwable t) {
                    // 以下不细究
                    exception = t;
                }

                // 获得读缓冲的大小
                int size = readBuf.size();
                for (int i = 0; i < size; i ++) {
                    // 修改读待办标记
                    readPending = false;
                    // 通道流水线触发读通道
                    pipeline.fireChannelRead(readBuf.get(i));
                }
                // 清空读缓冲
                readBuf.clear();
                // 已分配的处理做读完成操作
                allocHandle.readComplete();
                // 通道流水线触发通道读完成方法
                pipeline.fireChannelReadComplete();

                // 如果存在异常
                if (exception != null) {
                    /*
                        以下不细究
                     */
                    closed = closeOnReadError(exception);

                    pipeline.fireExceptionCaught(exception);
                }

                // 如果关闭
                if (closed) {
                    /*
                        以下不细究
                     */
                    inputShutdown = true;
                    if (isOpen()) {
                        close(voidPromise());
                    }
                }
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254

                /*
                    检查是否有读待办至今未被处理。
                    这能是两种原因：
                    用户在channelRead()方法里调用Channel.read()方法或ChannelHandlerContext.read()方法，
                    用户在channelReadComplete()方法里调用Channel().read()或者ChannelHandlerContext.read()方法。
                 */
                // 如果当前通道读待办，并且不是可自动读的
                if (!readPending && !config.isAutoRead()) {
                    // 以下不细究
                    removeReadOp();
                }
            }
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        final SelectionKey key = selectionKey();
        final int interestOps = key.interestOps();

        int maxMessagesPerWrite = maxMessagesPerWrite();
        while (maxMessagesPerWrite > 0) {
            Object msg = in.current();
            if (msg == null) {
                break;
            }
            try {
                boolean done = false;
                for (int i = config().getWriteSpinCount() - 1; i >= 0; i--) {
                    if (doWriteMessage(msg, in)) {
                        done = true;
                        break;
                    }
                }

                if (done) {
                    maxMessagesPerWrite--;
                    in.remove();
                } else {
                    break;
                }
            } catch (Exception e) {
                if (continueOnWriteError()) {
                    maxMessagesPerWrite--;
                    in.remove(e);
                } else {
                    throw e;
                }
            }
        }
        if (in.isEmpty()) {
            // Wrote all messages.
            if ((interestOps & SelectionKey.OP_WRITE) != 0) {
                key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
            }
        } else {
            // Did not write all messages.
            if ((interestOps & SelectionKey.OP_WRITE) == 0) {
                key.interestOps(interestOps | SelectionKey.OP_WRITE);
            }
        }
    }

    /**
     * Returns {@code true} if we should continue the write loop on a write error.
     */
    protected boolean continueOnWriteError() {
        return false;
    }

    protected boolean closeOnReadError(Throwable cause) {
        if (!isActive()) {
            // If the channel is not active anymore for whatever reason we should not try to continue reading.
            return true;
        }
        if (cause instanceof PortUnreachableException) {
            return false;
        }
        if (cause instanceof IOException) {
            // ServerChannel should not be closed even on IOException because it can often continue
            // accepting incoming connections. (e.g. too many open files)
            return !(this instanceof ServerChannel);
        }
        return true;
    }

    /**
     * Read messages into the given array and return the amount which was read.
     *
     * 将消息读进给定数组里，并且返回读取的数量。
     */
    protected abstract int doReadMessages(List<Object> buf) throws Exception;

    /**
     * Write a message to the underlying {@link java.nio.channels.Channel}.
     *
     * @return {@code true} if and only if the message has been written
     */
    protected abstract boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception;
}

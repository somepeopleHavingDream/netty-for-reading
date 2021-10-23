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
package io.netty.channel.nio;

import java.nio.channels.SelectionKey;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

final class SelectedSelectionKeySet extends AbstractSet<SelectionKey> {

    /**
     * 被选择的选择键
     */
    SelectionKey[] keys;

    int size;

    SelectedSelectionKeySet() {
        keys = new SelectionKey[1024];
    }

    @Override
    public boolean add(SelectionKey o) {
        /*
            覆写添加方法，使得对选择键的添加的时间复杂度为o(1)，因为jdk底层的选择键集的实现是hashset，添加操作的时间复杂度不是o(1)
         */

        // 如果选择键为null，则直接返回添加失败
        if (o == null) {
            return false;
        }

        // 赋值
        keys[size++] = o;
        // 如果键数量已满，则做扩容操作
        if (size == keys.length) {
            // 扩容
            increaseCapacity();
        }

        return true;
    }

    @Override
    public boolean remove(Object o) {
        return false;
    }

    @Override
    public boolean contains(Object o) {
        return false;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public Iterator<SelectionKey> iterator() {
        return new Iterator<SelectionKey>() {
            private int idx;

            @Override
            public boolean hasNext() {
                return idx < size;
            }

            @Override
            public SelectionKey next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return keys[idx++];
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    /**
     * 重置
     */
    void reset() {
        // 做重置操作
        reset(0);
    }

    /**
     * 重置
     *
     * @param start 起始偏移位置
     */
    void reset(int start) {
        Arrays.fill(keys, start, size, null);
        size = 0;
    }

    /**
     * 扩容
     */
    private void increaseCapacity() {
        // 扩容为原来的两倍
        SelectionKey[] newKeys = new SelectionKey[keys.length << 1];
        System.arraycopy(keys, 0, newKeys, 0, size);
        keys = newKeys;
    }
}

/*
 * Copyright 2014 The Netty Project
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

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;

import java.lang.reflect.Constructor;

/**
 * A {@link ChannelFactory} that instantiates a new {@link Channel} by invoking its default constructor reflectively.

 * 通过反射地调用它默认的构造器，
 * 来实例新通道的通道工厂。
 */
public class ReflectiveChannelFactory<T extends Channel> implements ChannelFactory<T> {

    /**
     * 此反射通道工厂用于创建通道的构造器
     */
    private final Constructor<? extends T> constructor;

    /**
     * 反射通道工厂的构造方法
     *
     * @param clazz 通道类对象
     */
    public ReflectiveChannelFactory(Class<? extends T> clazz) {
        // 检查类对象不能为null
        ObjectUtil.checkNotNull(clazz, "clazz");

        try {
            // 从类对象中获得并设置构造器
            this.constructor = clazz.getConstructor();
        } catch (NoSuchMethodException e) {
            // 以下不细究
            throw new IllegalArgumentException("Class " + StringUtil.simpleClassName(clazz) +
                    " does not have a public non-arg constructor", e);
        }
    }

    @Override
    public T newChannel() {
        try {
            // 通过构造器实例化一个实例
            return constructor.newInstance();
        } catch (Throwable t) {
            // 不细究
            throw new ChannelException("Unable to create Channel from class " + constructor.getDeclaringClass(), t);
        }
    }

    @Override
    public String toString() {
        return StringUtil.simpleClassName(ReflectiveChannelFactory.class) +
                '(' + StringUtil.simpleClassName(constructor.getDeclaringClass()) + ".class)";
    }
}

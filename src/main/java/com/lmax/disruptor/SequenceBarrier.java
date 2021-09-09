/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;


/**
 * Coordination barrier for tracking the cursor for publishers and sequence of
 * dependent {@link EventProcessor}s for processing a data structure
 * 消费者可用序列屏障
 * 主要作用是协调获取消费者可处理到的最大序号，内部持有着生产者和其依赖的消费者序列
 * SequenceBarrier实例引用被EventProcessor持有，用于等待并获取可用的消费事件，主要体现在waitFor这个方法。
 * 要实现这个功能，需要3点条件：
 *
 * 知道生产者的位置。
 * 因为Disruptor支持消费者链，在不同的消费者组之间，要保证后边的消 费者组只有在前消费者组中的消费者都处理完毕后，才能进行处理。
 * 暂时没有事件可消费，在等待可用消费时，还需要使用某种等待策略进行等待。
 *
 */
public interface SequenceBarrier
{
    /**
     * Wait for the given sequence to be available for consumption.
     *
     * @param sequence to wait for
     * @return the sequence up to which is available
     * @throws AlertException       if a status change has occurred for the Disruptor
     * @throws InterruptedException if the thread needs awaking on a condition variable.
     * @throws TimeoutException     if a timeout occurs while waiting for the supplied sequence.
     *
     * 等待指定序列可用
     */
    long waitFor(long sequence) throws AlertException, InterruptedException, TimeoutException;

    /**
     * Get the current cursor value that can be read.
     *
     * @return value of the cursor for entries that have been published.
     *
     * 获取当前可读游标值
     */
    long getCursor();

    /**
     * The current alert status for the barrier.
     *
     * @return true if in alert otherwise false.
     *
     * 当前的alert状态
     */
    boolean isAlerted();

    /**
     * Alert the {@link EventProcessor}s of a status change and stay in this status until cleared.
     * 通知消费者状态变化。当调用EventProcessor#halt()将调用此方法。
     */
    void alert();

    /**
     * Clear the current alert status.
     * 清楚alert状态
     */
    void clearAlert();

    /**
     * Check if an alert has been raised and throw an {@link AlertException} if it has.
     *
     * @throws AlertException if alert has been raised.
     * 检查是否发生alert，发生将抛出异常
     */
    void checkAlert() throws AlertException;
}

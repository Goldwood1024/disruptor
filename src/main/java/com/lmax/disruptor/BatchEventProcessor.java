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

import java.util.concurrent.atomic.AtomicInteger;


/**
 * Convenience class for handling the batching semantics of consuming entries from a {@link RingBuffer}
 * and delegating the available events to an {@link EventHandler}.
 * <p>
 * If the {@link EventHandler} also implements {@link LifecycleAware} it will be notified just after the thread
 * is started and just before the thread is shutdown.
 *
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 *
 * 每个EventHandler对应一个EventProcessor执行者，BatchEventProcessor每次大循环可以获取最高可用序号，并循环调用EventHandler
 */
public final class BatchEventProcessor<T>
    implements EventProcessor
{
    // 空闲状态
    private static final int IDLE = 0;
    // 暂停状态
    private static final int HALTED = IDLE + 1;
    // 运行状态
    private static final int RUNNING = HALTED + 1;

    // 线程实际运行状态
    private final AtomicInteger running = new AtomicInteger(IDLE);
    // 异常处理器
    private ExceptionHandler<? super T> exceptionHandler;
    // RingBuffer
    private final DataProvider<T> dataProvider;
    // 序列器屏障
    private final SequenceBarrier sequenceBarrier;
    // 事件处理器
    private final EventHandler<? super T> eventHandler;
    // 消费者 sequence
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    // 超时处理器
    private final TimeoutHandler timeoutHandler;
    private final BatchStartAware batchStartAware;

    /**
     * Construct a {@link EventProcessor} that will automatically track the progress by updating its sequence when
     * the {@link EventHandler#onEvent(Object, long, boolean)} method returns.
     *
     * @param dataProvider    to which events are published.
     * @param sequenceBarrier on which it is waiting.
     * @param eventHandler    is the delegate to which events are dispatched.
     */
    public BatchEventProcessor(
        final DataProvider<T> dataProvider,
        final SequenceBarrier sequenceBarrier,
        final EventHandler<? super T> eventHandler)
    {
        // 指定RingBuffer
        this.dataProvider = dataProvider;
        // 屏障
        this.sequenceBarrier = sequenceBarrier;
        // 事件处理器
        this.eventHandler = eventHandler;

        if (eventHandler instanceof SequenceReportingEventHandler)
        {
            ((SequenceReportingEventHandler<?>) eventHandler).setSequenceCallback(sequence);
        }

        batchStartAware =
            (eventHandler instanceof BatchStartAware) ? (BatchStartAware) eventHandler : null;
        timeoutHandler =
            (eventHandler instanceof TimeoutHandler) ? (TimeoutHandler) eventHandler : null;
    }

    @Override
    public Sequence getSequence()
    {
        return sequence;
    }

    @Override
    public void halt()
    {
        // 更新状态为暂停
        running.set(HALTED);
        sequenceBarrier.alert();
    }

    @Override
    public boolean isRunning()
    {
        return running.get() != IDLE;
    }

    /**
     * Set a new {@link ExceptionHandler} for handling exceptions propagated out of the {@link BatchEventProcessor}
     *
     * @param exceptionHandler to replace the existing exceptionHandler.
     *                         设置异常处理器
     */
    public void setExceptionHandler(final ExceptionHandler<? super T> exceptionHandler)
    {
        if (null == exceptionHandler)
        {
            throw new NullPointerException();
        }

        this.exceptionHandler = exceptionHandler;
    }

    /**
     * It is ok to have another thread rerun this method after a halt().
     *
     * @throws IllegalStateException if this object instance is already running in a thread
     * 可以让另一个线程在halt()之后重新运行这个方法
     */
    @Override
    public void run()
    {
        // 更新状态为运行 空闲->运行
        if (running.compareAndSet(IDLE, RUNNING))
        {
            sequenceBarrier.clearAlert();

            // 启动通知
            notifyStart();
            try
            {
                // 判断是否运行状态
                if (running.get() == RUNNING)
                {
                    processEvents();
                }
            }
            finally
            {
                // 停止通知
                notifyShutdown();
                // 更新为空闲
                running.set(IDLE);
            }
        }
        else
        {
            // This is a little bit of guess work.  The running state could of changed to HALTED by
            // this point.  However, Java does not have compareAndExchange which is the only way
            // to get it exactly correct.
            if (running.get() == RUNNING)
            {
                throw new IllegalStateException("Thread is already running");
            }
            else
            {
                earlyExit();
            }
        }
    }

    private void processEvents()
    {
        T event = null;
        // 从当前sequence后移一个进行消费
        long nextSequence = sequence.get() + 1L;

        while (true)
        {
            try
            {
                // availableSequence 返回的是可用的最大值
                final long availableSequence = sequenceBarrier.waitFor(nextSequence);
                if (batchStartAware != null)
                {
                    batchStartAware.onBatchStart(availableSequence - nextSequence + 1);
                }

                // 批量执行待消费的事件
                while (nextSequence <= availableSequence)
                {
                    // 取出事件
                    event = dataProvider.get(nextSequence);
                    // 调用消费者
                    eventHandler.onEvent(event, nextSequence, nextSequence == availableSequence);

                    // 取下一个事件
                    nextSequence++;
                }

                // eventHandler处理完毕后，更新当前序号
                sequence.set(availableSequence);
            }
            catch (final TimeoutException e)
            {
                // 超时通知
                notifyTimeout(sequence.get());
            }
            catch (final AlertException ex)
            {
                if (running.get() != RUNNING)
                {
                    break;
                }
            }
            catch (final Throwable ex)
            {
                // 调用异常处理器，可以抛出异常
                handleEventException(ex, nextSequence, event);

                // 若无新的异常抛出，则更新sequence
                sequence.set(nextSequence);
                // 发生异常事件不在重复消费
                nextSequence++;
            }
        }
    }

    private void earlyExit()
    {
        notifyStart();
        notifyShutdown();
    }

    private void notifyTimeout(final long availableSequence)
    {
        try
        {
            if (timeoutHandler != null)
            {
                // 超时处理器存在则调用
                timeoutHandler.onTimeout(availableSequence);
            }
        }
        catch (Throwable e)
        {
            handleEventException(e, availableSequence, null);
        }
    }

    /**
     * Notifies the EventHandler when this processor is starting up
     * 当处理器启动时通知EventHandler
     */
    private void notifyStart()
    {
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) eventHandler).onStart();
            }
            catch (final Throwable ex)
            {
                handleOnStartException(ex);
            }
        }
    }

    /**
     * Notifies the EventHandler immediately prior to this processor shutting down
     * 在处理器关闭之前立即通知EventHandler
     */
    private void notifyShutdown()
    {
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) eventHandler).onShutdown();
            }
            catch (final Throwable ex)
            {
                handleOnShutdownException(ex);
            }
        }
    }

    /**
     * Delegate to {@link ExceptionHandler#handleEventException(Throwable, long, Object)} on the delegate or
     * the default {@link ExceptionHandler} if one has not been configured.
     * 委托给异常处理器处理， 如果没有配置处理器 则使用默认的异常处理器(FatalExceptionHandler)
     */
    private void handleEventException(final Throwable ex, final long sequence, final T event)
    {
        getExceptionHandler().handleEventException(ex, sequence, event);
    }

    /**
     * Delegate to {@link ExceptionHandler#handleOnStartException(Throwable)} on the delegate or
     * the default {@link ExceptionHandler} if one has not been configured.
     */
    private void handleOnStartException(final Throwable ex)
    {
        getExceptionHandler().handleOnStartException(ex);
    }

    /**
     * Delegate to {@link ExceptionHandler#handleOnShutdownException(Throwable)} on the delegate or
     * the default {@link ExceptionHandler} if one has not been configured.
     */
    private void handleOnShutdownException(final Throwable ex)
    {
        getExceptionHandler().handleOnShutdownException(ex);
    }

    /**
     * 获取异常处理器 没有配置取默认的处理器
     * @return
     */
    private ExceptionHandler<? super T> getExceptionHandler()
    {
        ExceptionHandler<? super T> handler = exceptionHandler;
        if (handler == null)
        {
            return ExceptionHandlers.defaultHandler();
        }
        return handler;
    }
}
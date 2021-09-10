package com.lmax.disruptor.dsl;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

// 线程池
public class BasicExecutor implements Executor
{
    // 工厂接口
    private final ThreadFactory factory;
    // 线程队列 ConcurrentLinkedQueue
    private final Queue<Thread> threads = new ConcurrentLinkedQueue<>();

    public BasicExecutor(ThreadFactory factory)
    {
        this.factory = factory;
    }

    @Override
    public void execute(Runnable command)
    {
        // 创建执行线程
        final Thread thread = factory.newThread(command);
        if (null == thread)
        {
            throw new RuntimeException("Failed to create thread to run: " + command);
        }

        // 启动线程
        thread.start();

        // 加入队列
        threads.add(thread);
    }

    @Override
    public String toString()
    {
        return "BasicExecutor{" +
            "threads=" + dumpThreadInfo() +
            '}';
    }

    /**
     * 线程信息
     * @return
     */
    private String dumpThreadInfo()
    {
        final StringBuilder sb = new StringBuilder();

        final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

        for (Thread t : threads)
        {
            ThreadInfo threadInfo = threadMXBean.getThreadInfo(t.getId());
            sb.append("{");
            sb.append("name=").append(t.getName()).append(",");
            sb.append("id=").append(t.getId()).append(",");
            sb.append("state=").append(threadInfo.getThreadState()).append(",");
            sb.append("lockInfo=").append(threadInfo.getLockInfo());
            sb.append("}");
        }

        return sb.toString();
    }
}

package com.lmax.disruptor;

// 批处理
public interface BatchStartAware
{
    void onBatchStart(long batchSize);
}

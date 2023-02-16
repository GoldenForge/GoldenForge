package com.destroystokyo.paper.utils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

public class CachedSizeConcurrentLinkedQueue<E> extends ConcurrentLinkedQueue<E> {
    private final LongAdder cachedSize = new LongAdder();

    @Override
    public boolean add(@NotNull E e) {
        boolean result = super.add(e);
        if (result) {
            cachedSize.increment();
        }
        return result;
    }

    @Nullable
    @Override
    public E poll() {
        E result = super.poll();
        if (result != null) {
            cachedSize.decrement();
        }
        return result;
    }

    @Override
    public int size() {
        return cachedSize.intValue();
    }
}

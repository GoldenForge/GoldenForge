package ca.spottedleaf.leafprofiler;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

import java.util.Arrays;

public final class LeafProfiler {

    public final LProfilerRegistry registry;
    public final LProfileGraph graph;

    private long[] data;
    private final IntArrayFIFOQueue callStack = new IntArrayFIFOQueue();
    private int topOfStack = LProfileGraph.ROOT_NODE;
    private final LongArrayFIFOQueue timerStack = new LongArrayFIFOQueue();
    private long lastTimerStart = 0L;

    public LeafProfiler(final LProfilerRegistry registry, final LProfileGraph graph) {
        this.registry = registry;
        this.graph = graph;
    }

    private long[] resizeData(final long[] old, final int least) {
        return this.data = Arrays.copyOf(old, Math.max(old.length * 2, least * 2));
    }

    private void incrementDirect(final int nodeId, final long count) {
        final long[] data = this.data;
        if (nodeId >= data.length) {
            this.resizeData(data, nodeId)[nodeId] += count;
        } else {
            data[nodeId] += count;
        }
    }

    public void incrementCounter(final int type, final long count) {
        // this is supposed to be an optimised version of startTimer then stopTimer
        final int node = this.graph.getOrCreateNode(this.topOfStack, type);
        this.incrementDirect(node, count);
    }

    public void startTimer(final int type, final long startTime) {
        final int parentNode = this.topOfStack;
        final int newNode = this.graph.getOrCreateNode(parentNode, type);
        this.callStack.enqueue(parentNode);
        this.topOfStack = newNode;

        this.timerStack.enqueue(this.lastTimerStart);
        this.lastTimerStart = startTime;
    }

    public void stopTimer(final int type, final long endTime) {
        final int currentNode = this.topOfStack;
        this.topOfStack = this.callStack.dequeueLastInt();

        final long lastStart = this.lastTimerStart;
        this.lastTimerStart = this.timerStack.dequeueLastLong();

        this.incrementDirect(currentNode, endTime - lastStart);
    }
}

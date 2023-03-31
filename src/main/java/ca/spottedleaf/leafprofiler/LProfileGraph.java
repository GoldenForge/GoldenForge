package ca.spottedleaf.leafprofiler;

import ca.spottedleaf.concurrentutil.map.SWMRInt2IntHashTable;

import java.util.Arrays;

public final class LProfileGraph {

    public static final int ROOT_NODE = 0;

    // volatile required for correct publishing after resizing
    private volatile SWMRInt2IntHashTable[] nodes = new SWMRInt2IntHashTable[16];
    private int nodeCount;

    public LProfileGraph() {
        this.nodes[ROOT_NODE] = new SWMRInt2IntHashTable();
        this.nodeCount = 1;
    }

    private int createNode(final int parent, final int type) {
        synchronized (this) {
            SWMRInt2IntHashTable[] nodes = this.nodes;

            final SWMRInt2IntHashTable node = nodes[parent];

            final int newNode = this.nodeCount;
            final int prev = node.putIfAbsent(type, newNode);

            if (prev != 0) {
                // already exists
                return prev;
            }

            // insert new node
            ++this.nodeCount;

            if (newNode >= nodes.length) {
                this.nodes = nodes = Arrays.copyOf(nodes, nodes.length * 2);
            }

            nodes[newNode] = new SWMRInt2IntHashTable();

            return newNode;
        }
    }

    public int getOrCreateNode(final int parent, final int type) {
        // note: requires parent node to exist
        final SWMRInt2IntHashTable[] nodes = this.nodes;

        final int mapping = nodes[parent].get(type);

        if (mapping != 0) {
            return mapping;
        }

        return this.createNode(parent, type);
    }
}

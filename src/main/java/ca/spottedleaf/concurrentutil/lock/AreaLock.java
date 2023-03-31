package ca.spottedleaf.concurrentutil.lock;

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

public final class AreaLock {

    private final int coordinateShift;

    private final Long2ReferenceOpenHashMap<Node> nodesByPosition = new Long2ReferenceOpenHashMap<>(1024, 0.10f);

    public AreaLock(final int coordinateShift) {
        this.coordinateShift = coordinateShift;
    }

    private static long key(final int x, final int z) {
        return ((long)z << 32) | (x & 0xFFFFFFFFL);
    }

    public Node lock(final int x, final int z, final int radius) {
        final Thread thread = Thread.currentThread();
        final int minX = (x - radius) >> this.coordinateShift;
        final int minZ = (z - radius) >> this.coordinateShift;
        final int maxX = (x + radius) >> this.coordinateShift;
        final int maxZ = (z + radius) >> this.coordinateShift;

        final Node node = new Node(x, z, radius, thread);

        synchronized (this) {
            ReferenceOpenHashSet<Node> parents = null;
            for (int currZ = minZ; currZ <= maxZ; ++currZ) {
                for (int currX = minX; currX <= maxX; ++currX) {
                    final Node dependency = this.nodesByPosition.put(key(currX, currZ), node);
                    if (dependency == null) {
                        continue;
                    }

                    if (parents == null) {
                        parents = new ReferenceOpenHashSet<>();
                    }

                    if (parents.add(dependency)) {
                        // added a dependency, so we need to add as a child to the dependency
                        if (dependency.children == null) {
                            dependency.children = new ArrayList<>();
                        }
                        dependency.children.add(node);
                    }
                }
            }

            if (parents == null) {
                // no dependencies, so we can just return immediately
                return node;
            } // else: we need to lock

            node.parents = parents;
        }

        while (!node.unlocked) {
            LockSupport.park(node);
        }

        return node;
    }

    public void unlock(final Node node) {
        List<Node> toUnpark = null;

        final int x = node.x;
        final int z = node.z;
        final int radius = node.radius;

        final int minX = (x - radius) >> this.coordinateShift;
        final int minZ = (z - radius) >> this.coordinateShift;
        final int maxX = (x + radius) >> this.coordinateShift;
        final int maxZ = (z + radius) >> this.coordinateShift;

        synchronized (this) {
            final List<Node> children = node.children;
            if (children != null) {
                // try to unlock children
                for (int i = 0, len = children.size(); i < len; ++i) {
                    final Node child = children.get(i);
                    if (!child.parents.remove(node)) {
                        throw new IllegalStateException();
                    }
                    if (child.parents.isEmpty()) {
                        // we can unlock, as it now has no dependencies in front
                        child.parents = null;
                        if (toUnpark == null) {
                            toUnpark = new ArrayList<>();
                            toUnpark.add(child);
                        } else {
                            toUnpark.add(child);
                        }
                    }
                }
            }

            // remove node from dependency map
            for (int currZ = minZ; currZ <= maxZ; ++currZ) {
                for (int currX = minX; currX <= maxX; ++currX) {
                    // node: we only remove if we match, as a mismatch indicates a child node which of course has not
                    // yet been unlocked
                    this.nodesByPosition.remove(key(currX, currZ), node);
                }
            }
        }

        if (toUnpark == null) {
            return;
        }

        // we move the unpark / unlock logic here because we want to avoid performing work while holding the lock

        for (int i = 0, len = toUnpark.size(); i < len; ++i) {
            final Node toUnlock = toUnpark.get(i);
            toUnlock.unlocked = true; // must be volatile and before unpark()
            LockSupport.unpark(toUnlock.thread);
        }
    }

    public static final class Node {

        public final int x;
        public final int z;
        public final int radius;
        public final Thread thread;

        private List<Node> children;
        private ReferenceOpenHashSet<Node> parents;

        private volatile boolean unlocked;

        public Node(final int x, final int z, final int radius, final Thread thread) {
            this.x = x;
            this.z = z;
            this.radius = radius;
            this.thread = thread;
        }
    }
}

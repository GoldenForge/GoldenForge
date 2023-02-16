package io.papermc.paper.util.maplist;

import it.unimi.dsi.fastutil.objects.Reference2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import net.minecraft.server.MinecraftServer;

import java.util.Arrays;
import java.util.NoSuchElementException;

public final class IteratorSafeOrderedReferenceSet<E> {

    public static final int ITERATOR_FLAG_SEE_ADDITIONS = 1 << 0;

    protected final Reference2IntLinkedOpenHashMap<E> indexMap;
    protected int firstInvalidIndex = -1;

    /* list impl */
    protected E[] listElements;
    protected int listSize;

    protected final double maxFragFactor;

    protected int iteratorCount;

    private final boolean threadRestricted;

    public IteratorSafeOrderedReferenceSet() {
        this(16, 0.75f, 16, 0.2);
    }

    public IteratorSafeOrderedReferenceSet(final boolean threadRestricted) {
        this(16, 0.75f, 16, 0.2, threadRestricted);
    }

    public IteratorSafeOrderedReferenceSet(final int setCapacity, final float setLoadFactor, final int arrayCapacity,
                                           final double maxFragFactor) {
        this(setCapacity, setLoadFactor, arrayCapacity, maxFragFactor, false);
    }
    public IteratorSafeOrderedReferenceSet(final int setCapacity, final float setLoadFactor, final int arrayCapacity,
                                           final double maxFragFactor, final boolean threadRestricted) {
        this.indexMap = new Reference2IntLinkedOpenHashMap<>(setCapacity, setLoadFactor);
        this.indexMap.defaultReturnValue(-1);
        this.maxFragFactor = maxFragFactor;
        this.listElements = (E[])new Object[arrayCapacity];
        this.threadRestricted = threadRestricted;
    }

    /*
    public void check() {
        int iterated = 0;
        ReferenceOpenHashSet<E> check = new ReferenceOpenHashSet<>();
        if (this.listElements != null) {
            for (int i = 0; i < this.listSize; ++i) {
                Object obj = this.listElements[i];
                if (obj != null) {
                    iterated++;
                    if (!check.add((E)obj)) {
                        throw new IllegalStateException("contains duplicate");
                    }
                    if (!this.contains((E)obj)) {
                        throw new IllegalStateException("desync");
                    }
                }
            }
        }

        if (iterated != this.size()) {
            throw new IllegalStateException("Size is mismatched! Got " + iterated + ", expected " + this.size());
        }

        check.clear();
        iterated = 0;
        for (final java.util.Iterator<E> iterator = this.unsafeIterator(IteratorSafeOrderedReferenceSet.ITERATOR_FLAG_SEE_ADDITIONS); iterator.hasNext();) {
            final E element = iterator.next();
            iterated++;
            if (!check.add(element)) {
                throw new IllegalStateException("contains duplicate (iterator is wrong)");
            }
            if (!this.contains(element)) {
                throw new IllegalStateException("desync (iterator is wrong)");
            }
        }

        if (iterated != this.size()) {
            throw new IllegalStateException("Size is mismatched! (iterator is wrong) Got " + iterated + ", expected " + this.size());
        }
    }
    */

    protected final boolean allowSafeIteration() {
        return !this.threadRestricted || MinecraftServer.getServer().isSameThread();
    }

    protected final double getFragFactor() {
        return 1.0 - ((double)this.indexMap.size() / (double)this.listSize);
    }

    public int createRawIterator() {
        if (this.allowSafeIteration()) {
            ++this.iteratorCount;
        }
        if (this.indexMap.isEmpty()) {
            return -1;
        } else {
            return this.firstInvalidIndex == 0 ? this.indexMap.getInt(this.indexMap.firstKey()) : 0;
        }
    }

    public int advanceRawIterator(final int index) {
        final E[] elements = this.listElements;
        int ret = index + 1;
        for (int len = this.listSize; ret < len; ++ret) {
            if (elements[ret] != null) {
                return ret;
            }
        }

        return -1;
    }

    public void finishRawIterator() {
        if (this.allowSafeIteration() && --this.iteratorCount == 0) {
            if (this.getFragFactor() >= this.maxFragFactor) {
                this.defrag();
            }
        }
    }

    public boolean remove(final E element) {
        final int index = this.indexMap.removeInt(element);
        if (index >= 0) {
            if (this.firstInvalidIndex < 0 || index < this.firstInvalidIndex) {
                this.firstInvalidIndex = index;
            }
            if (this.listElements[index] != element) {
                throw new IllegalStateException();
            }
            this.listElements[index] = null;
            if (this.allowSafeIteration() && this.iteratorCount == 0 && this.getFragFactor() >= this.maxFragFactor) {
                this.defrag();
            }
            //this.check();
            return true;
        }
        return false;
    }

    public boolean contains(final E element) {
        return this.indexMap.containsKey(element);
    }

    public boolean add(final E element) {
        final int listSize = this.listSize;

        final int previous = this.indexMap.putIfAbsent(element, listSize);
        if (previous != -1) {
            return false;
        }

        if (listSize >= this.listElements.length) {
            this.listElements = Arrays.copyOf(this.listElements, listSize * 2);
        }
        this.listElements[listSize] = element;
        this.listSize = listSize + 1;

        //this.check();
        return true;
    }

    protected void defrag() {
        if (this.firstInvalidIndex < 0) {
            return; // nothing to do
        }

        if (this.indexMap.isEmpty()) {
            Arrays.fill(this.listElements, 0, this.listSize, null);
            this.listSize = 0;
            this.firstInvalidIndex = -1;
            //this.check();
            return;
        }

        final E[] backingArray = this.listElements;

        int lastValidIndex;
        java.util.Iterator<Reference2IntMap.Entry<E>> iterator;

        if (this.firstInvalidIndex == 0) {
            iterator = this.indexMap.reference2IntEntrySet().fastIterator();
            lastValidIndex = 0;
        } else {
            lastValidIndex = this.firstInvalidIndex;
            final E key = backingArray[lastValidIndex - 1];
            iterator = this.indexMap.reference2IntEntrySet().fastIterator(new Reference2IntMap.Entry<E>() {
                @Override
                public int getIntValue() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int setValue(int i) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public E getKey() {
                    return key;
                }
            });
        }

        while (iterator.hasNext()) {
            final Reference2IntMap.Entry<E> entry = iterator.next();

            final int newIndex = lastValidIndex++;
            backingArray[newIndex] = entry.getKey();
            entry.setValue(newIndex);
        }

        // cleanup end
        Arrays.fill(backingArray, lastValidIndex, this.listSize, null);
        this.listSize = lastValidIndex;
        this.firstInvalidIndex = -1;
        //this.check();
    }

    public E rawGet(final int index) {
        return this.listElements[index];
    }

    public int size() {
        // always returns the correct amount - listSize can be different
        return this.indexMap.size();
    }

    public Iterator<E> iterator() {
        return this.iterator(0);
    }

    public Iterator<E> iterator(final int flags) {
        if (this.allowSafeIteration()) {
            ++this.iteratorCount;
        }
        return new BaseIterator<>(this, true, (flags & ITERATOR_FLAG_SEE_ADDITIONS) != 0 ? Integer.MAX_VALUE : this.listSize);
    }

    public java.util.Iterator<E> unsafeIterator() {
        return this.unsafeIterator(0);
    }
    public java.util.Iterator<E> unsafeIterator(final int flags) {
        return new BaseIterator<>(this, false, (flags & ITERATOR_FLAG_SEE_ADDITIONS) != 0 ? Integer.MAX_VALUE : this.listSize);
    }

    public static interface Iterator<E> extends java.util.Iterator<E> {

        public void finishedIterating();

    }

    protected static final class BaseIterator<E> implements Iterator<E> {

        protected final IteratorSafeOrderedReferenceSet<E> set;
        protected final boolean canFinish;
        protected final int maxIndex;
        protected int nextIndex;
        protected E pendingValue;
        protected boolean finished;
        protected E lastReturned;

        protected BaseIterator(final IteratorSafeOrderedReferenceSet<E> set, final boolean canFinish, final int maxIndex) {
            this.set = set;
            this.canFinish = canFinish;
            this.maxIndex = maxIndex;
        }

        @Override
        public boolean hasNext() {
            if (this.finished) {
                return false;
            }
            if (this.pendingValue != null) {
                return true;
            }

            final E[] elements = this.set.listElements;
            int index, len;
            for (index = this.nextIndex, len = Math.min(this.maxIndex, this.set.listSize); index < len; ++index) {
                final E element = elements[index];
                if (element != null) {
                    this.pendingValue = element;
                    this.nextIndex = index + 1;
                    return true;
                }
            }

            this.nextIndex = index;
            return false;
        }

        @Override
        public E next() {
            if (!this.hasNext()) {
                throw new NoSuchElementException();
            }
            final E ret = this.pendingValue;

            this.pendingValue = null;
            this.lastReturned = ret;

            return ret;
        }

        @Override
        public void remove() {
            final E lastReturned = this.lastReturned;
            if (lastReturned == null) {
                throw new IllegalStateException();
            }
            this.lastReturned = null;
            this.set.remove(lastReturned);
        }

        @Override
        public void finishedIterating() {
            if (this.finished || !this.canFinish) {
                throw new IllegalStateException();
            }
            this.lastReturned = null;
            this.finished = true;
            if (this.set.allowSafeIteration()) {
                this.set.finishRawIterator();
            }
        }
    }
}

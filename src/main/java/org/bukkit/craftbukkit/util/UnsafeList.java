package org.bukkit.craftbukkit.util;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.*;

// implementation of an ArrayList that offers a getter without range checks
@SuppressWarnings("unchecked")
public class UnsafeList<E> extends AbstractList<E> implements List<E>, RandomAccess, Cloneable, Serializable {
    private static final long serialVersionUID = 8683452581112892191L;

    private transient Object[] data;
    private int size;
    private int initialCapacity;

    private Iterator[] iterPool = new Iterator[1];
    private int maxPool;
    private int poolCounter;

    public UnsafeList(int capacity, int maxIterPool) {
        super();
        if (capacity < 0) capacity = 32;
        int rounded = Integer.highestOneBit(capacity - 1) << 1;
        this.data = new Object[rounded];
        this.initialCapacity = rounded;
        this.maxPool = maxIterPool;
        this.iterPool[0] = new Itr();
    }

    public UnsafeList(int capacity) {
        this(capacity, 5);
    }

    public UnsafeList() {
        this(32);
    }

    @Override
    public E get(int index) {
        this.rangeCheck(index);

        return (E) this.data[index];
    }

    public E unsafeGet(int index) {
        return (E) this.data[index];
    }

    @Override
    public E set(int index, E element) {
        this.rangeCheck(index);

        E old = (E) this.data[index];
        this.data[index] = element;
        return old;
    }

    @Override
    public boolean add(E element) {
        this.growIfNeeded();
        this.data[this.size++] = element;
        return true;
    }

    @Override
    public void add(int index, E element) {
        this.growIfNeeded();
        System.arraycopy(data, index, data, index + 1, this.size - index);
        this.data[index] = element;
        this.size++;
    }

    @Override
    public E remove(int index) {
        this.rangeCheck(index);

        E old = (E) this.data[index];
        int movedCount = this.size - index - 1;
        if (movedCount > 0) {
            System.arraycopy(data, index + 1, data, index, movedCount);
        }
        this.data[--this.size] = null;

        return old;
    }

    @Override
    public boolean remove(Object o) {
        int index = this.indexOf(o);
        if (index >= 0) {
            this.remove(index);
            return true;
        }

        return false;
    }

    @Override
    public int indexOf(Object o) {
        for (int i = 0; i < this.size; i++) {
            if (o == this.data[i] || o.equals(this.data[i])) {
                return i;
            }
        }

        return -1;
    }

    @Override
    public boolean contains(Object o) {
        return this.indexOf(o) >= 0;
    }

    // Paper start
    protected transient int maxSize;
    public void setSize(int size) {
        if (this.maxSize < this.size) {
            this.maxSize = this.size;
        }
        this.size = size;
    }

    public void completeReset() {
        if (this.data != null) {
            Arrays.fill(this.data, 0, Math.max(this.size, this.maxSize), null);
        }
        this.size = 0;
        this.maxSize = 0;
        if (this.iterPool != null) {
            for (Iterator temp : this.iterPool) {
                if (temp == null) {
                    continue;
                }
                ((Itr)temp).valid = false;
            }
        }
    }
    // Paper end

    @Override
    public void clear() {
        // Create new array to reset memory usage to initial capacity
        this.size = 0;

        // If array has grown too large create new one, otherwise just clear it
        if (data.length > this.initialCapacity << 3) {
            this.data = new Object[this.initialCapacity];
        } else {
            for (int i = 0; i < data.length; i++) {
                this.data[i] = null;
            }
        }
    }

    // actually rounds up to nearest power of two
    public void trimToSize() {
        int old = data.length;
        int rounded = Integer.highestOneBit(this.size - 1) << 1;
        if (rounded < old) {
            this.data = Arrays.copyOf(data, rounded);
        }
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    public boolean isEmpty() {
        return this.size == 0;
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        UnsafeList<E> copy = (UnsafeList<E>) super.clone();
        copy.data = Arrays.copyOf(data, size);
        copy.size = this.size;
        copy.initialCapacity = this.initialCapacity;
        copy.iterPool = new Iterator[1];
        copy.iterPool[0] = new Itr();
        copy.maxPool = this.maxPool;
        copy.poolCounter = 0;
        return copy;
    }

    @Override
    public Iterator<E> iterator() {
        // Try to find an iterator that isn't in use
        for (Iterator iter : this.iterPool) {
            if (!((Itr) iter).valid) {
                Itr iterator = (Itr) iter;
                iterator.reset();
                return iterator;
            }
        }

        // Couldn't find one, see if we can grow our pool size
        if (iterPool.length < this.maxPool) {
            Iterator[] newPool = new Iterator[iterPool.length + 1];
            System.arraycopy(iterPool, 0, newPool, 0, iterPool.length);
            this.iterPool = newPool;

            this.iterPool[iterPool.length - 1] = new Itr();
            return this.iterPool[iterPool.length - 1];
        }

        // Still couldn't find a free one, round robin replace one with a new iterator
        // This is done in the hope that the new one finishes so can be reused
        this.poolCounter = ++this.poolCounter % iterPool.length;
        this.iterPool[this.poolCounter] = new Itr();
        return this.iterPool[this.poolCounter];
    }

    private void rangeCheck(int index) {
        if (index >= this.size || index < 0) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + this.size);
        }
    }

    private void growIfNeeded() {
        if (this.size == data.length) {
            Object[] newData = new Object[data.length << 1];
            System.arraycopy(data, 0, newData, 0, size);
            this.data = newData;
        }
    }

    private void writeObject(ObjectOutputStream os) throws IOException {
        os.defaultWriteObject();

        os.writeInt(size);
        os.writeInt(initialCapacity);
        for (int i = 0; i < this.size; i++) {
            os.writeObject(this.data[i]);
        }
        os.writeInt(maxPool);
    }

    private void readObject(ObjectInputStream is) throws IOException, ClassNotFoundException {
        is.defaultReadObject();

        this.size = is.readInt();
        this.initialCapacity = is.readInt();
        this.data = new Object[Integer.highestOneBit(this.size - 1) << 1];
        for (int i = 0; i < this.size; i++) {
            this.data[i] = is.readObject();
        }
        this.maxPool = is.readInt();
        this.iterPool = new Iterator[1];
        this.iterPool[0] = new Itr();
    }

    public class Itr implements Iterator<E> {
        int index;
        int lastRet = -1;
        int expectedModCount = modCount;
        public boolean valid = true;

        public void reset() {
            this.index = 0;
            this.lastRet = -1;
            this.expectedModCount = modCount;
            this.valid = true;
        }

        @Override
        public boolean hasNext() {
            this.valid = this.index != UnsafeList.this.size;
            return this.valid;
        }

        @Override
        public E next() {
            if (modCount != this.expectedModCount) {
                throw new ConcurrentModificationException();
            }

            int i = this.index;
            if (i >= UnsafeList.this.size) {
                throw new NoSuchElementException();
            }

            if (i >= data.length) {
                throw new ConcurrentModificationException();
            }

            this.index = i + 1;
            return (E) UnsafeList.this.data[this.lastRet = i];
        }

        @Override
        public void remove() {
            if (this.lastRet < 0) {
                throw new IllegalStateException();
            }

            if (modCount != this.expectedModCount) {
                throw new ConcurrentModificationException();
            }

            try {
                UnsafeList.this.remove(lastRet);
                this.index = this.lastRet;
                this.lastRet = -1;
                this.expectedModCount = modCount;
            } catch (IndexOutOfBoundsException ex) {
                throw new ConcurrentModificationException();
            }
        }
    }
}

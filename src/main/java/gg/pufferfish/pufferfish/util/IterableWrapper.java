package gg.pufferfish.pufferfish.util;

import java.util.*;

public class IterableWrapper<T> implements Iterable<T> {

	private final java.util.Iterator<T> iterator;

	public IterableWrapper(java.util.Iterator<T> iterator) {
		this.iterator = iterator;
	}

	@org.jetbrains.annotations.NotNull
	@Override
	public Iterator<T> iterator() {
		return iterator;
	}

}

package org.signal.paging;

import androidx.annotation.NonNull;
import androidx.core.util.Pools;

import java.util.BitSet;

/**
 * Keeps track of what data is empty vs filled with an emphasis on doing so in a space-efficient way.
 */
class DataStatus {

  private static final Pools.Pool<BitSet> POOL = new Pools.SynchronizedPool<>(1);

  private final BitSet state;

  private int size;

  public static DataStatus obtain(int size) {
    BitSet bitset = POOL.acquire();
    if (bitset == null) {
      bitset = new BitSet(size);
    } else {
      bitset.clear();
    }

    return new DataStatus(size, bitset);
  }

  private DataStatus(int size, @NonNull BitSet bitset) {
    this.size  = size;
    this.state = bitset;
  }

  void mark(int position) {
    state.set(position, true);
  }

  void markRange(int startInclusive, int endExclusive) {
    state.set(startInclusive, endExclusive, true);
  }

  int getEarliestUnmarkedIndexInRange(int startInclusive, int endExclusive) {
    for (int i = startInclusive; i < endExclusive; i++) {
      if (!state.get(i)) {
        return i;
      }
    }
    return -1;
  }

  int getLatestUnmarkedIndexInRange(int startInclusive, int endExclusive) {
    for (int i = endExclusive - 1; i >= startInclusive; i--) {
      if (!state.get(i)) {
        return i;
      }
    }
    return -1;
  }

  boolean get(int position) {
    return state.get(position);
  }

  void insertState(int position, boolean value) {
    if (position < 0 || position > size + 1) {
      throw new IndexOutOfBoundsException();
    }

    for (int i = size; i > position; i--) {
      state.set(i, state.get(i - 1));
    }

    state.set(position, value);

    this.size = size + 1;
  }

  int size() {
    return size;
  }

  void recycle() {
    POOL.release(state);
  }
}

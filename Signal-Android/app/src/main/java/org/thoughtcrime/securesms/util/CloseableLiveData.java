package org.thoughtcrime.securesms.util;

import androidx.lifecycle.MutableLiveData;

import org.signal.core.util.StreamUtil;

import java.io.Closeable;

/**
 * Implementation of {@link androidx.lifecycle.LiveData} that will handle closing the contained
 * {@link Closeable} when the value changes.
 */
public class CloseableLiveData<E extends Closeable> extends MutableLiveData<E> {

  @Override
  public void setValue(E value) {
    setValue(value, true);
  }

  public void setValue(E value, boolean closePrevious) {
    E previous = getValue();

    if (previous != null && closePrevious) {
      StreamUtil.close(previous);
    }

    super.setValue(value);
  }

  public void close() {
    E value = getValue();

    if (value != null) {
      StreamUtil.close(value);
    }
  }
}

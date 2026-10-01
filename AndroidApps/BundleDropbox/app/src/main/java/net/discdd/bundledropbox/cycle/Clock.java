package net.discdd.bundledropbox.cycle;

/**
 * Monotonic time source for the role cycle, injected so the controller can be tested with a fake clock.
 *
 * <p>On Android use {@code SystemClock::elapsedRealtime} (keeps counting in deep sleep and is immune to
 * wall-clock changes). Values are only meaningful relative to each other; never compare them with
 * {@code System.currentTimeMillis()}. The UI converts deadlines with {@code elapsedRealtime()}.
 */
public interface Clock {
    /** @return monotonic milliseconds since an arbitrary fixed origin. */
    long nowMillis();
}

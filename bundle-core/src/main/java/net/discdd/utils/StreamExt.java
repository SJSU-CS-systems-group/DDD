package net.discdd.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.Iterator;

// Stream methods missing for Android 13 and below
public class StreamExt {
    public static <T> Stream<T> takeWhile(Stream<T> stream, Predicate<? super T> predicate) {
        Iterator<T> iterator = stream.iterator();
        List<T> added = new ArrayList<>();

        while (iterator.hasNext()) {
            T next = iterator.next();
            if (!predicate.test(next)) {
                break;
            }
            added.add(next);
        }

        return added.stream();
    }
}
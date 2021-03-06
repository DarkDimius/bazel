// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.concurrent;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A map of atomic long counters. A key whose counter's value is currently zero is _not_
 * automatically removed from the map; use {@link #clear} to clear the entire map.
 *
 * <p>This is very similar to Guava's AtomicLongMap, but optimized for the case where keys are hot,
 * e.g. a high number of concurrent calls to {@code map.incrementAndGet(k)} and/or
 * {@code map.decrementAndGet(k)}, for the same key {@code k)}. Guava's AtomicLongMap uses
 * ConcurrentHashMap#compute, whose implementation unfortunately has internal synchronization even
 * when there's already an internal entry for the key in question.
 */
@ThreadSafe
public class FastHotKeyAtomicLongMap<T> {
  private final ConcurrentMap<T, AtomicLong> map;

  public static <T> FastHotKeyAtomicLongMap<T> create() {
    return new FastHotKeyAtomicLongMap<>(new MapMaker());
  }

  public static <T> FastHotKeyAtomicLongMap<T> create(int concurrencyLevel) {
    return new FastHotKeyAtomicLongMap<>(new MapMaker().concurrencyLevel(concurrencyLevel));
  }

  private FastHotKeyAtomicLongMap(MapMaker mapMaker) {
    this.map = mapMaker.makeMap();
  }

  public long incrementAndGet(T key) {
    return getOrInsertCounter(key).incrementAndGet();
  }

  public long decrementAndGet(T key) {
    return getOrInsertCounter(key).decrementAndGet();
  }

  public ImmutableMap<T, Long> asImmutableMap() {
    return ImmutableMap.copyOf(Maps.transformValues(map, AtomicLong::get));
  }

  public void clear() {
    map.clear();
  }

  private AtomicLong getOrInsertCounter(T element) {
    // Optimize for the case where 'element' is already in our map. See the class javadoc.
    AtomicLong counter = map.get(element);
    return counter != null ? counter : map.computeIfAbsent(element, s -> new AtomicLong(0));
  }
}

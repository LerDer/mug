/*****************************************************************************
 * ------------------------------------------------------------------------- *
 * Licensed under the Apache License, Version 2.0 (the "License");           *
 * you may not use this file except in compliance with the License.          *
 * You may obtain a copy of the License at                                   *
 *                                                                           *
 * http://www.apache.org/licenses/LICENSE-2.0                                *
 *                                                                           *
 * Unless required by applicable law or agreed to in writing, software       *
 * distributed under the License is distributed on an "AS IS" BASIS,         *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  *
 * See the License for the specific language governing permissions and       *
 * limitations under the License.                                            *
 *****************************************************************************/
package com.google.mu.util.stream;

import java.util.function.Function;
import java.util.stream.Collector;

/**
 * A collector passed to {@link BiStream#collect}. For example:  <pre>{@code
 *   BiCollector<String, Integer, Map<String, Integer>> collector = Collectors::toMap;
 *   Map<String, Integer> map = BiStream.of("a", 1).collect(collector);
 * }</pre>
 *
 * @param <K> the key type
 * @param <V> the value type
 * @param <R> the result type
 * @since 1.2
 */
@FunctionalInterface
public interface BiCollector<K, V, R> {
  /**
   * Adapts to a {@code Collector<T, ? R>}, by mapping the inputs through {@code keyMapper}
   * and {@code valueMapper} and subsequently collecting the key-value pairs with this
   * {@code BiCollector}.
   */
  // Deliberately avoid wildcards for keyMapper and valueMapper, because we don't expect
  // users to call this method. Instead, users will typically provide lambda or
  // method references matching this signature.
  // Signatures with or without wildcards should both match.
  // In other words, this signature optimizes flexibility for implementors, not callers.
  <T> Collector<T, ?, ? extends R> asCollector(
      Function<T, K> keyMapper, Function<T, V> valueMapper);
}

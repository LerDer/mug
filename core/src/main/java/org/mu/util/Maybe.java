/*****************************************************************************
 * Copyright (C) mu.org                                                *
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
package org.mu.util;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.mu.function.CheckedBiFunction;
import org.mu.function.CheckedFunction;
import org.mu.function.CheckedSupplier;

/**
 * Class that wraps checked exceptions and tunnel them through stream operations.
 * 
 * <p>The idea is to wrap checked exceptions inside Stream<Maybe<T, E>>, then map(), flatMap(),
 * filter() away through normal stream operations, and only unwrap/collect at the end. Exception is
 * only thrown at the "collecting" step. For example:
 *
 * <pre>{@code
 *   static List<byte[]> readFiles(Collection<File> files) throws IOException {
 *     Stream<Maybe<byte[], IOException>> stream = files.stream()
 *         .map(Maybe.wrap(Files::toByteArray));
 *     return Maybe.collect(stream);  // IOException throws here.
 *   }
 * }</pre>
 *
 * A longer stream chain example:
 *
 * <pre>{@code
 *   private Job fetchJob(long jobId) throws IOException;
 *   
 *   List<Job> getPendingJobs() throws IOException {
 *     Stream<Maybe<Job, IOException>> stream = pendingJobIds.stream()
 *         .map(Maybe.wrap(this::fetchJob))
 *         .filter(Maybe.byValue(Job::isPending));
 *     return Maybe.collect(stream);
 *   }
 * }</pre>
 *
 * To log and swallow exceptions:
 *
 * <pre>{@code
 *   private <T> Stream<T> logAndSwallow(Maybe<T> maybe) {
 *     return maybe.catching(logger::warn);
 *   }
 *
 *   List<String> getPendingJobNames() {
 *     return pendingJobIds.stream()
 *         .map(Maybe.wrap(this::fetchJob))
 *         .flatMap(this::logAndSwallow)
 *         .filter(Job::isPending)
 *         .map(Job::getName)
 *         .collect(Collectors.toList());
 *   }
 * }</pre>
 */
public abstract class Maybe<T, E extends Throwable> {

  /**
   * Creates a {@code Maybe} for {@code value}.
   *
   * @param value can be null
   */
  public static <T, E extends Throwable> Maybe<T, E> of(T value) {
    return new Success<>(value);
  }

  /** Creates an exceptional {@code Maybe} for {@code exception}. */
  public static <T, E extends Throwable> Maybe<T, E> except(E exception) {
    return new Failure<>(exception);
  }

  /**
   * Maps {@code this} using {@code function} unless it wraps exception.
   */
  public abstract <T2> Maybe<T2, E> map(Function<? super T, ? extends T2> function);

  /**
   * Flat maps {@code this} using {@code f} unless it wraps exception.
   */
  public abstract <T2> Maybe<T2, E> flatMap(Function<? super T, Maybe<T2, E>> function);

  /** Returns the encapsulated value or throw exception. */
  public abstract T get() throws E;

  /** Returns true unless this is exceptional. */
  public abstract boolean isPresent();

  /** Applies {@code consumer} if {@code this} is present. */
  public abstract void ifPresent(Consumer<? super T> consumer);

  /** Either returns the encapsulated value, or translates exception using {@code function}. */
  public abstract T orElse(Function<? super E, ? extends T> function);

  /**
   * Catches and handles exception with {@code handler}, and then skips it in the returned
   * {@code Stream}. This is specially useful in a {@link Stream} chain to handle and then ignore
   * exceptional results.
   */
  public final Stream<T> catching(Consumer<? super E> handler) {
    requireNonNull(handler);
    return map(Stream::of).orElse(e -> {
      handler.accept(e);
      return Stream.empty();
    });
  }

  /** Unwraps a stream of Maybe and throws exception if any represents exception. */
  public static <T, E extends Throwable> List<T> collect(Stream<Maybe<T, E>> stream) throws E {
    return collect(stream, Collectors.toList());
  }

  /** Unwraps a stream of Maybe and throws exception if any represents exception. */
  public static <T, E extends Throwable, A, R> R collect(
      Stream<Maybe<T, E>> stream, Collector<? super T, A, R> collector) throws E {
    A container = collector.supplier().get();
    Iterable<Maybe<T, E>> iterable = stream::iterator;
    for (Maybe<T, E> maybe : iterable) {
      collector.accumulator().accept(container, maybe.get());
    }
    return collector.finisher().apply(container);
  }

  /**
   * Turns {@code condition} to a {@code Predicate} over {@code Maybe}. The returned predicate
   * matches any {@code Maybe} with a matching value, as well as any exceptional {@code Maybe} so
   * as not to accidentally swallow exceptions.
   */
  public static <T, E extends Throwable> Predicate<Maybe<T, E>> byValue(Predicate<T> condition) {
    requireNonNull(condition);
    return maybe -> maybe.map(condition::test).orElse(e -> true);
  }

  /**
   * Wraps {@code supplier} to be used for a stream of Maybe.
   *
   * <p>Unchecked exceptions will be immediately propagated without being wrapped.
   */
  public static <T, E extends Throwable> Supplier<Maybe<T, E>> wrap(
      CheckedSupplier<T, E> supplier) {
    requireNonNull(supplier);
    return () -> getChecked(supplier);
  }

  /**
   * Wraps {@code function} to be used for a stream of Maybe.
   *
   * <p>Unchecked exceptions will be immediately propagated without being wrapped.
   */
  public static <F, T, E extends Throwable> Function<F, Maybe<T, E>> wrap(
      CheckedFunction<F, T, E> function) {
    requireNonNull(function);
    return from -> getChecked(()->function.apply(from));
  }

  /**
   * Wraps {@code function} to be used for a stream of Maybe.
   *
   * <p>Unchecked exceptions will be immediately propagated without being wrapped.
   */
  public static <A, B, T, E extends Throwable> BiFunction<A, B, Maybe<T, E>> wrap(
      CheckedBiFunction<A, B, T, E> function) {
    requireNonNull(function);
    return (a, b) -> getChecked(()->function.apply(a, b));
  }

  /**
   * Wraps {@code supplier} to be used for a stream of Maybe.
   *
   * <p>Normally one should use {@link #wrap(CheckedSupplier)} unless {@code E} is an unchecked
   * exception type.
   *
   * <p>For GWT code, wrap the supplier manually, as in:
   *
   * <pre>{@code
   *   private static <T> Supplier<Maybe<T, FooException>> foo(
   *       CheckedSupplier<T, FooException> supplier) {
   *     return () -> {
   *       try {
   *         return Maybe.of(supplier.get());
   *       } catch (FooException e) {
   *         return Maybe.except(e);
   *       }
   *     };
   *   }
   * }</pre>
   */
  public static <T, E extends Throwable> Supplier<Maybe<T, E>> wrap(
      CheckedSupplier<T, E> supplier, Class<E> exceptionType) {
    requireNonNull(supplier);
    requireNonNull(exceptionType);
    return () -> get(supplier, exceptionType);
  }

  /**
   * Wraps {@code function} to be used for a stream of Maybe.
   *
   * <p>Normally one should use {@link #wrap(CheckedFunction)} unless {@code E} is an unchecked
   * exception type.
   *
   * <p>For GWT code, wrap the function manually, as in:
   *
   * <pre>{@code
   *   private static <F, T> Function<F, Maybe<T, FooException>> foo(
   *       CheckedFunction<F, T, FooException> function) {
   *     return from -> {
   *       try {
   *         return Maybe.of(function.apply(from));
   *       } catch (FooException e) {
   *         return Maybe.except(e);
   *       }
   *     };
   *   }
   * }</pre>
   */
  public static <F, T, E extends Throwable> Function<F, Maybe<T, E>> wrap(
      CheckedFunction<F, T, E> function, Class<E> exceptionType) {
    requireNonNull(function);
    requireNonNull(exceptionType);
    return from -> get(() -> function.apply(from), exceptionType);
  }

  /**
   * Wraps {@code function} to be used for a stream of Maybe.
   *
   * <p>Normally one should use {@link #wrap(CheckedBiFunction)} unless {@code E} is an unchecked
   * exception type.
   *
   * <p>For GWT code, wrap the function manually, as in:
   *
   * <pre>{@code
   *   private static <A, B, T> BiFunction<A, B, Maybe<T, FooException>> foo(
   *       CheckedBiFunction<A, B, T, FooException> function) {
   *     return (a, b) -> {
   *       try {
   *         return Maybe.of(function.apply(a, b));
   *       } catch (FooException e) {
   *         return Maybe.except(e);
   *       }
   *     };
   *   }
   * }</pre>
   */
  public static <A, B, T, E extends Throwable> BiFunction<A, B, Maybe<T, E>> wrap(
      CheckedBiFunction<A, B, T, E> function, Class<E> exceptionType) {
    requireNonNull(function);
    requireNonNull(exceptionType);
    return (a, b) -> get(() -> function.apply(a, b), exceptionType);
  }

  /**
   * Returns a wrapper of {@code stage} that if {@code stage} failed with exception of
   * {@code exceptionType}, that exception is caught and wrapped inside a {@link Maybe} to complete
   * the wrapper stage normally.
   *
   * <p>This is useful if the code is interested in recovering from its own exception while not
   * wanting to mess with other types. Neither of {@link CompletionStage#exceptionally} and
   * {@link CompletionStage#handle} methods allow selective exception recovery because you can't
   * re-throw the {@code Throwable} unless it's unchecked.
   */
  public static <T, E extends Throwable> CompletionStage<Maybe<T, E>> catchException(
      Class<E> exceptionType, CompletionStage<T> stage) {
    CompletableFuture<Maybe<T, E>> future = new CompletableFuture<>();
    stage.handle((v, e) -> {
      try {
        if (e == null) {
          future.complete(Maybe.of(v));
        } else {
          unwrapFutureException(exceptionType, e)
              .map(cause -> future.complete(Maybe.except(cause)))
              .orElseGet(() -> future.completeExceptionally(e));
        }
      } catch (Throwable x) {  // Just in case there was a bug. Don't hang the thread.
        if (x != e) x.addSuppressed(e);
        future.completeExceptionally(x);
      }
      return null;
    });
    return future;
  }

  /** Propagates {@code exception} if it's unchecked, or else return it as is. */
  static <E extends Throwable> E propagateIfUnchecked(E e) {
    if (e instanceof RuntimeException) {
      throw (RuntimeException) e;
    } else if (e instanceof Error) {
      throw (Error) e;
    } else {
      return e;
    }
  }

  private static <E extends Throwable> Optional<E> unwrapFutureException(
      Class<E> causeType, Throwable exception) {
    for (Throwable e = exception; ; e = e.getCause()) {
      if (causeType.isInstance(e)) {
        return Optional.of(causeType.cast(e));
      }
      if (!(e instanceof ExecutionException || e instanceof CompletionException)) {
        return Optional.empty();
      }
    }
  }

  // TODO: Add Checked* interfaces for all other java.util.function interfaces such as IntSupplier.
  private static <T, E extends Throwable> Maybe<T, E> get(
      CheckedSupplier<T, E> supplier, Class<E> exceptionType) {
    try {
      return of(supplier.get());
    } catch (Throwable e) {
      if (exceptionType.isInstance(e)) {
        return except(exceptionType.cast(e));
      }
      throw new AssertionError(propagateIfUnchecked(e));
    }
  }

  private static <T, E extends Throwable> Maybe<T, E> getChecked(CheckedSupplier<T, E> supplier) {
    try {
      return of(supplier.get());
    } catch (Throwable e) {
      // CheckedSupplier<T, E> can only throw unchecked or E.
      @SuppressWarnings("unchecked")
      E exception = (E) propagateIfUnchecked(e);
      return except(exception);
    }
  }

  /** No subclasses! */
  private Maybe() {}

  private static final class Success<T, E extends Throwable> extends Maybe<T, E> {
    private final T value;

    Success(T value) {
      this.value = value;
    }

    @Override public <T2> Maybe<T2, E> map(Function<? super T, ? extends T2> f) {
      return of(f.apply(value));
    }

    @Override public <T2> Maybe<T2, E> flatMap(Function<? super T, Maybe<T2, E>> f) {
      return f.apply(value);
    }

    @Override public T get() {
      return value;
    }

    @Override public boolean isPresent() {
      return true;
    }

    @Override public void ifPresent(Consumer<? super T> consumer) {
      consumer.accept(value);
    }

    @Override public T orElse(Function<? super E, ? extends T> f) {
      requireNonNull(f);
      return value;
    }

    @Override public String toString() {
      return String.valueOf(value);
    }

    @Override public int hashCode() {
      return value == null ? 0 : value.hashCode();
    }

    @Override public boolean equals(Object obj) {
      if (obj instanceof Success<?, ?>) {
        Success<?, ?> that = (Success<?, ?>) obj;
        return Objects.equals(value, that.value);
      }
      return false;
    }
  }

  private static final class Failure<T, E extends Throwable> extends Maybe<T, E> {
    private final E exception;

    Failure(E exception) {
      this.exception = requireNonNull(exception);
    }

    @Override public <T2> Maybe<T2, E> map(Function<? super T, ? extends T2> f) {
      requireNonNull(f);
      return except(exception);
    }

    @Override public <T2> Maybe<T2, E> flatMap(Function<? super T, Maybe<T2, E>> f) {
      requireNonNull(f);
      return except(exception);
    }

    @Override public T get() throws E {
      throw exception;
    }

    @Override public boolean isPresent() {
      return false;
    }

    @Override public void ifPresent(Consumer<? super T> consumer) {
      requireNonNull(consumer);
    }

    @Override public T orElse(Function<? super E, ? extends T> f) {
      return f.apply(exception);
    }

    @Override public String toString() {
      return "exception: " + exception;
    }

    @Override public int hashCode() {
      return exception.hashCode();
    }

    @Override public boolean equals(Object obj) {
      if (obj instanceof Failure<?, ?>) {
        Failure<?, ?> that = (Failure<?, ?>) obj;
        return exception.equals(that.exception);
      }
      return false;
    }
  }
}
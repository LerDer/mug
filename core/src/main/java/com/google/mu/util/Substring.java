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
package com.google.mu.util;

import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.util.Optional;
import java.util.function.Function;

/**
 * A substring inside a string, providing easy access to substrings around it
 * ({@link #getBefore before}, {@link #getAfter after} or with the substring itself
 * {@link #remove removed}, {@link #replaceWith replaced} etc.).
 * 
 * <p>For example, to strip off the "http://" prefix from a uri string if existent: <pre>
 *   static String stripHttp(String uri) {
 *     return Substring.prefix("http://").removeFrom(uri);
 *   }
 * </pre>
 *
 * To strip off either "http://" or "https://" prefix: <pre>
 *   static import com.google.mu.util.Substring.prefix;
 * 
 *   static String stripHttpOrHttps(String uri) {
 *     return prefix("http://").or(prefix("https://")).removeFrom(uri);
 *   }
 * </pre>
 *
 * To strip off the suffix starting with a dash (-) character: <pre>
 *   static String stripDashSuffix(String str) {
 *     return Substring.last('-').andAfter().removeFrom(str);
 *   }
 * </pre>
 *
 * To replace trailing "//" with "/": <pre>
 *   static String fixTrailingSlash(String str) {
 *     return Substring.suffix("//").replaceFrom(str, '/');
 *   }
 * </pre>
 *
 * To extract the 'name' and 'value' from texts in the format of "name:value": <pre>
 *   String str = ...;
 *   Substring colon = Substring.first(':').in(str).orElseThrow(BadFormatException::new);
 *   String name = colon.getBefore();
 *   String value = colon.getAfter();
 * </pre>
 *
 * @since 2.0
 */
public final class Substring {

  /** {@link Pattern} that never matches any substring. */
  public static final Pattern NONE = new Pattern() {
    private static final long serialVersionUID = 1L;
    @Override public Substring match(String s) {
      requireNonNull(s);
      return null;
    }
    @Override public String toString() {
      return "NONE";
    }
  };

  /** {@link Pattern} that matches all strings entirely. */
  public static final Pattern ALL = new Pattern() {
    private static final long serialVersionUID = 1L;
    @Override public Substring match(String s) {
      return new Substring(s, 0, s.length());
    }
    @Override public String toString() {
      return "ALL";
    }
  };

  private final String context;
  private final int startIndex;
  private final int endIndex;

  private Substring(String context, int startIndex, int endIndex) {
    this.context = context;
    this.startIndex = startIndex;
    this.endIndex = endIndex;
  }

  /** Returns a {@code Pattern} that matches strings starting with {@code prefix}. */
  public static Pattern prefix(String prefix) {
    requireNonNull(prefix);
    return new Pattern() {
      private static final long serialVersionUID = 1L;
      @Override Substring match(String str) {
        return str.startsWith(prefix) ? new Substring(str, 0, prefix.length()) : null;
      }
    };
  }

  /** Returns a {@code Pattern} that matches strings starting with {@code prefix}. */
  public static Pattern prefix(char prefix) {
    requireNonNull(prefix);
    return new Pattern() {
      private static final long serialVersionUID = 1L;
      @Override Substring match(String str) {
        return str.length() > 0 && str.charAt(0) == prefix ? new Substring(str, 0, 1) : null;
      }
    };
  }

  /** Returns a {@code Pattern} that matches strings ending with {@code suffix}. */
  public static Pattern suffix(String suffix) {
    requireNonNull(suffix);
    return new Pattern() {
      private static final long serialVersionUID = 1L;
      @Override Substring match(String str) {
        return str.endsWith(suffix)
            ? new Substring(str, str.length() - suffix.length(), str.length())
            : null;
      }
    };
  }

  /** Returns a {@code Pattern} that matches strings ending with {@code suffix}. */
  public static Pattern suffix(char suffix) {
    requireNonNull(suffix);
    return new Pattern() {
      private static final long serialVersionUID = 1L;
      @Override Substring match(String str) {
        return str.length() > 0 && str.charAt(str.length() - 1) == suffix
            ? new Substring(str, str.length() - 1, str.length())
            : null;
      }
    };
  }

  /** Returns a {@code Pattern} that matches the first occurrence of {@code c}. */
  public static Pattern first(char c) {
    return new Pattern() {
      private static final long serialVersionUID = 1L;
      @Override Substring match(String str) {
        return substring(str, str.indexOf(c), 1);
      }
    };
  }

  /** Returns a {@code Pattern} that matches the first occurrence of {@code snippet}. */
  public static Pattern first(String snippet) {
    requireNonNull(snippet);
    return new Pattern() {
      private static final long serialVersionUID = 1L;
      @Override Substring match(String str) {
        return substring(str, str.indexOf(snippet), snippet.length());
      }
    };
  }

  /**
   * Returns a {@code Pattern} that matches the first occurrence of {@code regexPattern}.
   *
   * <p>Unlike {@code str.replaceFirst(regexPattern, replacement)},
   * <pre>regex(regexPattern).replaceFrom(str, replacement)</pre> treats the {@code replacement} as a literal
   * string with no special handling of backslash (\) and dollar sign ($) characters.
   */
  public static Pattern regex(java.util.regex.Pattern regexPattern) {
    return regexGroup(regexPattern, 0);
  }

  /**
   * Returns a {@code Pattern} that matches the first occurrence of {@code regexPattern}.
   *
   * <p>Unlike {@code str.replaceFirst(regexPattern, replacement)},
   * <pre>regex(regexPattern).replaceFrom(str, replacement)</pre> treats the {@code replacement} as a literal
   * string with no special handling of backslash (\) and dollar sign ($) characters.
   *
   * <p>Because this method internally compiles {@code regexPattern}, it's more efficient to reuse the
   * returned {@link Pattern} object than calling {@code regex(regexPattern)} repetitively.
   */
  public static Pattern regex(String regexPattern) {
    return regex(java.util.regex.Pattern.compile(regexPattern));
  }

  /**
   * Returns a {@code Pattern} that matches capturing {@code group} of {@code regexPattern}.
   *
   * <p>The returned {@code Pattern} will throw {@link IndexOutOfBoundsException} when matched against
   * strings without the target {@code group}.
   */
  public static Pattern regexGroup(java.util.regex.Pattern regexPattern, int group) {
    requireNonNull(regexPattern);
    if (group < 0) throw new IllegalArgumentException("group cannot be negative: " + group);
    return new Pattern() {
      private static final long serialVersionUID = 1L;
      @Override Substring match(String str) {
        java.util.regex.Matcher matcher = regexPattern.matcher(str);
        if (matcher.find()) {
          return new Substring(str, matcher.start(group), matcher.end(group));
        } else {
          return null;
        }
      }
    };
  }

  /**
   * Returns a {@code Pattern} that matches capturing {@code group} of {@code regexPattern}.
   *
   * <p>Unlike {@code str.replaceFirst(regexPattern, replacement)},
   * <pre>regexGroup(regexPattern, group).replaceFrom(str, replacement)</pre> treats the {@code replacement}
   * as a literal string with no special handling of backslash (\) and dollar sign ($) characters.
   *
   * <p>Because this method internally compiles {@code regexPattern}, it's more efficient to reuse the
   * returned {@link Pattern} object than calling {@code regexGroup(regexPattern, group)} repetitively.
   *
   * <p>The returned {@code Pattern} will throw {@link IndexOutOfBoundsException} when matched against
   * strings without the target {@code group}.
   */
  public static Pattern regexGroup(String regexPattern, int group) {
    return regexGroup(java.util.regex.Pattern.compile(regexPattern), group);
  }

  /** Returns a {@code Pattern} that matches the last occurrence of {@code c}. */
  public static Pattern last(char c) {
    return new Pattern() {
      private static final long serialVersionUID = 1L;
      @Override Substring match(String str) {
        return substring(str, str.lastIndexOf(c), 1);
      }
    };
  }

  /** Returns a {@code Pattern} that matches the last occurrence of {@code snippet}. */
  public static Pattern last(String snippet) {
    requireNonNull(snippet);
    return new Pattern() {
      private static final long serialVersionUID = 1L;
      @Override Substring match(String str) {
        return substring(str, str.lastIndexOf(snippet), snippet.length());
      }
    };
  }

  /**
   * Returns part before this substring.
   *
   * <p>{@link #getBefore} and {@link #getAfter} are almost always used together to split a string
   * into two parts. Prefer using {@link Pattern#andBefore} if you are trying to find a prefix ending
   * with a pattern, like: <pre>
   *   String schemeStripped = Substring.first("://").andBefore().removeFrom(uri);
   * </pre> or using {@link Pattern#andAfter} to find a suffix starting with a pattern: <pre>
   *   String commentRemoved = Substring.first("//").andAfter().removeFrom(line);
   * </pre>
   */
  public String getBefore() {
    return context.substring(0, startIndex);
  }

  /**
   * Returns part after this substring.
   *
   * <p>{@link #getBefore} and {@link #getAfter} are almost always used together to split a string
   * into two parts. Prefer using {@link Pattern#andBefore} if you are trying to find a prefix ending
   * with a pattern, like: <pre>
   *   String schemeStripped = Substring.first("://").andBefore().removeFrom(uri);
   * </pre> or using {@link Pattern#andAfter} to find a suffix starting with a pattern: <pre>
   *   String commentRemoved = Substring.first("//").andAfter().removeFrom(line);
   * </pre>
   */
  public String getAfter() {
    return context.substring(endIndex);
  }

  /** Returns a new string with the substring removed. */
  public String remove() {
    if (endIndex == context.length()) {
      return getBefore();
    } else if (startIndex == 0) {
      return getAfter();
    } else {
      return getBefore() + getAfter();
    }
  }

  /** Returns a new string with {@code this} substring replaced by {@code replacement}. */
  public String replaceWith(char replacement) {
    return getBefore() + replacement + getAfter();
  }

  /** Returns a new string with {@code this} substring replaced by {@code replacement}. */
  public String replaceWith(CharSequence replacement) {
    requireNonNull(replacement);
    return getBefore() + replacement + getAfter();
  }

  /** Returns the starting index of this substring in the containing string. */
  public int getIndex() {
    return startIndex;
  }

  /** Returns the length of this substring. */
  public int length() {
    return endIndex - startIndex;
  }

  /** Returns this substring. */
  @Override public String toString() {
    return context.substring(startIndex, endIndex);
  }

  @Override public int hashCode() {
    return context.hashCode();
  }

  /** Two {@code Substring} instances are equal if they are the same sub sequences of equal strings. */
  @Override public boolean equals(Object obj) {
    if (obj instanceof Substring) {
      Substring that = (Substring) obj;
      return startIndex == that.startIndex && endIndex == that.endIndex && context.equals(that.context);
    }
    return false;
  }

  /** Returns a new {@code Substring} instance covering part to the left of this substring. */
  Substring before() {
    return new Substring(context, 0, startIndex);
  }

  /** Returns a new {@code Substring} instance covering part to the right of this substring. */
  Substring after() {
    return new Substring(context, endIndex, context.length());
  }

  /** Returns a new {@code Substring} instance that extends to the beginning of the enclosing string. */
  Substring andBefore() {
    return new Substring(context, 0, endIndex);
  }

  /** Returns a new {@code Substring} instance that extends to the end of the enclosing string. */
  Substring andAfter() {
    return new Substring(context, startIndex, context.length());
  }

  /** A substring pattern that can be matched against a string to find substrings. */
  public static abstract class Pattern implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Matches against {@code string} and returns null if not found. */
    abstract Substring match(String string);

    /** Finds the substring in {@code string} or returns {@code empty()} if not found. */
    public final Optional<Substring> in(String string) {
      return Optional.ofNullable(match(string));
    }

    /**
     * Returns a new string with the substring matched by {@code this} removed. Returns {@code string} as is
     * if a substring is not found.
     */
    public final String removeFrom(String string) {
      Substring substring = match(string);
      return substring == null ? string : substring.remove();
    }

    /**
     * Returns a new string with the substring matched by {@code this} replaced by {@code replacement}.
     * Returns {@code string} as is if a substring is not found.
     */
    public final String replaceFrom(String string, char replacement) {
      Substring substring = match(string);
      return substring == null ? string : substring.replaceWith(replacement);
    }

    /**
     * Returns a new string with the substring matched by {@code this} replaced by {@code replacement}.
     * Returns {@code string} as is if a substring is not found.
     */
    public final String replaceFrom(String string, CharSequence replacement) {
      requireNonNull(replacement);
      Substring substring = match(string);
      return substring == null ? string : substring.replaceWith(replacement);
    }

    /**
     * Returns a {@code Pattern} that fall backs to using {@code that} if {@code this} fails to
     * match.
     */
    public final Pattern or(Pattern that) {
      requireNonNull(that);
      Pattern base = this;
      return new Pattern() {
        private static final long serialVersionUID = 1L;
        @Override Substring match(String str) {
          Substring substring = base.match(str);
          return substring == null ? that.match(str) : substring;
        }
      };
    }

    /**
     * Returns a new {@code Pattern} that will match strings using {@code this} pattern and then
     * cover the range before the matched substring. For example: <pre>
     *   String startFromDoubleSlash = Substring.first("//").before().removeFrom(uri);
     * </pre>
     */
    public final Pattern before() {
      return then(Substring::before);
    }

    /**
     * Returns a new {@code Pattern} that will match strings using {@code this} pattern and then
     * cover the range after the matched substring. For example: <pre>
     *   String endWithPeriod = Substring.last(".").after().removeFrom(line);
     * </pre>
     */
    public final Pattern after() {
      return then(Substring::after);
    }

    /**
     * Returns a new {@code Pattern} that will match strings using {@code this} pattern and then
     * extend the matched substring to the beginning of the string. For example: <pre>
     *   String schemeStripped = Substring.first("://").andBefore().removeFrom(uri);
     * </pre>
     */
    public final Pattern andBefore() {
      return then(Substring::andBefore);
    }

    /**
     * Returns a new {@code Pattern} that will match strings using {@code this} pattern and then
     * extend the matched substring to the end of the string. For example: <pre>
     *   String commentRemoved = Substring.first("//").andAfter().removeFrom(line);
     * </pre>
     */
    public final Pattern andAfter() {
      return then(Substring::andAfter);
    }

    private Pattern then(Mapper mapper) {
      Pattern base = this;
      return new Pattern() {
        private static final long serialVersionUID = 1L;
        @Override Substring match(String str) {
          Substring substring = base.match(str);
          return substring == null ? null : mapper.apply(substring);
        }
      };
    }

    private interface Mapper extends Function<Substring, Substring>, Serializable {}
  }
  
  private static Substring substring(String str, int index, int length) {
    return index >= 0 ? new Substring(str, index, index + length) : null;
  }
}
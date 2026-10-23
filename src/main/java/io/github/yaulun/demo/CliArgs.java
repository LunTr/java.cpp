package io.github.yaulun.demo;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class CliArgs {
  private final Map<String, String> values;

  private CliArgs(Map<String, String> values) {
    this.values = Map.copyOf(values);
  }

  static CliArgs parse(String[] args, Set<String> flags, Set<String> valued) {
    Map<String, String> values = new HashMap<>();
    for (int index = 0; index < args.length; index++) {
      String name = args[index];
      if (!flags.contains(name) && !valued.contains(name)) {
        throw new UsageException("Unknown argument: " + name);
      }
      if (values.containsKey(name)) {
        throw new UsageException("Duplicate argument: " + name);
      }
      String value = "true";
      if (valued.contains(name)) {
        if (++index == args.length || args[index].startsWith("--")) {
          throw new UsageException("Missing value: " + name);
        }
        value = args[index];
      }
      values.put(name, value);
    }
    return new CliArgs(values);
  }

  boolean has(String name) {
    return values.containsKey(name);
  }

  Optional<String> value(String name) {
    return Optional.ofNullable(values.get(name));
  }

  int intValue(String name, int fallback, int min, int max) {
    long result = longValue(name, fallback, min, max);
    return (int) result;
  }

  long longValue(String name, long fallback, long min, long max) {
    String text = values.get(name);
    if (text == null) {
      return fallback;
    }
    try {
      long value = Long.parseLong(text);
      if (value < min || value > max) {
        throw new UsageException(name + " must be in [" + min + ", " + max + "]");
      }
      return value;
    } catch (NumberFormatException e) {
      throw new UsageException(name + " requires an integer: " + text);
    }
  }

  static final class UsageException extends IllegalArgumentException {
    UsageException(String message) {
      super(message);
    }
  }
}

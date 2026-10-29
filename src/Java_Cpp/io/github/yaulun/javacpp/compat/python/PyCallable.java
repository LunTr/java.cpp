package io.github.yaulun.javacpp.compat.python;

import java.util.List;
import java.util.Map;

/**
 * Callable protocol for values passed through the Python-facing API. Implementations receive the
 * positional and keyword arguments exactly as a Python call frame would; a null keyword map means
 * the call supplied no keyword arguments.
 */
@FunctionalInterface
public interface PyCallable {
  Object call(List<?> arguments, Map<String, ?> keywords);
}

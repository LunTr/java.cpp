package dev.javacpp.compat.python;

import dev.javacpp.python.Py;
import dev.javacpp.python.PythonErrorType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Ports llama_chat_format.LlamaChatCompletionHandlerRegistry plus the module-level helpers. The
 * pinned class stores handlers on a class attribute shared through the singleton metaclass; the
 * registry itself is a Singleton, so instances resolve to one shared handler map.
 */
public final class PythonChatHandlerRegistry extends PythonSingleton {
  private static final Map<String, Object> HANDLERS = new LinkedHashMap<>();

  public Map<String, Object> handlers() {
    return HANDLERS;
  }

  /** Ports register_chat_completion_handler. */
  public void registerChatCompletionHandler(String name, Object chatHandler, boolean overwrite) {
    if (!overwrite && HANDLERS.containsKey(name)) {
      throw Py.error(
          PythonErrorType.VALUE_ERROR,
          "Formatter with name '"
              + name
              + "' is already registered. Use `overwrite=True` to overwrite it.");
    }
    HANDLERS.put(name, chatHandler);
  }

  /** Ports unregister_chat_handler. */
  public void unregisterChatHandler(String name) {
    if (HANDLERS.containsKey(name)) {
      HANDLERS.remove(name);
    } else {
      throw Py.error(
          PythonErrorType.VALUE_ERROR, "No formatter registered under the name '" + name + "'.");
    }
  }

  /** Ports get_chat_completion_handler_by_name. */
  public Object getChatCompletionHandlerByName(String name) {
    if (!HANDLERS.containsKey(name)) {
      throw Py.error(
          PythonErrorType.LLAMA_CHAT_HANDLER_NOT_FOUND,
          "Invalid chat handler: " + name + " (valid formats: " + pyList(HANDLERS) + ")");
    }
    return HANDLERS.get(name);
  }

  /** Ports the module-level get_chat_completion_handler: singleton lookup. */
  public static Object getChatCompletionHandler(String name) {
    return PythonSingleton.call(PythonChatHandlerRegistry.class, PythonChatHandlerRegistry::new)
        .getChatCompletionHandlerByName(name);
  }

  /** Ports register_chat_completion_handler: returns a decorator registering under name. */
  public static Function<Object, Object> registerChatCompletionHandler(String name) {
    return f -> {
      PythonSingleton.call(PythonChatHandlerRegistry.class, PythonChatHandlerRegistry::new)
          .registerChatCompletionHandler(name, f, false);
      return f;
    };
  }

  /** Ports register_chat_format: wraps the formatter, registers the chat handler. */
  public static Function<Object, Object> registerChatFormat(
      String name, Function<Object, Object> formatterToHandler) {
    return f -> {
      Object handler = formatterToHandler.apply(f);
      PythonSingleton.call(PythonChatHandlerRegistry.class, PythonChatHandlerRegistry::new)
          .registerChatCompletionHandler(name, handler, false);
      return f;
    };
  }

  /** Python list repr used inside the not-found message. */
  private static String pyList(Map<String, ?> map) {
    StringBuilder out = new StringBuilder("[");
    String sep = "";
    for (String key : map.keySet()) {
      out.append(sep).append("'").append(key).append("'");
      sep = ", ";
    }
    return out.append("]").toString();
  }

  public static void clearHandlers() {
    HANDLERS.clear();
  }
}

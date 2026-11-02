package io.github.yaulun.javacpp.compat.python;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Ports the module-level accessors and helpers of server/app.py. The proxy constructor is a port so
 * set_llama_proxy stays deterministic; get_llama_proxy replays the double-lock acquire/release
 * order through an async-lock port.
 */
public final class PythonServerApp {
  private PythonServerApp() {}

  /** Module state mirroring _server_settings / _llama_proxy / _ping_message_factory. */
  public static final class ModuleState {
    public Object serverSettings;
    public Object llamaProxy;
    public Supplier<byte[]> pingMessageFactory;
  }

  /** Async-lock port exposing await acquire() / release(). */
  public interface Lock {
    void acquire();

    void release();
  }

  /** Ports set_server_settings. */
  public static void setServerSettings(ModuleState state, Object serverSettings) {
    state.serverSettings = serverSettings;
  }

  /** Ports get_server_settings: the generator yields the stored settings once. */
  public static Iterable<Object> getServerSettings(ModuleState state) {
    return java.util.Collections.singletonList(state.serverSettings);
  }

  /** Ports set_llama_proxy: constructs LlamaProxy(models=model_settings). */
  public static void setLlamaProxy(
      ModuleState state, List<?> modelSettings, Function<List<?>, Object> proxyFactory) {
    state.llamaProxy = proxyFactory.apply(modelSettings);
  }

  /** Ports set_ping_message_factory. */
  public static void setPingMessageFactory(ModuleState state, Supplier<byte[]> factory) {
    state.pingMessageFactory = factory;
  }

  /**
   * Ports get_llama_proxy: outer acquire, inner acquire, outer release, yield, inner release.
   * Returns the proxy after replaying the lock dance through the ports.
   */
  public static Object getLlamaProxy(ModuleState state, Lock outerLock, Lock innerLock) {
    outerLock.acquire();
    boolean releaseOuterLock = true;
    try {
      innerLock.acquire();
      try {
        outerLock.release();
        releaseOuterLock = false;
        return state.llamaProxy;
      } finally {
        innerLock.release();
      }
    } finally {
      if (releaseOuterLock) {
        outerLock.release();
      }
    }
  }

  /** Settings port for authenticate: only api_key matters. */
  public interface ApiSettings {
    Object apiKey();
  }

  /** Bearer authorization port: null credentials or a credentials string. */
  public interface Authorization {
    String credentials();
  }

  /** Ports authenticate: no key configured passes; wrong/missing credentials raise 401. */
  public static Object authenticate(ApiSettings settings, Authorization authorization) {
    if (settings.apiKey() == null) {
      return true;
    }
    if (authorization != null
        && authorization.credentials() != null
        && authorization.credentials().equals(settings.apiKey())) {
      return authorization.credentials();
    }
    throw io.github.yaulun.javacpp.python.Py.error(
        io.github.yaulun.javacpp.python.PythonErrorType.HTTP_EXCEPTION, "Invalid API key");
  }

  /** The llama object returned by llama_proxy(model): tokenize + detokenize. */
  public interface Llama extends Tokenizer {
    byte[] detokenize(int[] tokens);
  }

  /** Llama accessor port used by the tokenize/detokenize endpoints: proxy(model). */
  public interface LlamaAccessor {
    Llama llama(Object model);
  }

  /** Ports /extras/tokenize. */
  public static Map<String, Object> tokenizeEndpoint(
      Object input, Object model, LlamaAccessor llama) {
    int[] tokens =
        llama
            .llama(model)
            .tokenize(
                ((String) input).getBytes(java.nio.charset.StandardCharsets.UTF_8), true, true);
    java.util.List<Object> list = new java.util.ArrayList<>();
    for (int t : tokens) list.add(t);
    return Map.of("tokens", list);
  }

  /** Ports /extras/tokenize/count. */
  public static Map<String, Object> countQueryTokensEndpoint(
      Object input, Object model, LlamaAccessor llama) {
    int[] tokens =
        llama
            .llama(model)
            .tokenize(
                ((String) input).getBytes(java.nio.charset.StandardCharsets.UTF_8), true, true);
    return Map.of("count", tokens.length);
  }

  /** Ports /extras/detokenize. */
  public static Map<String, Object> detokenizeEndpoint(
      Object tokens, Object model, LlamaAccessor llama) {
    int[] ids;
    if (tokens instanceof java.util.List<?> list) {
      ids = new int[list.size()];
      for (int i = 0; i < ids.length; i++) ids[i] = ((Number) list.get(i)).intValue();
    } else {
      ids = (int[]) tokens;
    }
    byte[] bytes = llama.llama(model).detokenize(ids);
    return Map.of("text", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
  }

  /** Ports get_models: iterates the proxy yielding alias entries. */
  public static Map<String, Object> getModels(Iterable<String> llamaProxy) {
    java.util.List<Object> data = new java.util.ArrayList<>();
    for (String alias : llamaProxy) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", alias);
      item.put("object", "model");
      item.put("owned_by", "me");
      item.put("permissions", List.of());
      data.add(item);
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("object", "list");
    response.put("data", data);
    return response;
  }

  /** Ports _logit_bias_tokens_to_input_ids: utf-8 encode, tokenize(add_bos=False,special=True). */
  public interface Tokenizer {
    int[] tokenize(byte[] token, boolean addBos, boolean special);
  }

  public static Map<String, Object> logitBiasTokensToInputIds(
      Tokenizer llama, Map<String, Object> logitBias) {
    Map<String, Object> toBias = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : logitBias.entrySet()) {
      byte[] token = entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      for (int inputId : llama.tokenize(token, false, true)) {
        toBias.put(String.valueOf(inputId), entry.getValue());
      }
    }
    return toBias;
  }
}

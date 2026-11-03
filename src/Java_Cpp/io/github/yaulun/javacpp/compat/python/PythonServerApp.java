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
    ProxyGuard guard = enterLlamaProxy(state, outerLock, innerLock);
    try {
      return guard.proxy();
    } finally {
      guard.close();
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

  /** HTTPException carrying the pinned status_code and detail attributes. */
  public static final class HttpException extends RuntimeException {
    private final int statusCode;
    private final String detail;

    public HttpException(int statusCode, String detail) {
      super(statusCode + ": " + detail);
      this.statusCode = statusCode;
      this.detail = detail;
    }

    public int statusCode() {
      return statusCode;
    }

    public String detail() {
      return detail;
    }
  }

  /** anyio.get_cancelled_exc_class()() marker; rethrown unchanged by get_event_publisher. */
  public static final class CancelledError extends RuntimeException {}

  /** Outer anyio lock whose locked() state get_event_publisher polls for interrupt_requests. */
  public interface OuterLock extends Lock {
    boolean locked();
  }

  /** Request port: url.path for the copilot alias, is_disconnected and client for the guards. */
  public interface Request {
    String urlPath();

    boolean isDisconnected();

    String client();
  }

  /** inner_send_chan port: async-with enter/close plus send(dict). */
  public interface SendChannel {
    void enter();

    void send(Map<String, Object> event);

    void close();
  }

  /** Completion/chat request-body fields read by prepare_request_resources. */
  public interface ResourceBody {
    Object logitBias();

    String logitBiasType();

    String grammar();

    long minTokens();
  }

  /** CreateCompletionRequest port: mutable prompt plus model_dump. */
  public interface CompletionBody extends ResourceBody {
    Object prompt();

    void setPrompt(Object prompt);

    Object model();

    Map<String, Object> modelDump(java.util.Set<String> exclude);
  }

  /** CreateChatCompletionRequest port. */
  public interface ChatBody extends ResourceBody {
    Object model();

    Map<String, Object> modelDump(java.util.Set<String> exclude);
  }

  /** CreateEmbeddingRequest port. */
  public interface EmbeddingBody {
    Object model();

    Map<String, Object> modelDump(java.util.Set<String> exclude);
  }

  /** The llama object prepare_request_resources resolves: tokenize plus token_eos. */
  public interface ResourceLlama extends Tokenizer {
    int tokenEos();
  }

  /** Callable LlamaProxy port: llama = llama_proxy(body_model). */
  public interface ResourceProxy {
    ResourceLlama llama(Object model);
  }

  /** llama_cpp constructor ports used by prepare_request_resources. */
  public interface ResourceDeps {
    Object grammarFromString(String grammar);

    Object minTokensLogitsProcessor(long minTokens, int tokenEos);

    java.util.List<Object> logitsProcessorList(java.util.List<Object> processors);
  }

  /** functools.partial(get_event_publisher, **keywords) value. */
  public record BoundCall(String function, Map<String, Object> keywords) {}

  /** Remaining framework ports for the endpoints and the event publisher. */
  public interface EndpointDeps extends ResourceDeps {
    Object runInThreadpool(Object callable, Object[] args, Map<String, Object> kwargs);

    java.util.Iterator<Object> iterateInThreadpool(Object iterator);

    /** anyio.create_memory_object_stream: returns {send, receive}. */
    Object[] memoryObjectStream(int capacity);

    Object eventSourceResponse(
        Object receiveChannel, BoundCall dataSenderCallable, String sep, Object pingMessageFactory);

    /** llama_cpp.Llama.__call__ unbound-method token. */
    Object llamaCall();

    /** llama_cpp.Llama.create_chat_completion unbound-method token. */
    Object llamaCreateChatCompletion();

    /** Bound-method token such as llama.create_embedding. */
    Object boundMethod(Object target, String name);

    boolean interruptRequests(Object serverSettings);

    void print(String line);

    void moveOnAfter(long seconds, boolean shield);
  }

  /** Framework ports for create_app: environment, config files, pydantic and FastAPI. */
  public interface CreateAppDeps {
    String getenv(String name);

    boolean pathExists(String path);

    byte[] readFileBytes(String path);

    Object yamlSafeLoad(byte[] data);

    Object configFileValidateJson(Object payload);

    Object configModels(Object config);

    Object serverSettingsValidate(Object value);

    Object modelSettingsValidate(Object value);

    Object newSettings();

    Object rootPath(Object serverSettings);

    boolean disablePingEvents(Object serverSettings);

    Object rawContextMiddlewareClass();

    Object requestIdPlugin();

    Object corsMiddlewareClass();

    Object middleware(Object cls, java.util.List<Object> plugins);

    Object router();

    Object fastApi(Map<String, Object> kwargs);

    void addMiddleware(Object app, Object cls, Map<String, Object> kwargs);

    void includeRouter(Object app, Object router);

    Object createProxy(java.util.List<?> modelSettings);
  }

  /** Enters get_llama_proxy's double lock; the caller body runs before guard.close(). */
  private static ProxyGuard enterLlamaProxy(ModuleState state, Lock outerLock, Lock innerLock) {
    outerLock.acquire();
    boolean releaseOuterLock = true;
    try {
      innerLock.acquire();
      try {
        outerLock.release();
        releaseOuterLock = false;
        return new ProxyGuard(state.llamaProxy, innerLock);
      } finally {
        if (releaseOuterLock) {
          innerLock.release();
        }
      }
    } finally {
      if (releaseOuterLock) {
        outerLock.release();
      }
    }
  }

  private record ProxyGuard(Object proxy, Lock innerLock) {
    void close() {
      innerLock.release();
    }
  }

  /**
   * Ports prepare_request_resources: 503 on a missing proxy, logit_bias conversion, grammar
   * compilation and the min_tokens logits-processor merge.
   */
  @SuppressWarnings("unchecked")
  public static ResourceLlama prepareRequestResources(
      ResourceBody body,
      ResourceProxy llamaProxy,
      Object bodyModel,
      Map<String, Object> kwargs,
      ResourceDeps deps) {
    if (llamaProxy == null) {
      throw new HttpException(503, "Service is not available");
    }
    ResourceLlama llama = llamaProxy.llama(bodyModel);
    if (body.logitBias() != null) {
      kwargs.put(
          "logit_bias",
          "tokens".equals(body.logitBiasType())
              ? logitBiasTokensToInputIds(llama, (Map<String, Object>) body.logitBias())
              : body.logitBias());
    }
    if (body.grammar() != null) {
      kwargs.put("grammar", deps.grammarFromString(body.grammar()));
    }
    if (body.minTokens() > 0) {
      java.util.List<Object> minTokensProcessors =
          deps.logitsProcessorList(
              java.util.List.of(deps.minTokensLogitsProcessor(body.minTokens(), llama.tokenEos())));
      if (!kwargs.containsKey("logits_processor")) {
        kwargs.put("logits_processor", minTokensProcessors);
      } else {
        ((java.util.List<Object>) kwargs.get("logits_processor")).addAll(minTokensProcessors);
      }
    }
    return llama;
  }

  /**
   * Ports get_event_publisher: double-lock proxy lookup, streamed chunks, disconnect and
   * interrupt_requests cancellation, then the [DONE] sentinel.
   */
  public static void getEventPublisher(
      ModuleState state,
      OuterLock outerLock,
      Lock innerLock,
      Request request,
      SendChannel innerSendChan,
      ResourceBody body,
      Object bodyModel,
      Object llamaCall,
      Map<String, Object> kwargs,
      EndpointDeps deps) {
    Object serverSettings = state.serverSettings;
    boolean interruptRequests =
        io.github.yaulun.javacpp.python.Py.truth(serverSettings)
            && deps.interruptRequests(serverSettings);
    ProxyGuard guard = enterLlamaProxy(state, outerLock, innerLock);
    try {
      ResourceLlama llama =
          prepareRequestResources(body, (ResourceProxy) guard.proxy(), bodyModel, kwargs, deps);
      innerSendChan.enter();
      try {
        try {
          Object iterator = deps.runInThreadpool(llamaCall, new Object[] {llama}, kwargs);
          java.util.Iterator<Object> chunks = deps.iterateInThreadpool(iterator);
          while (chunks.hasNext()) {
            innerSendChan.send(Map.of("data", PythonJson.dumps(chunks.next())));
            if (request.isDisconnected()) {
              throw new CancelledError();
            }
            if (interruptRequests && outerLock.locked()) {
              innerSendChan.send(Map.of("data", "[DONE]"));
              throw new CancelledError();
            }
          }
          innerSendChan.send(Map.of("data", "[DONE]"));
        } catch (CancelledError e) {
          deps.print("disconnected");
          deps.moveOnAfter(1, true);
          deps.print("Disconnected from client (via refresh/close) " + request.client());
          throw e;
        }
      } finally {
        innerSendChan.close();
      }
    } finally {
      guard.close();
    }
  }

  private static Map<String, Object> eventPublisherKeywords(
      Request request,
      Object sendChan,
      Object body,
      Object bodyModel,
      Object llamaCall,
      Map<String, Object> kwargs) {
    Map<String, Object> bound = new LinkedHashMap<>();
    bound.put("request", request);
    bound.put("inner_send_chan", sendChan);
    bound.put("body", body);
    bound.put("body_model", bodyModel);
    bound.put("llama_call", llamaCall);
    bound.put("kwargs", kwargs);
    return bound;
  }

  /**
   * Ports create_completion: prompt list normalization, the copilot-codex alias, streaming
   * EventSourceResponse or the guarded threadpool call.
   */
  public static Object createCompletion(
      ModuleState state,
      OuterLock outerLock,
      Lock innerLock,
      Request request,
      CompletionBody body,
      EndpointDeps deps) {
    if (body.prompt() instanceof java.util.List<?> prompts) {
      if (prompts.size() > 1) {
        throw io.github.yaulun.javacpp.python.Py.error(
            io.github.yaulun.javacpp.python.PythonErrorType.ASSERTION_ERROR, "");
      }
      body.setPrompt(prompts.isEmpty() ? "" : prompts.get(0));
    }
    Object bodyModel =
        "/v1/engines/copilot-codex/completions".equals(request.urlPath())
            ? "copilot-codex"
            : body.model();
    Map<String, Object> kwargs =
        body.modelDump(java.util.Set.of("n", "best_of", "logit_bias_type", "user", "min_tokens"));
    if (io.github.yaulun.javacpp.python.Py.truth(kwargs.get("stream"))) {
      Object[] channels = deps.memoryObjectStream(10);
      return deps.eventSourceResponse(
          channels[1],
          new BoundCall(
              "get_event_publisher",
              eventPublisherKeywords(
                  request, channels[0], body, bodyModel, deps.llamaCall(), kwargs)),
          "\n",
          state.pingMessageFactory);
    }
    ProxyGuard guard = enterLlamaProxy(state, outerLock, innerLock);
    try {
      ResourceLlama llama =
          prepareRequestResources(body, (ResourceProxy) guard.proxy(), bodyModel, kwargs, deps);
      if (request.isDisconnected()) {
        deps.print(
            "Disconnected from client (via refresh/close) before llm invoked " + request.client());
        throw new HttpException(400, "Client closed request");
      }
      return deps.runInThreadpool(llama, new Object[0], kwargs);
    } finally {
      guard.close();
    }
  }

  /** Ports create_embedding: run_in_threadpool over proxy(model).create_embedding. */
  public static Object createEmbedding(
      EmbeddingBody request, ResourceProxy llamaProxy, EndpointDeps deps) {
    if (llamaProxy == null) {
      throw io.github.yaulun.javacpp.python.Py.error(
          io.github.yaulun.javacpp.python.PythonErrorType.TYPE_ERROR,
          "'NoneType' object is not callable");
    }
    return deps.runInThreadpool(
        deps.boundMethod(llamaProxy.llama(request.model()), "create_embedding"),
        new Object[0],
        request.modelDump(java.util.Set.of("user")));
  }

  /**
   * Ports create_chat_completion: model_dump kwargs, streaming EventSourceResponse or the guarded
   * create_chat_completion threadpool call.
   */
  public static Object createChatCompletion(
      ModuleState state,
      OuterLock outerLock,
      Lock innerLock,
      Request request,
      ChatBody body,
      EndpointDeps deps) {
    Object bodyModel = body.model();
    Map<String, Object> kwargs =
        body.modelDump(java.util.Set.of("n", "logit_bias_type", "user", "min_tokens"));
    if (io.github.yaulun.javacpp.python.Py.truth(kwargs.get("stream"))) {
      Object[] channels = deps.memoryObjectStream(10);
      return deps.eventSourceResponse(
          channels[1],
          new BoundCall(
              "get_event_publisher",
              eventPublisherKeywords(
                  request, channels[0], body, bodyModel, deps.llamaCreateChatCompletion(), kwargs)),
          "\n",
          state.pingMessageFactory);
    }
    ProxyGuard guard = enterLlamaProxy(state, outerLock, innerLock);
    try {
      ResourceLlama llama =
          prepareRequestResources(body, (ResourceProxy) guard.proxy(), bodyModel, kwargs, deps);
      if (request.isDisconnected()) {
        deps.print(
            "Disconnected from client (via refresh/close) before llm invoked " + request.client());
        throw new HttpException(400, "Client closed request");
      }
      return deps.runInThreadpool(
          deps.boundMethod(llama, "create_chat_completion"), new Object[0], kwargs);
    } finally {
      guard.close();
    }
  }

  /**
   * Ports create_app: CONFIG_FILE resolution (JSON or yaml), settings validation, module state
   * wiring and the FastAPI construction sequence.
   */
  public static Object createApp(
      ModuleState state,
      Object settings,
      Object serverSettings,
      java.util.List<?> modelSettings,
      CreateAppDeps deps) {
    String configFile = deps.getenv("CONFIG_FILE");
    if (configFile != null) {
      if (!deps.pathExists(configFile)) {
        throw io.github.yaulun.javacpp.python.Py.error(
            io.github.yaulun.javacpp.python.PythonErrorType.VALUE_ERROR,
            "Config file " + configFile + " not found!");
      }
      byte[] data = deps.readFileBytes(configFile);
      Object configFileSettings;
      if (configFile.endsWith(".yaml") || configFile.endsWith(".yml")) {
        configFileSettings = deps.configFileValidateJson(PythonJson.dumps(deps.yamlSafeLoad(data)));
      } else {
        configFileSettings = deps.configFileValidateJson(data);
      }
      serverSettings = deps.serverSettingsValidate(configFileSettings);
      modelSettings = (java.util.List<?>) deps.configModels(configFileSettings);
    }
    if (serverSettings == null && modelSettings == null) {
      if (settings == null) {
        settings = deps.newSettings();
      }
      serverSettings = deps.serverSettingsValidate(settings);
      modelSettings = java.util.List.of(deps.modelSettingsValidate(settings));
    }
    if (serverSettings == null || modelSettings == null) {
      throw io.github.yaulun.javacpp.python.Py.error(
          io.github.yaulun.javacpp.python.PythonErrorType.ASSERTION_ERROR,
          "server_settings and model_settings must be provided together");
    }
    setServerSettings(state, serverSettings);
    Object middleware =
        java.util.List.of(
            deps.middleware(
                deps.rawContextMiddlewareClass(), java.util.List.of(deps.requestIdPlugin())));
    Map<String, Object> fastApiKwargs = new LinkedHashMap<>();
    fastApiKwargs.put("middleware", middleware);
    fastApiKwargs.put("title", "🦙 llama.cpp Python API");
    fastApiKwargs.put("version", PythonErrorBridge.PYTHON_VERSION);
    fastApiKwargs.put("root_path", deps.rootPath(serverSettings));
    Object app = deps.fastApi(fastApiKwargs);
    Map<String, Object> corsKwargs = new LinkedHashMap<>();
    corsKwargs.put("allow_origins", java.util.List.of("*"));
    corsKwargs.put("allow_credentials", true);
    corsKwargs.put("allow_methods", java.util.List.of("*"));
    corsKwargs.put("allow_headers", java.util.List.of("*"));
    deps.addMiddleware(app, deps.corsMiddlewareClass(), corsKwargs);
    deps.includeRouter(app, deps.router());
    setLlamaProxy(state, modelSettings, deps::createProxy);
    if (deps.disablePingEvents(serverSettings)) {
      setPingMessageFactory(state, () -> new byte[0]);
    }
    return app;
  }
}

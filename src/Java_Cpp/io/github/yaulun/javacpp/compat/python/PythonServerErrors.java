package io.github.yaulun.javacpp.compat.python;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ports server/errors.py: the two ErrorResponseFormatters and RouteErrorHandler. Request bodies,
 * the inner route handler, the request JSON payload and the clock are injectable ports so the
 * control flow replays without FastAPI.
 */
public final class PythonServerErrors {
  private PythonServerErrors() {}

  /** Request body kinds the pinned isinstance check distinguishes. */
  public enum BodyKind {
    CHAT_COMPLETION,
    COMPLETION,
    EMBEDDING
  }

  /** Completion/chat request surface: max_tokens plus the messages discriminator. */
  public interface Body {
    BodyKind kind();

    Object maxTokens();

    default boolean hasMessages() {
      return kind() == BodyKind.CHAT_COMPLETION;
    }
  }

  /** OpenAI-style error payload; insertion order matches the TypedDict field order. */
  public static Map<String, Object> errorResponse(
      Object message, Object type, Object param, Object code) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("message", message);
    error.put("type", type);
    error.put("param", param);
    error.put("code", code);
    return error;
  }

  public static final Pattern CONTEXT_LENGTH_PATTERN =
      Pattern.compile("Requested tokens \\((\\d+)\\) exceed context window of (\\d+)");
  public static final Pattern MODEL_PATH_PATTERN =
      Pattern.compile("Model path does not exist: (.+)");

  /** Ports ErrorResponseFormatters.context_length_exceeded; returns the (status, error) tuple. */
  public static java.util.List<Object> contextLengthExceeded(Body request, Matcher match) {
    int contextWindow = Integer.parseInt(match.group(2));
    int promptTokens = Integer.parseInt(match.group(1));
    Object completionTokens = request.maxTokens();
    String message =
        request.hasMessages()
            ? "This model's maximum context length is {} tokens. "
                + "However, you requested {} tokens "
                + "({} in the messages, {} in the completion). "
                + "Please reduce the length of the messages or completion."
            : "This model's maximum context length is {} tokens, "
                + "however you requested {} tokens "
                + "({} in your prompt; {} for the completion). "
                + "Please reduce your prompt; or completion length.";
    long requested =
        promptTokens + (completionTokens == null ? 0L : ((Number) completionTokens).longValue());
    return java.util.List.of(
        400,
        errorResponse(
            format(message, contextWindow, requested, promptTokens, completionTokens),
            "invalid_request_error",
            "messages",
            "context_length_exceeded"));
  }

  /** Ports ErrorResponseFormatters.model_not_found; returns the (status, error) tuple. */
  public static java.util.List<Object> modelNotFound(Body request, Matcher match) {
    String modelPath = match.group(1);
    return java.util.List.of(
        400,
        errorResponse(
            "The model `" + modelPath + "` does not exist",
            "invalid_request_error",
            null,
            "model_not_found"));
  }

  /** RouteErrorHandler with injectable inner handler, request JSON and clock. */
  public static final class RouteErrorHandler {
    private final Function<Request, Response> originalHandler;
    private final Function<Map<String, Object>, Body> bodyFactory;
    private final Consumer<String> stderr;
    private final java.util.function.DoubleSupplier clock;

    public RouteErrorHandler(
        Function<Request, Response> originalHandler,
        Function<Map<String, Object>, Body> bodyFactory,
        Consumer<String> stderr,
        java.util.function.DoubleSupplier clockSeconds) {
      this.originalHandler = Objects.requireNonNull(originalHandler);
      this.bodyFactory = Objects.requireNonNull(bodyFactory);
      this.stderr = Objects.requireNonNull(stderr);
      this.clock = Objects.requireNonNull(clockSeconds);
    }

    /** Ports error_message_wrapper; non-matching bodies log and return 500. */
    public java.util.List<Object> errorMessageWrapper(Exception error, Body body) {
      if (body != null && (body.kind() != BodyKind.EMBEDDING)) {
        // pattern_and_formatters dict order: context length first, then model path.
        Matcher context = CONTEXT_LENGTH_PATTERN.matcher(String.valueOf(pyStr(error)));
        if (context.find()) {
          return contextLengthExceeded(body, context);
        }
        Matcher model = MODEL_PATH_PATTERN.matcher(String.valueOf(pyStr(error)));
        if (model.find()) {
          return modelNotFound(body, model);
        }
      }
      // print(..., file=sys.stderr) writes the text and end separately; traceback adds its lines.
      stderr.accept("Exception: " + pyStr(error));
      stderr.accept("\n");
      stderr.accept("NoneType: None\n");
      stderr.accept("");
      return java.util.List.of(
          500, errorResponse(pyStr(error), "internal_server_error", null, null));
    }

    /** Ports custom_route_handler: timing header, HTTPException passthrough, body reparse. */
    public Response customRouteHandler(Request request) throws Exception {
      try {
        double start = clock.getAsDouble();
        Response response = originalHandler.apply(request);
        int elapsed = (int) ((clock.getAsDouble() - start) * 1000);
        response.headers().put("openai-processing-ms", Integer.toString(elapsed));
        return response;
      } catch (HttpException unauthorized) {
        throw unauthorized;
      } catch (Exception exc) {
        // request.json() is outside the parse try; its own failures propagate like Python's.
        Map<String, Object> jsonBody = request.json();
        Body body;
        try {
          body = bodyFactory.apply(jsonBody);
        } catch (Exception invalid) {
          body = null;
        }
        java.util.List<Object> wrapped = errorMessageWrapper(exc, body);
        return Response.json(Map.of("error", wrapped.get(1)), (int) wrapped.get(0));
      }
    }
  }

  /** Raised for HTTPException passthrough (e.g. API key failures). */
  public static class HttpException extends RuntimeException {
    public HttpException(String message) {
      super(message);
    }
  }

  /** Request port: json() returns the raw payload map or throws, like await request.json(). */
  public interface Request {
    Map<String, Object> json() throws Exception;
  }

  /** Response port with a mutable headers map; JSON responses also expose payload/status. */
  public interface Response {
    Map<String, String> headers();

    default Object payload() {
      return null;
    }

    default int statusCode() {
      return 200;
    }

    static Response json(Object payload, int statusCode) {
      Map<String, String> headers = new LinkedHashMap<>();
      return new Response() {
        public Map<String, String> headers() {
          return headers;
        }

        public Object payload() {
          return payload;
        }

        public int statusCode() {
          return statusCode;
        }
      };
    }
  }

  /** str.format-style {} substitution used by the completion message. */
  private static String format(String template, Object... values) {
    StringBuilder out = new StringBuilder();
    int index = 0;
    for (Object value : values) {
      int brace = template.indexOf("{}", index);
      out.append(template, index, brace).append(value == null ? "None" : value);
      index = brace + 2;
    }
    return out.append(template, index, template.length()).toString();
  }

  /** str(error) for an exception is its message, not the Java qualified toString. */
  private static String pyStr(Throwable error) {
    return error.getMessage();
  }
}

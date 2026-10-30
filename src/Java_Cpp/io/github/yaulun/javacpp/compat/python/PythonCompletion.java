package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.python.PythonValues.*;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.function.*;
import io.github.yaulun.javacpp.python.Py;
import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonException;
import io.github.yaulun.javacpp.python.PythonNumbers;
import io.github.yaulun.javacpp.python.PythonValues;

/** Resumable port of the original _create_completion body; each next resumes one yield. */
final class PythonCompletion implements Iterator<Map<String, Object>> {
  private final PythonLlama llama;
  private final PythonCompletionModel model;
  private final Map<String, Object> options;
  private final boolean verbose;
  private final Supplier<String> idFactory;
  private final LongSupplier clock;
  private final PythonWarnings warnings;
  private final boolean infill;
  private String id, modelName, reason = "length";
  private long created;
  private Object prompt;
  private int promptLength, bos, returned, multibyte, phase;
  private double maximum;
  private boolean stream, echo;
  private Object logprobs;
  private List<Integer> promptTokens, completionTokens, remaining;
  private List<byte[]> stops;
  private byte[] text = new byte[0], remainingText;
  private int position, stopPrefix, tailEnd;
  private PythonLlama.TokenGenerator generator;
  private PythonStoppingCriteriaList stopping;
  private Map<String, Object> buffered;

  PythonCompletion(
      PythonLlama llama,
      PythonCompletionModel model,
      Map<String, Object> arguments,
      boolean verbose,
      Supplier<String> idFactory,
      LongSupplier clock,
      PythonWarnings warnings,
      boolean infill) {
    this.llama = llama;
    this.model = model;
    options = PythonLlamaDefaults.bind("_create_completion", arguments);
    this.verbose = verbose;
    this.idFactory = idFactory;
    this.clock = clock;
    this.warnings = warnings;
    this.infill = infill;
  }

  public boolean hasNext() {
    if (buffered == null && phase != 9) buffered = advance();
    return buffered != null;
  }

  public Map<String, Object> next() {
    if (buffered != null) {
      var value = buffered;
      buffered = null;
      return value;
    }
    if (phase == 9) throw new NoSuchElementException();
    var value = advance();
    if (value == null) throw new NoSuchElementException();
    return value;
  }

  private Map<String, Object> advance() {
    try {
      return resume();
    } catch (RuntimeException | Error failure) {
      phase = 9;
      throw failure;
    }
  }

  private Map<String, Object> resume() {
    if (phase == 0) initialize();
    while (phase != 9) {
      if (phase == 1) {
        Integer token = generator.next();
        if (token == null) {
          phase = 4;
          continue;
        }
        if (model.isEog(token)) {
          text = detok(completionTokens, promptTokens);
          reason = "stop";
          phase = 4;
          continue;
        }
        completionTokens.add(token);
        byte[] all = detok(completionTokens, promptTokens);
        byte[] last = prefix(all, all.length);
        int start = Math.max(0, last.length - 3);
        for (int i = start; i < last.length; i++) {
          int k = 3 - (i - start), c = Byte.toUnsignedInt(last[i]);
          for (int n = 2; n <= 4; n++) {
            int pattern = n == 2 ? 192 : n == 3 ? 224 : 240;
            if (n > k && (pattern & c) == pattern) multibyte = n - k;
          }
        }
        if (multibyte > 0) {
          multibyte--;
          continue;
        }
        byte[] found = null;
        for (byte[] stop : stops)
          if (indexOf(all, stop) >= 0) {
            found = stop;
            break;
          }
        if (found != null) {
          text = prefix(all, indexOf(all, found));
          reason = "stop";
          phase = 4;
          continue;
        }
        if (stream) {
          remaining = new ArrayList<>(completionTokens.subList(returned, completionTokens.size()));
          remainingText =
              detok(remaining, concat(promptTokens, completionTokens.subList(0, returned)));
          stopPrefix = 0;
          position = 0;
          for (byte[] stop : stops)
            for (int i = Math.min(stop.length, remainingText.length); i > 0; i--)
              if (endsWith(remainingText, prefix(stop, i))) {
                stopPrefix = Math.max(stopPrefix, i);
                break;
              }
          phase = 2;
        } else phase = 3;
      } else if (phase == 2) {
        Map<String, Object> chunk = regularChunk();
        if (chunk != null) return chunk;
        phase = 3;
      } else if (phase == 3) {
        if (completionTokens.size() >= maximum) {
          text = detok(completionTokens, promptTokens);
          reason = "length";
          phase = 4;
        } else phase = 1;
      } else if (phase == 4) {
        if (stopping != null
            && stopping.test(
                Arrays.copyOf(llama.inputIds().array(), llama.inputIds().remaining()),
                llama.scoreRow(-1))) {
          text = detok(completionTokens, promptTokens);
          reason = "stop";
        }
        if (verbose) model.printTimings();
        if (stream) {
          remaining = new ArrayList<>(completionTokens.subList(returned, completionTokens.size()));
          remainingText =
              detok(remaining, concat(promptTokens, completionTokens.subList(0, returned)));
          tailEnd = remainingText.length;
          position = 0;
          for (byte[] stop : stops) {
            int at = indexOf(remainingText, stop);
            if (at >= 0) tailEnd = Math.min(tailEnd, at);
          }
          phase = 5;
        } else {
          cacheSave(false);
          phase = 9;
          return nonstream();
        }
      } else if (phase == 5) {
        Map<String, Object> chunk = tailChunk();
        if (chunk != null) return chunk;
        phase = 6;
      } else if (phase == 6) {
        phase = 7;
        return chunk("", null, reason);
      } else if (phase == 7) {
        cacheSave(true);
        phase = 9;
      }
    }
    return null;
  }

  private void initialize() {
    Object suffix = options.get("suffix");
    if (suffix != null && !(suffix instanceof String))
      throw error(PythonErrorType.ASSERTION_ERROR, "");
    id = idFactory.get();
    created = clock.getAsLong();
    bos = model.bos();
    int cls = model.cls(), sep = model.sep();
    boolean addSpace = model.metadata("tokenizer.ggml.add_space_prefix", "true").equals("true");
    prompt = options.get("prompt");
    promptLength = Py.len(prompt);
    List<Integer> beginnings = new ArrayList<>(List.of(cls != -1 ? cls : bos));
    List<Integer> endings = new ArrayList<>(List.of(sep != -1 ? sep : model.eos()));
    if ((prompt instanceof List<?> && suffix == null)
        || !model.addBos()
        || beginnings.getFirst() == -1) beginnings.clear();
    if ((prompt instanceof List<?> && suffix == null) || (!model.addEos() && sep == -1))
      endings.clear();
    int suffixOffset = 0;
    if (addSpace && truth(suffix)) {
      suffix = "☺" + suffix;
      suffixOffset = 2;
    }
    completionTokens = new ArrayList<>();
    if (promptLength == 0) completionTokens.add(bos);
    List<Integer> prefixTokens = new ArrayList<>();
    if (suffix != null) prefixTokens.add(0);
    if (prompt instanceof String value) {
      if (!value.isEmpty())
        prefixTokens.addAll(
            ints(llama.tokenize(PythonLlamaServices.strictUtf8(value), false, suffix == null)));
    } else for (Object token : list(prompt)) prefixTokens.add(((Number) token).intValue());
    List<Integer> suffixTokens = new ArrayList<>();
    if (suffix != null) {
      suffixTokens.add(0);
      if (truth(suffix)) {
        var ids = ints(llama.tokenize(PythonLlamaServices.strictUtf8(suffix), false, false));
        suffixTokens.addAll(ids.subList(Math.min(suffixOffset, ids.size()), ids.size()));
      }
    }
    List<Integer> middle = suffix != null ? List.of(0) : List.of();
    promptTokens =
        concat(
            beginnings,
            infill ? concat(suffixTokens, prefixTokens) : concat(prefixTokens, suffixTokens),
            middle,
            endings);
    stream = truth(options.get("stream"));
    echo = truth(options.get("echo"));
    logprobs = options.get("logprobs");
    List<?> stopOptions =
        options.get("stop") instanceof List<?> values
            ? values
            : options.get("stop") instanceof String value ? List.of(value) : List.of();
    modelName = options.get("model") == null ? model.modelPath() : (String) options.get("model");
    if (promptTokens.size() >= 2
        && promptTokens.get(0) == model.bos()
        && promptTokens.get(1) == model.bos())
      warnings.duplicateBos(
          model.tokenText(model.bos()),
          new PythonWarningLocation("llama_cpp/llama.py", 1314, "llama_cpp.llama", null));
    Object processors = options.get("logits_processor");
    if (options.get("logit_bias") != null) {
      PythonLogitsProcessor bias = logitBias((Map<?, ?>) options.get("logit_bias"));
      if (processors == null) {
        var list = new PythonLogitsProcessorList();
        list.add(bias);
        processors = list;
      } else {
        ((PythonLogitsProcessorList) processors).add(bias);
        processors = null;
      }
    }
    if (verbose) model.resetTimings();
    int capacity = llama.configuredContextLength();
    if (promptTokens.size() >= capacity)
      throw error(
          PythonErrorType.VALUE_ERROR,
          "Requested tokens ("
              + promptTokens.size()
              + ") exceed context window of "
              + model.contextLength());
    maximum =
        options.get("max_tokens") == null
            ? 0
            : PythonNumbers.compareNumber(options.get("max_tokens"), "<=", "int");
    if (maximum <= 0) maximum = capacity - promptTokens.size();
    maximum = maximum + promptTokens.size() < capacity ? maximum : capacity - promptTokens.size();
    stops = new ArrayList<>();
    for (Object value : stopOptions) stops.add(PythonLlamaServices.strictUtf8(value));
    if (logprobs != null && !llama.logitsAllEnabled())
      throw error(
          PythonErrorType.VALUE_ERROR,
          "logprobs is not supported for models created with logits_all=False");
    if (Py.truth(llama.cache())) {
      try {
        var cached = (PythonLlamaState) Py.getItem(llama.cache(), promptTokens);
        int cachePrefix = common(cached.inputIds(), promptTokens),
            livePrefix =
                common(
                    Arrays.copyOf(llama.inputIds().array(), llama.inputIds().remaining()),
                    promptTokens);
        if (cachePrefix > livePrefix) {
          llama.loadState(cached);
          if (verbose) llama.diagnostic("Llama._create_completion: cache hit");
        }
      } catch (PythonException failure) {
        if (failure.type() != PythonErrorType.KEY_ERROR) throw failure;
        if (verbose) llama.diagnostic("Llama._create_completion: cache miss");
      }
    }
    llama.setSeed(
        options.get("seed") != null
            ? options.get("seed")
            : BigInteger.valueOf(new PythonRandom(llama.seedObject()).completionSeed()));
    Map<String, Object> sampling = new LinkedHashMap<>();
    for (String name :
        List.of(
            "top_k",
            "top_p",
            "min_p",
            "typical_p",
            "tfs_z",
            "mirostat_mode",
            "mirostat_tau",
            "mirostat_eta",
            "frequency_penalty",
            "presence_penalty",
            "repeat_penalty",
            "grammar")) sampling.put(name, options.get(name));
    sampling.put("temp", options.get("temperature"));
    sampling.put("logits_processor", processors);
    stopping = (PythonStoppingCriteriaList) options.get("stopping_criteria");
    sampling.put("stopping_criteria", stopping);
    generator = llama.generate(array(promptTokens), sampling);
    phase = 1;
  }

  static PythonLogitsProcessor logitBias(Map<?, ?> values) {
    Map<BigInteger, Double> bias = new LinkedHashMap<>();
    for (var entry : values.entrySet())
      bias.put(PythonNumbers.integer(entry.getKey()), PythonNumbers.floating(entry.getValue()));
    return (ids, scores) -> {
      float[] result = scores.clone();
      for (var entry : bias.entrySet()) {
        int index = PythonNumbers.arrayIndex(entry.getKey(), scores.length);
        result[index] = (float) (entry.getValue() + scores[index]);
      }
      return result;
    };
  }

  private Map<String, Object> regularChunk() {
    while (!remaining.isEmpty()) {
      int token = remaining.getFirst();
      if (logprobs != null) {
        remaining.removeFirst();
        if (token == bos) continue;
        List<Integer> previous = concat(promptTokens, completionTokens.subList(0, returned));
        position += detok(List.of(token), previous).length;
        if (position > remainingText.length - stopPrefix) {
          remaining.clear();
          return null;
        }
        String tokenText = decode(detok(List.of(token), previous));
        int offset =
            promptLength + Py.len(decode(detok(completionTokens.subList(0, returned), previous)));
        float[] probabilities =
            PythonLlama.logitsToLogprobs(llama.scoreRow(promptTokens.size() + returned - 1));
        Map<String, Object> top = top(probabilities, null);
        top.put(tokenText, (double) probabilities[token]);
        Object detail =
            dict(
                "tokens",
                List.of(decode(detok(List.of(token), previous))),
                "text_offset",
                List.of(offset),
                "token_logprobs",
                List.of((double) probabilities[token]),
                "top_logprobs",
                List.of(top));
        returned++;
        return chunk(
            decode(
                detok(List.of(token), concat(promptTokens, completionTokens.subList(0, returned)))),
            detail,
            null);
      }
      String decoded = null;
      byte[] bytes = null;
      int consumed = 0;
      for (int i = 1; i <= remaining.size(); i++) {
        bytes =
            detok(
                remaining.subList(0, i),
                concat(promptTokens, completionTokens.subList(0, returned)));
        decoded = decodeStrict(bytes);
        if (decoded != null) {
          consumed = i;
          break;
        }
      }
      if (decoded == null) {
        remaining.clear();
        return null;
      }
      position += bytes.length;
      if (position > remainingText.length - stopPrefix) {
        remaining.clear();
        return null;
      }
      remaining = new ArrayList<>(remaining.subList(consumed, remaining.size()));
      returned += consumed;
      return chunk(decoded, null, null);
    }
    return null;
  }

  private Map<String, Object> tailChunk() {
    while (!remaining.isEmpty()) {
      int token = remaining.removeFirst();
      position +=
          detok(List.of(token), concat(promptTokens, completionTokens.subList(0, returned))).length;
      Object detail = null;
      if (logprobs != null) {
        if (token == bos) continue;
        String tokenText = decode(detok(List.of(token), null));
        int offset =
            promptLength
                + detok(
                        completionTokens.subList(0, returned),
                        concat(promptTokens, completionTokens.subList(0, returned)))
                    .length;
        float[] probabilities =
            PythonLlama.logitsToLogprobs(llama.scoreRow(promptTokens.size() + returned - 1));
        Map<String, Object> top = top(probabilities, null);
        top.put(tokenText, (double) probabilities[token]);
        detail =
            dict(
                "tokens",
                List.of(decode(detok(List.of(token), null))),
                "text_offset",
                List.of(offset),
                "token_logprobs",
                List.of((double) probabilities[token]),
                "top_logprobs",
                List.of(top));
      }
      if (position >= tailEnd) {
        byte[] last = detok(List.of(token), null);
        if (position == tailEnd - 1) {
          remaining.clear();
          return null;
        }
        returned++;
        remaining.clear();
        return chunk(decode(prefix(last, last.length - (position - tailEnd))), detail, null);
      }
      returned++;
      return chunk(decode(detok(List.of(token), null)), detail, null);
    }
    return null;
  }

  private Map<String, Object> nonstream() {
    String value = decode(text);
    if (echo) value = leftString(prompt) + value;
    Object detail = null;
    if (logprobs != null) {
      List<Integer> all =
          echo
              ? concat(
                  promptTokens.subList(
                      !promptTokens.isEmpty() && promptTokens.getFirst() == model.bos() ? 1 : 0,
                      promptTokens.size()),
                  completionTokens)
              : completionTokens;
      int tokenOffset = echo ? 0 : Math.max(0, promptTokens.size() - 1),
          offset = echo ? 0 : promptLength;
      List<String> tokens = new ArrayList<>();
      List<Integer> offsets = new ArrayList<>();
      List<Object> tokenProbabilities = new ArrayList<>(), tops = new ArrayList<>();
      int rows = llama.scores().size();
      List<String> tokenStrings = new ArrayList<>();
      for (int i = 0; i < all.size(); i++)
        tokenStrings.add(decode(detok(List.of(all.get(i)), all.subList(0, i))));
      for (int i = 0; i < all.size() && i + tokenOffset < rows; i++) {
        int token = all.get(i);
        String tokenText = tokenStrings.get(i);
        float[] probabilities = PythonLlama.logitsToLogprobs(llama.scoreRow(i + tokenOffset));
        if (token == bos) continue;
        offsets.add(offset + Py.len(decode(detok(all.subList(0, i), null))));
        tokens.add(tokenText);
        tokenProbabilities.add((double) probabilities[token]);
        Map<String, Object> top = top(probabilities, all.subList(0, i));
        top.put(tokenText, (double) probabilities[token]);
        tops.add(top);
      }
      if (echo && !all.isEmpty()) {
        if (tokenProbabilities.isEmpty())
          throw error(PythonErrorType.INDEX_ERROR, "list assignment index out of range");
        tokenProbabilities.set(0, null);
        tops.set(0, null);
      }
      detail =
          dict(
              "tokens",
              tokens,
              "text_offset",
              offsets,
              "token_logprobs",
              tokenProbabilities,
              "top_logprobs",
              tops);
    }
    Map<String, Object> result = chunk(value, detail, reason);
    result.put(
        "usage",
        dict(
            "prompt_tokens",
            promptTokens.size(),
            "completion_tokens",
            completionTokens.size(),
            "total_tokens",
            promptTokens.size() + completionTokens.size()));
    return result;
  }

  private Map<String, Object> top(float[] probabilities, List<Integer> previous) {
    List<Integer> indices = new ArrayList<>();
    for (int i = 0; i < probabilities.length; i++) indices.add(i);
    indices.sort(
        (a, b) -> {
          float x = probabilities[a], y = probabilities[b];
          return x < y ? 1 : x > y ? -1 : x == y ? Integer.compare(b, a) : 0;
        });
    BigInteger stop;
    try {
      stop = PythonNumbers.index(logprobs);
    } catch (PythonException failure) {
      throw error(
          PythonErrorType.TYPE_ERROR,
          "slice indices must be integers or None or have an __index__ method");
    }
    int end =
        stop.signum() < 0
            ? stop.add(BigInteger.valueOf(indices.size())).max(BigInteger.ZERO).intValue()
            : stop.min(BigInteger.valueOf(indices.size())).intValue();
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < end; i++) {
      int token = indices.get(i);
      result.put(decode(detok(List.of(token), previous)), (double) probabilities[token]);
    }
    return result;
  }

  private void cacheSave(boolean stream) {
    if (!Py.truth(llama.cache())) return;
    if (verbose) llama.diagnostic("Llama._create_completion: cache save");
    Py.setItem(llama.cache(), concat(promptTokens, completionTokens), llama.saveState());
    if (stream && verbose) llama.diagnostic("Llama._create_completion: cache saved");
  }

  private Map<String, Object> chunk(String value, Object logprobs, Object finish) {
    return dict(
        "id",
        id,
        "object",
        "text_completion",
        "created",
        created,
        "model",
        modelName,
        "choices",
        List.of(dict("text", value, "index", 0, "logprobs", logprobs, "finish_reason", finish)));
  }

  private byte[] detok(List<Integer> tokens, List<Integer> previous) {
    return llama.detokenize(array(tokens), previous == null ? null : array(previous), false);
  }

  private static int common(int[] a, List<Integer> b) {
    int i = 0;
    while (i < Math.min(a.length, b.size()) && a[i] == b.get(i)) i++;
    return i;
  }

  private static int[] array(List<Integer> values) {
    return values.stream().mapToInt(Integer::intValue).toArray();
  }

  private static List<Integer> ints(int[] values) {
    return new ArrayList<>(Arrays.stream(values).boxed().toList());
  }

  @SafeVarargs
  private static List<Integer> concat(List<Integer>... lists) {
    List<Integer> result = new ArrayList<>();
    for (var list : lists) result.addAll(list);
    return result;
  }

  private static byte[] prefix(byte[] values, int end) {
    int limit = end < 0 ? Math.max(0, values.length + end) : Math.min(values.length, end);
    return Arrays.copyOf(values, limit);
  }

  private static int indexOf(byte[] values, byte[] target) {
    outer:
    for (int i = 0; i <= values.length - target.length; i++) {
      for (int j = 0; j < target.length; j++) if (values[i + j] != target[j]) continue outer;
      return i;
    }
    return -1;
  }

  private static boolean endsWith(byte[] values, byte[] suffix) {
    if (suffix.length > values.length) return false;
    for (int i = 0; i < suffix.length; i++)
      if (values[values.length - suffix.length + i] != suffix[i]) return false;
    return true;
  }

  private static String decode(byte[] values) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.IGNORE)
          .onUnmappableCharacter(CodingErrorAction.IGNORE)
          .decode(ByteBuffer.wrap(values))
          .toString();
    } catch (CharacterCodingException failure) {
      throw new AssertionError(failure);
    }
  }

  private static String decodeStrict(byte[] values) {
    try {
      return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(values)).toString();
    } catch (CharacterCodingException failure) {
      return null;
    }
  }
}

package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.compat.python.PythonErrorBridge.error;

import io.github.yaulun.javacpp.python.PythonBytes;
import io.github.yaulun.javacpp.python.PythonErrorType;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** _internals.LlamaModel: parameter passthrough, ValueError contract and buffer conventions. */
public final class PythonLlamaModel {
  private final PythonInternalsPort port;
  private final String pathModel;
  private final Object params;
  private final boolean verbose;
  private final PythonExitStack exitStack = new PythonExitStack();
  private Object sampler;
  private final List<Object[]> customSamplers = new ArrayList<>();
  private Object model;
  private Object vocab;

  /** @param params the caller-owned llama_model_params value, passed through unchanged. */
  public PythonLlamaModel(
      PythonInternalsPort port, String pathModel, Object params, boolean verbose) {
    this.port = Objects.requireNonNull(port, "port");
    this.pathModel = pathModel;
    this.params = params;
    this.verbose = verbose;
    this.sampler = null;
    if (!port.exists(pathModel)) {
      throw error(PythonErrorType.VALUE_ERROR, "Model path does not exist: " + pathModel);
    }
    Object loaded = port.modelLoadFromFile(pathModel, params);
    if (loaded == null) {
      throw error(PythonErrorType.VALUE_ERROR, "Failed to load model from file: " + pathModel);
    }
    Object vocabulary = port.modelGetVocab(loaded);
    if (vocabulary == null) {
      throw error(PythonErrorType.VALUE_ERROR, "Failed to get vocab from model: " + pathModel);
    }
    this.model = loaded;
    this.vocab = vocabulary;
    exitStack.callback(this::freeModel);
  }

  /** A failed construction leaves no freeable state, matching the ExitStack ordering. */
  private void freeModel() {
    if (model == null) {
      return;
    }
    port.modelFree(model);
    model = null;
  }

  public String pathModel() {
    return pathModel;
  }

  public Object params() {
    return params;
  }

  public boolean verbose() {
    return verbose;
  }

  public Object model() {
    return model;
  }

  public Object vocab() {
    return vocab;
  }

  public Object sampler() {
    return sampler;
  }

  /** The original attribute is written externally by the Llama owner. */
  public void setSampler(Object sampler) {
    this.sampler = sampler;
  }

  /** Entries are (chain index, custom sampler) pairs appended by add_custom callers. */
  public List<Object[]> customSamplers() {
    return customSamplers;
  }

  /** Registered context close callbacks run before free_model via LIFO order. */
  public PythonExitStack exitStack() {
    return exitStack;
  }

  /** Custom samplers leave the chain in reverse before the exit stack frees the model. */
  public void close() {
    if (sampler != null) {
      for (int i = customSamplers.size() - 1; i >= 0; i--) {
        port.samplerChainRemove(sampler, ((Number) customSamplers.get(i)[0]).intValue());
      }
      customSamplers.clear();
    }
    exitStack.close();
  }

  /** Fixed 1024 byte buffer; the returned text stops at the first NUL. */
  public String desc() {
    byte[] buffer = new byte[1024];
    port.modelDesc(model, buffer);
    return cString(buffer);
  }

  private static String cString(byte[] buffer) {
    int end = 0;
    while (end < buffer.length && buffer[end] != 0) {
      end++;
    }
    return new String(buffer, 0, end, StandardCharsets.UTF_8);
  }

  /** First pass sizes by n_ctx_train, retrying once with the reported count. */
  public List<Integer> tokenize(byte[] text, boolean addBos, boolean special) {
    int nCtx = port.modelNCtxTrain(model);
    int[] tokens = new int[nCtx];
    int nTokens = port.tokenize(vocab, text, tokens, nCtx, addBos, special);
    if (nTokens < 0) {
      nTokens = -nTokens;
      tokens = new int[nTokens];
      nTokens = port.tokenize(vocab, text, tokens, nTokens, addBos, special);
      if (nTokens < 0) {
        throw error(
            PythonErrorType.RUNTIME_ERROR,
            "Failed to tokenize: text=\"" + PythonBytes.repr(text) + "\" n_tokens=" + nTokens);
      }
    }
    List<Integer> result = new ArrayList<>(nTokens);
    for (int i = 0; i < nTokens; i++) {
      result.add(tokens[i]);
    }
    return result;
  }

  /** Always returns the full 32 byte buffer, as bytes(buf) does in the original. */
  public byte[] tokenToPiece(int token, boolean special) {
    byte[] buffer = new byte[32];
    port.tokenToPiece(vocab, token, buffer, 0, special);
    return buffer;
  }

  /** Concatenates per-token pieces, then drops the leading space after a BOS token. */
  public byte[] detokenize(List<Integer> tokens, boolean special) {
    int size = 32;
    byte[] buffer = new byte[size];
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (int token : tokens) {
      int n = port.tokenToPiece(vocab, token, buffer, 0, special);
      if (n > size) {
        throw error(PythonErrorType.ASSERTION_ERROR, "");
      }
      int count = n < 0 ? Math.max(0, size + n) : n;
      output.write(buffer, 0, count);
    }
    byte[] bytes = output.toByteArray();
    if (!tokens.isEmpty() && tokens.get(0) == port.vocabBos(vocab) && bytes.length > 0
        && bytes[0] == ' ') {
      return Arrays.copyOfRange(bytes, 1, bytes.length);
    }
    return bytes;
  }

  /** Mutable insertion-ordered map; the shared buffer grows past long keys and values. */
  public Map<String, String> metadata() {
    Map<String, String> metadata = new LinkedHashMap<>();
    int bufferSize = 1024;
    byte[] buffer = new byte[bufferSize];
    int count = port.modelMetaCount(model);
    for (int i = 0; i < count; i++) {
      int nbytes = port.modelMetaKeyByIndex(model, i, buffer);
      if (nbytes > bufferSize) {
        bufferSize = nbytes + 1;
        buffer = new byte[bufferSize];
        port.modelMetaKeyByIndex(model, i, buffer);
      }
      String key = cString(buffer);
      nbytes = port.modelMetaValStrByIndex(model, i, buffer);
      if (nbytes > bufferSize) {
        bufferSize = nbytes + 1;
        buffer = new byte[bufferSize];
        port.modelMetaValStrByIndex(model, i, buffer);
      }
      metadata.put(key, cString(buffer));
    }
    return metadata;
  }

  public static Object defaultParams(PythonInternalsPort port) {
    return port.modelDefaultParams();
  }
}

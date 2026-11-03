package io.github.yaulun.javacpp.internal;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * llama-ext.h experimental entry points. Mirrors llama_cpp_ext._ctypes_function_from_names: each
 * function resolves by trying the plain, Itanium-mangled and MSVC-mangled candidates in order.
 */
public final class LlamaExtBindings {
  private static final List<String> SET_EMBEDDINGS_NEXTN =
      List.of(
          "llama_set_embeddings_nextn",
          "_Z26llama_set_embeddings_nextnP13llama_contextbb",
          "?llama_set_embeddings_nextn@@YAXPEAUllama_context@@_N1@Z");
  private static final List<String> SET_NEXTN_LAYER_OFFSET =
      List.of(
          "llama_set_nextn_layer_offset",
          "_Z28llama_set_nextn_layer_offsetP13llama_contexti",
          "?llama_set_nextn_layer_offset@@YAXPEAUllama_context@@H@Z");
  private static final List<String> GET_EMBEDDINGS_NEXTN =
      List.of(
          "llama_get_embeddings_nextn",
          "_Z26llama_get_embeddings_nextnP13llama_context",
          "?llama_get_embeddings_nextn@@YAPEAMPEAUllama_context@@@Z");
  private static final List<String> GET_EMBEDDINGS_NEXTN_ITH =
      List.of(
          "llama_get_embeddings_nextn_ith",
          "_Z30llama_get_embeddings_nextn_ithP13llama_contexti",
          "?llama_get_embeddings_nextn_ith@@YAPEAMPEAUllama_context@@H@Z");
  private static final List<String> GET_CTX_OTHER =
      List.of(
          "llama_get_ctx_other",
          "_Z19llama_get_ctx_otherP13llama_context",
          "?llama_get_ctx_other@@YAPEAUllama_context@@PEAU1@@Z");

  private static final Map<String, List<String>> CANDIDATES =
      Map.of(
          "llama_set_embeddings_nextn", SET_EMBEDDINGS_NEXTN,
          "llama_set_nextn_layer_offset", SET_NEXTN_LAYER_OFFSET,
          "llama_get_embeddings_nextn", GET_EMBEDDINGS_NEXTN,
          "llama_get_embeddings_nextn_ith", GET_EMBEDDINGS_NEXTN_ITH,
          "llama_get_ctx_other", GET_CTX_OTHER);

  private final Map<String, Function> functions;

  private LlamaExtBindings(Map<String, Function> functions) {
    this.functions = functions;
  }

  /** Resolves every entry point against the loaded llama library; unresolved names stay absent. */
  public static LlamaExtBindings load(NativeLibrary llama) {
    Map<String, Function> resolved = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : CANDIDATES.entrySet()) {
      for (String name : entry.getValue()) {
        try {
          resolved.put(entry.getKey(), llama.getFunction(name));
          break;
        } catch (UnsatisfiedLinkError missing) {
          // try the next decorated candidate like _ctypes_function_from_names
        }
      }
    }
    return new LlamaExtBindings(resolved);
  }

  /** Whether the given entry point resolved on the loaded library. */
  public boolean available(String name) {
    return functions.containsKey(name);
  }

  private Function require(String name) {
    Function function = functions.get(name);
    if (function == null) {
      throw new UnsatisfiedLinkError(
          "None of the shared library symbols were found: "
              + String.join(", ", CANDIDATES.get(name)));
    }
    return function;
  }

  public void llama_set_embeddings_nextn(Pointer ctx, byte value, byte masked) {
    require("llama_set_embeddings_nextn").invoke(new Object[] {ctx, value, masked});
  }

  public void llama_set_nextn_layer_offset(Pointer ctx, int offset) {
    require("llama_set_nextn_layer_offset").invoke(new Object[] {ctx, offset});
  }

  public Pointer llama_get_embeddings_nextn(Pointer ctx) {
    return (Pointer)
        require("llama_get_embeddings_nextn").invoke(Pointer.class, new Object[] {ctx});
  }

  public Pointer llama_get_embeddings_nextn_ith(Pointer ctx, int i) {
    return (Pointer)
        require("llama_get_embeddings_nextn_ith").invoke(Pointer.class, new Object[] {ctx, i});
  }

  public Pointer llama_get_ctx_other(Pointer ctx) {
    return (Pointer) require("llama_get_ctx_other").invoke(Pointer.class, new Object[] {ctx});
  }
}

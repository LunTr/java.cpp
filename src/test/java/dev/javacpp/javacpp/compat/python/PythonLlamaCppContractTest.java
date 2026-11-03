package dev.javacpp.compat.python;

import static org.junit.jupiter.api.Assertions.*;

import dev.javacpp.python.PythonErrorType;
import dev.javacpp.python.PythonException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

class PythonLlamaCppContractTest {
  @TestFactory
  Stream<DynamicTest> matchesOriginalBodies() throws Exception {
    List<String> lines;
    try (var input =
        Objects.requireNonNull(
            getClass().getResourceAsStream("/python-0.3.36-llamacpp-contracts.tsv"),
            "Run generate_llamacpp_contracts.py")) {
      lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).lines().toList();
    }
    return IntStream.range(0, lines.size())
        .mapToObj(
            i ->
                DynamicTest.dynamicTest(
                    "llamacpp:" + i,
                    () -> {
                      String[] parts = lines.get(i).split("\t");
                      Object result = drive(map(read(parts[1])));
                      ByteArrayOutputStream out = new ByteArrayOutputStream();
                      PythonContractData.write(new DataOutputStream(out), result);
                      String actual = Base64.getEncoder().encodeToString(out.toByteArray());
                      if (!parts[2].equals(actual)) {
                        java.nio.file.Files.write(
                            java.nio.file.Path.of(".verify/llamacpp-" + i + ".b64"),
                            actual.getBytes());
                        fail("llamacpp " + i + " differs");
                      }
                    }));
  }

  private static Object read(String v) throws Exception {
    return PythonContractData.read(
        new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(v))));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object v) {
    return (Map<String, Object>) v;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object v) {
    return (List<Object>) v;
  }

  private static Object delegate(
      List<Object> trace, Map<String, Object> c, String alias, Object... args) {
    List<Object> entry = new ArrayList<>();
    entry.add(alias);
    entry.addAll(Arrays.asList(args));
    trace.add(entry);
    if (c.get("delegate_error") != null) {
      throw new PythonException(
          PythonErrorType.RUNTIME_ERROR, (String) c.get("delegate_error"), null);
    }
    return c.get("delegate_result");
  }

  private static Object drive(Map<String, Object> c) {
    List<Object> trace = new ArrayList<>();
    List<PythonWarning> caught = new ArrayList<>();
    PythonWarnings warnings =
        PythonWarnings.withAction(
            PythonWarningAction.valueOf(
                ((String) c.getOrDefault("action", "always")).toUpperCase()),
            caught::add);
    PythonLlamaCpp.LlamaCppAccess access =
        new PythonLlamaCpp.LlamaCppAccess() {
          public Object loadModelFromFile(Object pathModel, Object params) {
            return delegate(trace, c, "_llama_load_model_from_file", pathModel, params);
          }

          public Object freeModel(Object model) {
            return delegate(trace, c, "_llama_free_model", model);
          }

          public Object newContextWithModel(Object model, Object params) {
            return delegate(trace, c, "_llama_new_context_with_model", model, params);
          }

          public Object nCtxTrain(Object model) {
            return delegate(trace, c, "_llama_n_ctx_train", model);
          }

          public Object nEmbd(Object model) {
            return delegate(trace, c, "_llama_n_embd", model);
          }

          public Object nLayer(Object model) {
            return delegate(trace, c, "_llama_n_layer", model);
          }

          public Object nHead(Object model) {
            return delegate(trace, c, "_llama_n_head", model);
          }

          public Object nVocab(Object vocab) {
            return delegate(trace, c, "_llama_n_vocab", vocab);
          }

          public Object getStateSize(Object ctx) {
            return delegate(trace, c, "_llama_get_state_size", ctx);
          }

          public Object copyStateData(Object ctx, Object dst) {
            return delegate(trace, c, "_llama_copy_state_data", ctx, dst);
          }

          public Object setStateData(Object ctx, Object src) {
            return delegate(trace, c, "_llama_set_state_data", ctx, src);
          }

          public Object setAdaptersLora(
              Object ctx, Object adapters, Object nAdapters, Object scales) {
            return delegate(trace, c, "llama_set_adapters_lora", ctx, adapters, nAdapters, scales);
          }

          public Object setAdapterCvec(
              Object ctx, Object data, Object len, Object nEmbd, Object ilStart, Object ilEnd) {
            return delegate(
                trace, c, "llama_set_adapter_cvec", ctx, data, len, nEmbd, ilStart, ilEnd);
          }

          public Object loadSessionFile(
              Object ctx,
              Object pathSession,
              Object tokensOut,
              Object nTokenCapacity,
              Object nTokenCountOut) {
            return delegate(
                trace,
                c,
                "_llama_load_session_file",
                ctx,
                pathSession,
                tokensOut,
                nTokenCapacity,
                nTokenCountOut);
          }

          public Object saveSessionFile(
              Object ctx, Object pathSession, Object tokens, Object nTokenCount) {
            return delegate(
                trace, c, "_llama_save_session_file", ctx, pathSession, tokens, nTokenCount);
          }

          public Object tokenGetText(Object vocab, Object token) {
            return delegate(trace, c, "_llama_token_get_text", vocab, token);
          }

          public Object tokenGetScore(Object vocab, Object token) {
            return delegate(trace, c, "_llama_token_get_score", vocab, token);
          }

          public Object tokenGetAttr(Object vocab, Object token) {
            return delegate(trace, c, "_llama_token_get_attr", vocab, token);
          }

          public Object tokenIsEog(Object vocab, Object token) {
            return delegate(trace, c, "_llama_token_is_eog", vocab, token);
          }

          public Object tokenIsControl(Object vocab, Object token) {
            return delegate(trace, c, "_llama_token_is_control", vocab, token);
          }

          public Object tokenBos(Object vocab) {
            return delegate(trace, c, "_llama_token_bos", vocab);
          }

          public Object tokenEos(Object vocab) {
            return delegate(trace, c, "_llama_token_eos", vocab);
          }

          public Object tokenEot(Object vocab) {
            return delegate(trace, c, "_llama_token_eot", vocab);
          }

          public Object tokenCls(Object vocab) {
            return delegate(trace, c, "_llama_token_cls", vocab);
          }

          public Object tokenSep(Object vocab) {
            return delegate(trace, c, "_llama_token_sep", vocab);
          }

          public Object tokenNl(Object vocab) {
            return delegate(trace, c, "_llama_token_nl", vocab);
          }

          public Object tokenPad(Object vocab) {
            return delegate(trace, c, "_llama_token_pad", vocab);
          }

          public Object addBosToken(Object vocab) {
            return delegate(trace, c, "_llama_add_bos_token", vocab);
          }

          public Object addEosToken(Object vocab) {
            return delegate(trace, c, "_llama_add_eos_token", vocab);
          }

          public Object tokenFimPre(Object vocab) {
            return delegate(trace, c, "_llama_token_fim_pre", vocab);
          }

          public Object tokenFimSuf(Object vocab) {
            return delegate(trace, c, "_llama_token_fim_suf", vocab);
          }

          public Object tokenFimMid(Object vocab) {
            return delegate(trace, c, "_llama_token_fim_mid", vocab);
          }

          public Object tokenFimPad(Object vocab) {
            return delegate(trace, c, "_llama_token_fim_pad", vocab);
          }

          public Object tokenFimRep(Object vocab) {
            return delegate(trace, c, "_llama_token_fim_rep", vocab);
          }

          public Object tokenFimSep(Object vocab) {
            return delegate(trace, c, "_llama_token_fim_sep", vocab);
          }

          public Object samplerInitGrammarLazy(
              Object vocab,
              Object grammarStr,
              Object grammarRoot,
              Object triggerWords,
              Object numTriggerWords,
              Object triggerTokens,
              Object numTriggerTokens) {
            return delegate(
                trace,
                c,
                "_llama_sampler_init_grammar_lazy",
                vocab,
                grammarStr,
                grammarRoot,
                triggerWords,
                numTriggerWords,
                triggerTokens,
                numTriggerTokens);
          }
        };
    PythonLlamaCpp cpp = new PythonLlamaCpp(access, warnings);
    PythonWarningLocation location =
        new PythonWarningLocation("pinned-llamacpp", 1, "pinned-llamacpp", null);
    String func = (String) c.get("func");

    List<Object> results = new ArrayList<>();
    for (Object stepObj : list(c.get("ops"))) {
      List<Object> step = list(stepObj);
      caught.clear();
      try {
        results.add(
            switch (func) {
              case "llama_load_model_from_file" ->
                  cpp.loadModelFromFile(step.get(0), step.get(1), location);
              case "llama_free_model" -> cpp.freeModel(step.get(0), location);
              case "llama_new_context_with_model" ->
                  cpp.newContextWithModel(step.get(0), step.get(1), location);
              case "llama_n_ctx_train" -> cpp.nCtxTrain(step.get(0), location);
              case "llama_n_embd" -> cpp.nEmbd(step.get(0), location);
              case "llama_n_layer" -> cpp.nLayer(step.get(0), location);
              case "llama_n_head" -> cpp.nHead(step.get(0), location);
              case "llama_n_vocab" -> cpp.nVocab(step.get(0), location);
              case "llama_get_state_size" -> cpp.getStateSize(step.get(0), location);
              case "llama_copy_state_data" -> cpp.copyStateData(step.get(0), step.get(1), location);
              case "llama_set_state_data" -> cpp.setStateData(step.get(0), step.get(1), location);
              case "llama_set_adapter_lora" ->
                  cpp.setAdapterLora(step.get(0), step.get(1), step.get(2), location);
              case "llama_apply_adapter_cvec" ->
                  cpp.applyAdapterCvec(
                      step.get(0),
                      step.get(1),
                      step.get(2),
                      step.get(3),
                      step.get(4),
                      step.get(5),
                      location);
              case "llama_load_session_file" ->
                  cpp.loadSessionFile(
                      step.get(0), step.get(1), step.get(2), step.get(3), step.get(4), location);
              case "llama_save_session_file" ->
                  cpp.saveSessionFile(step.get(0), step.get(1), step.get(2), step.get(3), location);
              case "llama_token_get_text" -> cpp.tokenGetText(step.get(0), step.get(1), location);
              case "llama_token_get_score" -> cpp.tokenGetScore(step.get(0), step.get(1), location);
              case "llama_token_get_attr" -> cpp.tokenGetAttr(step.get(0), step.get(1), location);
              case "llama_token_is_eog" -> cpp.tokenIsEog(step.get(0), step.get(1), location);
              case "llama_token_is_control" ->
                  cpp.tokenIsControl(step.get(0), step.get(1), location);
              case "llama_token_bos" -> cpp.tokenBos(step.get(0), location);
              case "llama_token_eos" -> cpp.tokenEos(step.get(0), location);
              case "llama_token_eot" -> cpp.tokenEot(step.get(0), location);
              case "llama_token_cls" -> cpp.tokenCls(step.get(0), location);
              case "llama_token_sep" -> cpp.tokenSep(step.get(0), location);
              case "llama_token_nl" -> cpp.tokenNl(step.get(0), location);
              case "llama_token_pad" -> cpp.tokenPad(step.get(0), location);
              case "llama_add_bos_token" -> cpp.addBosToken(step.get(0), location);
              case "llama_add_eos_token" -> cpp.addEosToken(step.get(0), location);
              case "llama_token_fim_pre" -> cpp.tokenFimPre(step.get(0), location);
              case "llama_token_fim_suf" -> cpp.tokenFimSuf(step.get(0), location);
              case "llama_token_fim_mid" -> cpp.tokenFimMid(step.get(0), location);
              case "llama_token_fim_pad" -> cpp.tokenFimPad(step.get(0), location);
              case "llama_token_fim_rep" -> cpp.tokenFimRep(step.get(0), location);
              case "llama_token_fim_sep" -> cpp.tokenFimSep(step.get(0), location);
              case "llama_sampler_init_grammar_lazy" ->
                  cpp.samplerInitGrammarLazy(
                      step.get(0),
                      step.get(1),
                      step.get(2),
                      step.get(3),
                      step.get(4),
                      step.get(5),
                      step.get(6),
                      location);
              default -> throw new AssertionError("unknown func " + func);
            });
      } catch (PythonException e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", e.type().pythonName());
        error.put("message", e.getMessage());
        results.add(error);
      }
      for (PythonWarning w : caught) {
        trace.add(List.of("warn", w.category().pythonName(), w.message()));
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("results", results);
    out.put("trace", trace);
    return out;
  }
}

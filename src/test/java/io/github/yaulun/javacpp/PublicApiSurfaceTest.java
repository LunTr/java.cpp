package io.github.yaulun.javacpp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.yaulun.javacpp.compat.python.PythonCall;
import io.github.yaulun.javacpp.compat.python.PythonChatCompletions;
import io.github.yaulun.javacpp.compat.python.PythonChatFormats;
import io.github.yaulun.javacpp.compat.python.PythonChatFormatterResponse;
import io.github.yaulun.javacpp.compat.python.PythonErrorBridge;
import io.github.yaulun.javacpp.compat.python.PythonErrorType;
import io.github.yaulun.javacpp.compat.python.PythonException;
import io.github.yaulun.javacpp.compat.python.PythonLlama;
import io.github.yaulun.javacpp.compat.python.PythonLlamaExecution;
import io.github.yaulun.javacpp.compat.python.PythonLlamaRamCache;
import io.github.yaulun.javacpp.compat.python.PythonLlamaSampler;
import io.github.yaulun.javacpp.compat.python.PythonLlamaState;
import io.github.yaulun.javacpp.compat.python.PythonLlamaTokenizer;
import io.github.yaulun.javacpp.compat.python.PythonLogitsProcessor;
import io.github.yaulun.javacpp.compat.python.PythonLogitsProcessorList;
import io.github.yaulun.javacpp.compat.python.PythonMinTokensLogitsProcessor;
import io.github.yaulun.javacpp.compat.python.PythonStoppingCriteria;
import io.github.yaulun.javacpp.compat.python.PythonStoppingCriteriaList;
import io.github.yaulun.javacpp.compat.python.PythonTokenizer;
import io.github.yaulun.javacpp.compat.python.PythonTokenizerModel;
import io.github.yaulun.javacpp.compat.python.PythonTokens;
import io.github.yaulun.javacpp.compat.python.PythonWarning;
import io.github.yaulun.javacpp.compat.python.PythonWarningAction;
import io.github.yaulun.javacpp.compat.python.PythonWarningLocation;
import io.github.yaulun.javacpp.compat.python.PythonWarnings;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicApiSurfaceTest {
  @Test
  void publicFacadeHidesNativeTypesAndMutableState() throws Exception {
    for (Class<?> type :
        List.of(
            PythonCall.class,
            PythonChatCompletions.class,
            PythonChatFormats.class,
            PythonChatFormats.RoleMessage.class,
            PythonChatFormatterResponse.class,
            PythonErrorBridge.class,
            PythonErrorType.class,
            PythonException.class,
            PythonLlama.class,
            PythonLlama.TokenGenerator.class,
            io.github.yaulun.javacpp.compat.python.PythonSamplerBuilder.class,
            io.github.yaulun.javacpp.compat.python.PythonGrammar.class,
            io.github.yaulun.javacpp.compat.python.PythonCompletionModel.class,
            io.github.yaulun.javacpp.compat.python.PythonEmbeddingBackend.class,
            io.github.yaulun.javacpp.compat.python.PythonLlamaServices.class,
            io.github.yaulun.javacpp.compat.python.PythonLlamaServices.OpenAiTypes.class,
            io.github.yaulun.javacpp.compat.python.PythonLlamaConfiguration.class,
            io.github.yaulun.javacpp.compat.python.PythonLlamaResource.class,
            io.github.yaulun.javacpp.compat.python.PythonPretrained.class,
            io.github.yaulun.javacpp.compat.python.PythonPretrained.Repository.class,
            PythonLlamaExecution.class,
            PythonLlamaSampler.class,
            PythonLlamaTokenizer.class,
            PythonTokenizer.class,
            PythonTokenizerModel.class,
            PythonLlamaRamCache.class,
            io.github.yaulun.javacpp.compat.python.PythonLlamaCache.class,
            io.github.yaulun.javacpp.compat.python.PythonLlamaDiskCache.class,
            io.github.yaulun.javacpp.compat.python.PythonLlamaDiskCache.Store.class,
            io.github.yaulun.javacpp.compat.python.PythonLlamaDiskCache.Factory.class,
            PythonLlamaState.class,
            PythonLogitsProcessor.class,
            PythonLogitsProcessorList.class,
            PythonMinTokensLogitsProcessor.class,
            PythonStoppingCriteria.class,
            PythonStoppingCriteriaList.class,
            PythonTokens.class,
            PythonWarning.class,
            PythonWarningAction.class,
            PythonWarningLocation.class,
            PythonWarnings.class)) {
      checkType(type);
    }
    Path source = Path.of("src/Java_Cpp/io/github/yaulun/javacpp");
    List<Path> files;
    try (var listing = Files.list(source)) {
      files = listing.filter(path -> path.toString().endsWith(".java")).toList();
    }
    for (Path file : files) {
      String name = file.getFileName().toString().replace(".java", "");
      Class<?> type =
          Class.forName("io.github.yaulun.javacpp." + name, false, getClass().getClassLoader());
      if (!Modifier.isPublic(type.getModifiers())) {
        continue;
      }
      checkType(type);
      for (Class<?> child : type.getDeclaredClasses()) {
        if (Modifier.isPublic(child.getModifiers())) {
          checkType(child);
        }
      }
    }
  }

  private static void checkType(Class<?> type) {
    for (Field field : type.getDeclaredFields()) {
      if (!field.isSynthetic() && !field.isEnumConstant()) {
        assertTrue(
            Modifier.isPrivate(field.getModifiers())
                || (Modifier.isStatic(field.getModifiers())
                    && Modifier.isFinal(field.getModifiers())),
            field.toString());
      }
    }
    for (Method method : type.getDeclaredMethods()) {
      if (Modifier.isPublic(method.getModifiers())) {
        assertFalse(
            method.getGenericReturnType().getTypeName().contains("com.sun.jna"), method.toString());
        for (var parameter : method.getGenericParameterTypes()) {
          assertFalse(parameter.getTypeName().contains("com.sun.jna"), method.toString());
        }
      }
    }
    for (var constructor : type.getConstructors()) {
      for (var parameter : constructor.getGenericParameterTypes()) {
        assertFalse(parameter.getTypeName().contains("com.sun.jna"), constructor.toString());
      }
    }
  }
}

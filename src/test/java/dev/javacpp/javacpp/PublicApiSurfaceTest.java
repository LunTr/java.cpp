package dev.javacpp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javacpp.compat.python.PythonCall;
import dev.javacpp.compat.python.PythonChatCompletions;
import dev.javacpp.compat.python.PythonChatFormats;
import dev.javacpp.compat.python.PythonChatFormatterResponse;
import dev.javacpp.compat.python.PythonErrorBridge;
import dev.javacpp.compat.python.PythonLlama;
import dev.javacpp.compat.python.PythonLlamaExecution;
import dev.javacpp.compat.python.PythonLlamaRamCache;
import dev.javacpp.compat.python.PythonLlamaSampler;
import dev.javacpp.compat.python.PythonLlamaState;
import dev.javacpp.compat.python.PythonLlamaTokenizer;
import dev.javacpp.compat.python.PythonLogitsProcessor;
import dev.javacpp.compat.python.PythonLogitsProcessorList;
import dev.javacpp.compat.python.PythonMinTokensLogitsProcessor;
import dev.javacpp.compat.python.PythonStoppingCriteria;
import dev.javacpp.compat.python.PythonStoppingCriteriaList;
import dev.javacpp.compat.python.PythonTokenizer;
import dev.javacpp.compat.python.PythonTokenizerModel;
import dev.javacpp.compat.python.PythonTokens;
import dev.javacpp.compat.python.PythonWarning;
import dev.javacpp.compat.python.PythonWarningAction;
import dev.javacpp.compat.python.PythonWarningLocation;
import dev.javacpp.compat.python.PythonWarnings;
import dev.javacpp.python.PythonErrorType;
import dev.javacpp.python.PythonException;
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
            dev.javacpp.compat.python.PythonSamplerBuilder.class,
            dev.javacpp.compat.python.PythonGrammar.class,
            dev.javacpp.compat.python.PythonCompletionModel.class,
            dev.javacpp.compat.python.PythonEmbeddingBackend.class,
            dev.javacpp.compat.python.PythonLlamaServices.class,
            dev.javacpp.compat.python.PythonLlamaServices.OpenAiTypes.class,
            dev.javacpp.compat.python.PythonLlamaConfiguration.class,
            dev.javacpp.compat.python.PythonLlamaResource.class,
            dev.javacpp.compat.python.PythonPretrained.class,
            dev.javacpp.compat.python.PythonPretrained.Repository.class,
            PythonLlamaExecution.class,
            PythonLlamaSampler.class,
            PythonLlamaTokenizer.class,
            PythonTokenizer.class,
            PythonTokenizerModel.class,
            PythonLlamaRamCache.class,
            dev.javacpp.compat.python.PythonLlamaCache.class,
            dev.javacpp.compat.python.PythonLlamaDiskCache.class,
            dev.javacpp.compat.python.PythonLlamaDiskCache.Store.class,
            dev.javacpp.compat.python.PythonLlamaDiskCache.Factory.class,
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
    Path source = Path.of("src/Java_Cpp/dev/javacpp");
    List<Path> files;
    try (var listing = Files.list(source)) {
      files = listing.filter(path -> path.toString().endsWith(".java")).toList();
    }
    for (Path file : files) {
      String name = file.getFileName().toString().replace(".java", "");
      Class<?> type = Class.forName("dev.javacpp." + name, false, getClass().getClassLoader());
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

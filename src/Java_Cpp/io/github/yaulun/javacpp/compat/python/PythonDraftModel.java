package io.github.yaulun.javacpp.compat.python;

import io.github.yaulun.javacpp.python.Py;
import io.github.yaulun.javacpp.python.PythonErrorType;
import java.util.function.BiFunction;

/**
 * Ports llama_speculative.LlamaDraftModel: abstract __call__ raising NotImplementedError, with the
 * same ABCMeta instantiation rule as the other pinned abstract bases.
 */
public class PythonDraftModel implements BiFunction<int[], java.util.Map<String, Object>, int[]> {
  protected PythonDraftModel() {
    PythonAbstract.checkInstance(
        this,
        PythonDraftModel.class,
        pythonTypeName(),
        PythonAbstract.method("call", "__call__", int[].class, java.util.Map.class));
  }

  /** Python-facing class name used in the ABCMeta instantiation message. */
  protected String pythonTypeName() {
    return "LlamaDraftModel";
  }

  /** Original abstract method body: raise NotImplementedError. */
  public int[] call(int[] inputIds, java.util.Map<String, Object> kwargs) {
    throw Py.error(PythonErrorType.NOT_IMPLEMENTED_ERROR, "");
  }

  @Override
  public int[] apply(int[] inputIds, java.util.Map<String, Object> kwargs) {
    return call(inputIds, kwargs);
  }
}

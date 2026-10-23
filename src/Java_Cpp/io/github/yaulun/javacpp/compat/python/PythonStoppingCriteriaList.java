package io.github.yaulun.javacpp.compat.python;

import java.util.ArrayList;
import java.util.List;

/** Mutable Python StoppingCriteriaList; callbacks require caller synchronization. */
public final class PythonStoppingCriteriaList extends ArrayList<PythonStoppingCriteria> {
  /** Ports __call__: executes the entire comprehension before evaluating any collected results. */
  public boolean test(int[] inputIds, float[] logits) {
    List<Object> results = new ArrayList<>();
    for (int index = 0; index < size(); index++) {
      PythonStoppingCriteria criterion = get(index);
      if (criterion == null)
        throw PythonValues.error(PythonErrorType.TYPE_ERROR, "'NoneType' object is not callable");
      results.add(criterion.test(inputIds, logits));
    }
    for (Object result : results) if (PythonValues.truth(result)) return true;
    return false;
  }
}

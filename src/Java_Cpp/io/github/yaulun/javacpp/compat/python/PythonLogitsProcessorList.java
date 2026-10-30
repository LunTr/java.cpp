package io.github.yaulun.javacpp.compat.python;

import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonValues;
import java.util.ArrayList;

/** Mutable Python LogitsProcessorList; callbacks and mutation require caller synchronization. */
public final class PythonLogitsProcessorList extends ArrayList<PythonLogitsProcessor> {
  /** Ports __call__, visiting processors in list order and returning the final scores reference. */
  public float[] apply(int[] inputIds, float[] scores) {
    for (int index = 0; index < size(); index++) {
      PythonLogitsProcessor processor = get(index);
      if (processor == null)
        throw PythonValues.error(PythonErrorType.TYPE_ERROR, "'NoneType' object is not callable");
      scores = processor.apply(inputIds, scores);
    }
    return scores;
  }
}

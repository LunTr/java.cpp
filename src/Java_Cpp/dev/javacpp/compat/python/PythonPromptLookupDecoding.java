package dev.javacpp.compat.python;

import java.util.Arrays;
import java.util.Map;

/**
 * Ports LlamaPromptLookupDecoding: sliding-window ngram search descending from min(max_ngram_size,
 * input_length - 1) to 1, first match's continuation up to num_pred_tokens, or an empty intc array
 * when no ngram reoccurs.
 */
public final class PythonPromptLookupDecoding extends PythonDraftModel {
  private final int maxNgramSize;
  private final int numPredTokens;

  public PythonPromptLookupDecoding() {
    this(2, 10);
  }

  public PythonPromptLookupDecoding(int maxNgramSize, int numPredTokens) {
    this.maxNgramSize = maxNgramSize;
    this.numPredTokens = numPredTokens;
  }

  @Override
  protected String pythonTypeName() {
    return "LlamaPromptLookupDecoding";
  }

  public int maxNgramSize() {
    return maxNgramSize;
  }

  public int numPredTokens() {
    return numPredTokens;
  }

  public static int[] findCandidatePredTokens(int[] inputIds, int maxNgramSize, int numPredTokens) {
    int inputLength = inputIds.length;
    for (int ngramSize = Math.min(maxNgramSize, inputLength - 1); ngramSize > 0; ngramSize--) {
      int[] ngram = Arrays.copyOfRange(inputIds, inputLength - ngramSize, inputLength);
      for (int index = 0; index + ngramSize <= inputLength; index++) {
        boolean matches = true;
        for (int offset = 0; offset < ngramSize; offset++) {
          if (inputIds[index + offset] != ngram[offset]) {
            matches = false;
            break;
          }
        }
        if (!matches) continue;
        int start = index + ngramSize;
        int end = Math.min(start + numPredTokens, inputLength);
        if (start < end) {
          return Arrays.copyOfRange(inputIds, start, end);
        }
      }
    }
    return new int[0];
  }

  @Override
  public int[] call(int[] inputIds, Map<String, Object> kwargs) {
    return findCandidatePredTokens(inputIds, maxNgramSize, numPredTokens);
  }
}

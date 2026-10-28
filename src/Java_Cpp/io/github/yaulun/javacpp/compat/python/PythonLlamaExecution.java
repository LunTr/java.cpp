package io.github.yaulun.javacpp.compat.python;

import io.github.yaulun.javacpp.LlamaContext;

/** Execution port for Python state orchestration; native ownership stays with the caller. */
public interface PythonLlamaExecution {
  int batchSize();

  boolean removeSequence(int position);

  void decode(int[] tokens, int position, boolean logitsAll);

  float[][] logits(int rows, int columns);

  long stateSize();

  long copyState(byte[] target);

  long restoreState(byte[] source);

  /** Adapts an existing context without taking ownership. */
  static PythonLlamaExecution fromContext(LlamaContext context) {
    return new PythonLlamaExecution() {
      public int batchSize() {
        return context.batchSize();
      }

      public boolean removeSequence(int position) {
        return context.removeSequence(position);
      }

      public void decode(int[] tokens, int position, boolean logitsAll) {
        PythonErrorBridge.run(
            PythonCall.decode(), () -> context.evaluateAt(tokens, position, logitsAll));
      }

      public float[][] logits(int rows, int columns) {
        return context.outputLogits(rows);
      }

      public long stateSize() {
        return context.stateSize();
      }

      public long copyState(byte[] target) {
        return context.copyState(target);
      }

      public long restoreState(byte[] source) {
        return context.restoreState(source);
      }
    };
  }
}

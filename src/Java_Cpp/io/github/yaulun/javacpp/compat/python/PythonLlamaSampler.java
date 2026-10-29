package io.github.yaulun.javacpp.compat.python;

/** Sampler callback receiving the Python relative output index. */
@FunctionalInterface
public interface PythonLlamaSampler {
  int sample(int relativeIndex);

  default int sampleAt(Object relativeIndex) {
    return sample(PythonNumbers.cInt(relativeIndex, 3));
  }
}

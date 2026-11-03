package dev.javacpp.compat.python;

import dev.javacpp.python.Py;
import dev.javacpp.python.PythonErrorType;

/**
 * BaseLlamaTokenizer from the pinned source: abstract base whose two method bodies raise
 * NotImplementedError and whose subclasses fail instantiation until both are implemented.
 */
public class PythonBaseLlamaTokenizer implements PythonTokenizer {
  protected PythonBaseLlamaTokenizer() {
    PythonAbstract.checkInstance(
        this,
        PythonBaseLlamaTokenizer.class,
        pythonTypeName(),
        PythonAbstract.method("tokenize", "tokenize", byte[].class, boolean.class, boolean.class),
        PythonAbstract.method("detokenize", "detokenize", int[].class, int[].class, boolean.class));
  }

  /** Python-facing class name used in the ABCMeta instantiation message. */
  protected String pythonTypeName() {
    return "BaseLlamaTokenizer";
  }

  /** Original abstract method body: raise NotImplementedError. */
  @Override
  public int[] tokenize(byte[] text, boolean addBos, boolean special) {
    throw Py.error(PythonErrorType.NOT_IMPLEMENTED_ERROR, "");
  }

  /** Original abstract method body: raise NotImplementedError. */
  @Override
  public byte[] detokenize(int[] tokens, int[] previousTokens, boolean special) {
    throw Py.error(PythonErrorType.NOT_IMPLEMENTED_ERROR, "");
  }
}

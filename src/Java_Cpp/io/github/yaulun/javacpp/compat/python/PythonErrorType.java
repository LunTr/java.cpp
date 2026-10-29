package io.github.yaulun.javacpp.compat.python;

/** Python exception and warning categories supported by the 0.3.36 compatibility bridge. */
public enum PythonErrorType {
  VALUE_ERROR("ValueError", false),
  RUNTIME_ERROR("RuntimeError", false),
  ASSERTION_ERROR("AssertionError", false),
  KEY_ERROR("KeyError", false),
  TYPE_ERROR("TypeError", false),
  OVERFLOW_ERROR("OverflowError", false),
  ARGUMENT_ERROR("ArgumentError", false),
  TEMPLATE_SYNTAX_ERROR("TemplateSyntaxError", false),
  TEMPLATE_RUNTIME_ERROR("TemplateRuntimeError", false),
  UNDEFINED_ERROR("UndefinedError", false),
  INDEX_ERROR("IndexError", false),
  ATTRIBUTE_ERROR("AttributeError", false),
  IMPORT_ERROR("ImportError", false),
  STOP_ITERATION("StopIteration", false),
  UNICODE_ENCODE_ERROR("UnicodeEncodeError", false),
  RUNTIME_WARNING("RuntimeWarning", true),
  DEPRECATION_WARNING("DeprecationWarning", true);

  private final String pythonName;
  private final boolean warning;

  PythonErrorType(String pythonName, boolean warning) {
    this.pythonName = pythonName;
    this.warning = warning;
  }

  public String pythonName() {
    return pythonName;
  }

  public boolean isWarning() {
    return warning;
  }
}

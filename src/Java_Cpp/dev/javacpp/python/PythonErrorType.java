package dev.javacpp.python;

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
  FILE_NOT_FOUND_ERROR("FileNotFoundError", false),
  STOP_ITERATION("StopIteration", false),
  NOT_IMPLEMENTED_ERROR("NotImplementedError", false),
  UNICODE_DECODE_ERROR("UnicodeDecodeError", false),
  JSON_DECODE_ERROR("JSONDecodeError", false),
  LLAMA_CHAT_HANDLER_NOT_FOUND("LlamaChatCompletionHandlerNotFoundException", false),
  HTTP_EXCEPTION("HTTPException", false),
  UNICODE_ENCODE_ERROR("UnicodeEncodeError", false),
  MEMORY_ERROR("MemoryError", false),
  ZERO_DIVISION_ERROR("ZeroDivisionError", false),
  BASE_EXCEPTION("Exception", false),
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

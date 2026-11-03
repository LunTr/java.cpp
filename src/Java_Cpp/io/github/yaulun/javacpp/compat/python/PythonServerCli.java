package io.github.yaulun.javacpp.compat.python;

import io.github.yaulun.javacpp.python.Py;
import io.github.yaulun.javacpp.python.PythonBytes;
import io.github.yaulun.javacpp.python.PythonErrorType;
import io.github.yaulun.javacpp.python.PythonException;
import java.util.List;
import java.util.Map;

/**
 * Ports server/cli.py argparse helpers. Typing annotations arrive as an injectable port so the
 * Literal/Union/List/Dict shape checks replay deterministically; the parser and pydantic model are
 * ports too.
 */
public final class PythonServerCli {
  private PythonServerCli() {}

  /** Annotation port: origin marker plus positional args, mirroring typing internals. */
  public interface Annotation {
    /** One of: literal, union, list, dict, or null for unparameterized types. */
    String origin();

    List<Annotation> args();

    /** type(arg) of the first literal arg, or a plain type for terminals. */
    String baseType();
  }

  /** Parser port recording add_argument calls in order. */
  public interface ArgumentParser {
    void addArgument(String flag, String dest, Object nargs, Object type, Object help);
  }

  /** pydantic model port: field description/default/required and annotation, plus ctor. */
  public interface ModelField {
    String name();

    String description();

    boolean required();

    Object defaultValue();

    Annotation annotation();
  }

  public interface Model {
    List<ModelField> modelFields();

    Object construct(Map<String, Object> kwargs);
  }

  /** Ports _get_base_type; assertion failures map to Python AssertionError. */
  public static String getBaseType(Annotation annotation) {
    String origin = annotation.origin();
    if ("literal".equals(origin)) {
      assertArgs(annotation);
      return annotation.args().get(0).baseType();
    }
    if ("union".equals(origin)) {
      assertArgs(annotation);
      List<Annotation> nonOptional =
          annotation.args().stream().filter(a -> !"none".equals(a.origin())).toList();
      if (!nonOptional.isEmpty()) {
        return getBaseType(nonOptional.get(0));
      }
      return annotation.baseType();
    }
    if ("list".equals(origin)) {
      assertArgs(annotation);
      return getBaseType(annotation.args().get(0));
    }
    return annotation.baseType();
  }

  /** Ports _contains_list_type. */
  public static boolean containsListType(Annotation annotation) {
    if (annotation == null) return false;
    String origin = annotation.origin();
    if ("list".equals(origin)) return true;
    if ("literal".equals(origin) || "union".equals(origin)) {
      return annotation.args().stream().anyMatch(PythonServerCli::containsListType);
    }
    return false;
  }

  /** Ports _contains_dict_type. */
  public static boolean containsDictType(Annotation annotation) {
    if (annotation == null) return false;
    String origin = annotation.origin();
    if ("dict".equals(origin)) return true;
    if ("literal".equals(origin) || "union".equals(origin)) {
      return annotation.args().stream().anyMatch(PythonServerCli::containsDictType);
    }
    return false;
  }

  /** Ports _parse_bool_arg; bytes decode first, then lowercase/strip membership. */
  public static boolean parseBoolArg(Object arg) {
    if (arg instanceof byte[] bytes) {
      arg = PythonBytes.decodeUtf8(bytes);
    }
    String argStr = String.valueOf(arg).toLowerCase().strip();
    if (TRUE_VALUES.contains(argStr)) return true;
    if (FALSE_VALUES.contains(argStr)) return false;
    throw Py.error(PythonErrorType.VALUE_ERROR, "Invalid boolean argument: " + arg);
  }

  private static final java.util.Set<String> TRUE_VALUES =
      java.util.Set.of("1", "on", "t", "true", "y", "yes");
  private static final java.util.Set<String> FALSE_VALUES =
      java.util.Set.of("0", "off", "f", "false", "n", "no");

  /** Ports _parse_json_object_arg; bad JSON raises JSONDecodeError, non-dict raises ValueError. */
  public static Map<String, Object> parseJsonObjectArg(Object arg) {
    if (arg instanceof byte[] bytes) {
      arg = PythonBytes.decodeUtf8(bytes);
    }
    if (!(arg instanceof String)) {
      throw Py.error(
          PythonErrorType.TYPE_ERROR,
          "the JSON object must be str, bytes or bytearray, not " + Py.typeName(arg));
    }
    Object value = PythonJson.loads((String) arg);
    if (!(value instanceof Map)) {
      throw Py.error(PythonErrorType.VALUE_ERROR, "Invalid JSON object argument: " + arg);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) value;
    return map;
  }

  /** Ports add_args_from_model: fields iterate in definition order. */
  public static void addArgsFromModel(ArgumentParser parser, Model model) {
    for (ModelField field : model.modelFields()) {
      String description = field.description();
      if (description != null && !field.required() && field.defaultValue() != null) {
        description += " (default: " + field.defaultValue() + ")";
      }
      String baseType = field.annotation() != null ? getBaseType(field.annotation()) : "str";
      boolean listType = containsListType(field.annotation());
      boolean dictType = containsDictType(field.annotation());
      if (dictType) {
        parser.addArgument("--" + field.name(), field.name(), null, "json_arg", description);
      } else if (!"bool".equals(baseType)) {
        parser.addArgument(
            "--" + field.name(), field.name(), listType ? "*" : null, baseType, description);
      }
      if ("bool".equals(baseType)) {
        parser.addArgument("--" + field.name(), field.name(), null, "bool_arg", description);
      }
    }
  }

  /** Ports parse_model_from_args: only non-None namespace values matching model fields. */
  public static Object parseModelFromArgs(Model model, Map<String, Object> namespace) {
    Map<String, Object> kwargs = new java.util.LinkedHashMap<>();
    java.util.Set<String> fields = new java.util.HashSet<>();
    for (ModelField field : model.modelFields()) fields.add(field.name());
    for (Map.Entry<String, Object> entry : namespace.entrySet()) {
      if (entry.getValue() != null && fields.contains(entry.getKey())) {
        kwargs.put(entry.getKey(), entry.getValue());
      }
    }
    return model.construct(kwargs);
  }

  private static void assertArgs(Annotation annotation) {
    if (annotation.args() == null || annotation.args().isEmpty()) {
      throw Py.error(PythonErrorType.ASSERTION_ERROR, "");
    }
  }

  // ---- server/__main__.py main -------------------------------------------------

  /** argparse.ArgumentParser port for __main__.main. */
  public interface MainParser extends ArgumentParser {
    /** parse_args() result namespace. */
    Map<String, Object> parseArgs();

    void printHelp();
  }

  /** Dependency ports of __main__.main; every call is recordable. */
  public interface MainDeps {
    MainParser parser(String description);

    /** Settings model for add_args_from_model. */
    Model settingsModel();

    Model serverSettingsModel();

    Model modelSettingsModel();

    /** os.environ.get / os.getenv. */
    String envGet(String name);

    boolean pathExists(String path);

    /** ConfigFileSettings.model_validate_json. */
    Object configValidateJson(String json);

    /** yaml.safe_load on the opened config file. */
    Object yamlSafeLoad(String path);

    /** f.read() of the opened non-yaml config file. */
    String openRead(String path);

    /** ServerSettings.model_validate. */
    Object serverSettingsValidate(Object configFileSettings);

    /** config_file_settings.models. */
    List<Object> configModels(Object configFileSettings);

    /** print(e, file=sys.stderr). */
    void printErr(String message);

    /** sys.exit(code). */
    void exit(int code);

    Object createApp(Object serverSettings, List<Object> modelSettings);

    Object serverHost(Object serverSettings);

    Object serverPort(Object serverSettings);

    Object sslKeyfile(Object serverSettings);

    Object sslCertfile(Object serverSettings);

    void uvicornRun(Object app, Object host, int port, Object sslKeyfile, Object sslCertfile);
  }

  /** Ports server/__main__.py main. */
  public static void main(MainDeps deps) {
    String description = "🦙 Llama.cpp python server. Host your own LLMs!🚀";
    MainParser parser = deps.parser(description);
    addArgsFromModel(parser, deps.settingsModel());
    parser.addArgument(
        "--config_file", "config_file", null, "str", "Path to a config file to load.");
    Object serverSettings = null;
    List<Object> modelSettings = new java.util.ArrayList<>();
    Map<String, Object> arguments = parser.parseArgs();
    try {
      String configFile = deps.envGet("CONFIG_FILE");
      if (configFile == null) {
        Object value = arguments.get("config_file");
        configFile = value instanceof String text ? text : null;
      }
      if (configFile != null) {
        if (!deps.pathExists(configFile)) {
          throw Py.error(PythonErrorType.VALUE_ERROR, "Config file " + configFile + " not found!");
        }
        Object configFileSettings;
        if (configFile.endsWith(".yaml") || configFile.endsWith(".yml")) {
          configFileSettings =
              deps.configValidateJson(PythonJson.dumps(deps.yamlSafeLoad(configFile)));
        } else {
          configFileSettings = deps.configValidateJson(deps.openRead(configFile));
        }
        serverSettings = deps.serverSettingsValidate(configFileSettings);
        modelSettings = new java.util.ArrayList<>(deps.configModels(configFileSettings));
      } else {
        serverSettings = parseModelFromArgs(deps.serverSettingsModel(), arguments);
        modelSettings = new java.util.ArrayList<>();
        modelSettings.add(parseModelFromArgs(deps.modelSettingsModel(), arguments));
      }
    } catch (Exception error) {
      String message =
          error instanceof PythonException failure ? failure.getMessage() : String.valueOf(error);
      deps.printErr(message);
      parser.printHelp();
      deps.exit(1);
      return;
    }
    if (serverSettings == null) {
      throw Py.error(PythonErrorType.ASSERTION_ERROR, "");
    }
    if (modelSettings == null) {
      throw Py.error(PythonErrorType.ASSERTION_ERROR, "");
    }
    Object app = deps.createApp(serverSettings, modelSettings);
    String host = deps.envGet("HOST");
    if (host == null) {
      host = (String) deps.serverHost(serverSettings);
    }
    String portEnv = deps.envGet("PORT");
    int port =
        portEnv != null
            ? Integer.parseInt(portEnv)
            : ((Number) deps.serverPort(serverSettings)).intValue();
    deps.uvicornRun(
        app, host, port, deps.sslKeyfile(serverSettings), deps.sslCertfile(serverSettings));
  }
}

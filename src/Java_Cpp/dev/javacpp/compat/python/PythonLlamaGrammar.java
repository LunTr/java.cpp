package dev.javacpp.compat.python;

import static dev.javacpp.compat.python.PythonErrorBridge.error;

import dev.javacpp.python.PythonErrorType;
import java.util.*;

/**
 * llama_grammar.py: LlamaGrammar wrappers, _build_repetition and the full json-schema to gbnf
 * SchemaConverter. Pure-Python bodies; only file/network access is behind ports.
 */
public final class PythonLlamaGrammar {

  private PythonLlamaGrammar() {}

  /** Python repr() for dict/list/str values used in assert messages. */
  public static String pythonRepr(Object v) {
    if (v instanceof Map<?, ?> m) {
      StringBuilder b = new StringBuilder("{");
      boolean first = true;
      for (var e : m.entrySet()) {
        if (!first) b.append(", ");
        first = false;
        b.append(pythonRepr(e.getKey())).append(": ").append(pythonRepr(e.getValue()));
      }
      return b.append("}").toString();
    }
    if (v instanceof List<?> l) {
      StringBuilder b = new StringBuilder("[");
      boolean first = true;
      for (Object x : l) {
        if (!first) b.append(", ");
        first = false;
        b.append(pythonRepr(x));
      }
      return b.append("]").toString();
    }
    if (v instanceof String s) return "'" + s + "'";
    if (v == null) return "None";
    if (v instanceof Boolean b) return b ? "True" : "False";
    return String.valueOf(v);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Object v) {
    return (Map<String, Object>) v;
  }

  @SuppressWarnings("unchecked")
  static List<Object> list(Object v) {
    return (List<Object>) v;
  }

  static String str(Object v) {
    return v == null ? "None" : v instanceof String s ? s : String.valueOf(v);
  }

  /** open(file) port. */
  public interface FilePort {
    String readAll(Object file) throws Exception;
  }

  public static String grammarFromString(String grammar, boolean verbose) {
    return grammar;
  }

  public static String grammarFromFile(FilePort port, Object file, boolean verbose) {
    String grammar;
    try {
      grammar = port.readAll(file);
    } catch (Exception err) {
      throw error(
          PythonErrorType.BASE_EXCEPTION,
          "from_file: error reading grammar file: " + err.getMessage());
    }
    if (grammar != null && !grammar.isEmpty()) {
      return grammarFromString(grammar, verbose);
    }
    throw error(
        PythonErrorType.VALUE_ERROR,
        "from_file: error parsing grammar file: params_grammer is empty");
  }

  public static String grammarFromJsonSchema(
      String jsonSchema, boolean verbose, List<Object> propOrder) {
    return grammarFromString(jsonSchemaToGbnf(jsonSchema, propOrder), verbose);
  }

  // ---------------------------------------------------------------------------
  // _build_repetition + opt_repetitions

  public static String buildRepetition(
      String itemRule,
      long minItems,
      Long maxItems,
      String separatorRule,
      boolean itemRuleIsLiteral) {
    if (separatorRule == null || separatorRule.isEmpty()) {
      if (minItems == 0 && maxItems != null && maxItems == 1) {
        return itemRule + "?";
      } else if (minItems == 1 && maxItems == null) {
        return itemRule + "+";
      }
    }
    StringBuilder result = new StringBuilder();
    if (minItems > 0) {
      if (itemRuleIsLiteral && separatorRule == null) {
        result
            .append('"')
            .append(itemRule.substring(1, itemRule.length() - 1).repeat((int) minItems))
            .append('"');
      } else {
        String sep = separatorRule != null ? " " + separatorRule + " " : " ";
        for (int k = 0; k < minItems; k++) {
          if (k > 0) result.append(sep);
          result.append(itemRule);
        }
      }
    }
    if (minItems > 0 && (maxItems == null || maxItems != minItems)) {
      result.append(" ");
    }
    if (maxItems != null) {
      result.append(optRepetitions(itemRule, separatorRule, maxItems - minItems, minItems > 0));
    } else {
      String itemOperator =
          "(" + (separatorRule != null ? separatorRule + " " : "") + itemRule + ")";
      if (minItems == 0 && separatorRule != null) {
        result = new StringBuilder("(" + itemRule + " " + itemOperator + "*)?");
      } else {
        result.append(itemOperator).append("*");
      }
    }
    return result.toString();
  }

  /** Nested opt_repetitions. */
  public static String optRepetitions(
      String itemRule, String separatorRule, long upToN, boolean prefixWithSep) {
    String content =
        prefixWithSep && separatorRule != null && !separatorRule.isEmpty()
            ? separatorRule + " " + itemRule
            : itemRule;
    if (upToN == 0) {
      return "";
    } else if (upToN == 1) {
      return "(" + content + ")?";
    } else if (separatorRule != null && !prefixWithSep) {
      return "(" + content + " " + optRepetitions(itemRule, separatorRule, upToN - 1, true) + ")?";
    } else {
      StringBuilder b = new StringBuilder();
      for (int k = 0; k < upToN; k++) b.append("(").append(content).append(" ");
      String s = b.toString().replaceAll("\\s+$", "");
      return s + ")?".repeat((int) upToN);
    }
  }

  // ---------------------------------------------------------------------------
  // BuiltinRule + rule tables

  public static final class BuiltinRule {
    public final String content;
    public final List<String> deps;

    public BuiltinRule(String content, List<String> deps) {
      this.content = content;
      this.deps = deps == null ? List.of() : deps;
    }
  }

  private static final String UP_TO_15 = buildRepetition("[0-9]", 0, 15L, null, false);

  public static final Map<String, BuiltinRule> PRIMITIVE_RULES;
  public static final Map<String, BuiltinRule> STRING_FORMAT_RULES;
  public static final Set<String> RESERVED_NAMES;
  static final Set<Character> NON_LITERAL_SET =
      Set.of('|', '.', '(', ')', '[', ']', '{', '}', '*', '+', '?');
  static final Set<Character> ESCAPED_IN_REGEXPS_BUT_NOT_IN_LITERALS =
      Set.of('[', ']', '(', ')', '|', '{', '}', '*', '+', '?');
  static final String SPACE_RULE = "\" \"?";
  static final String DOTALL = "[\\\\U00000000-\\\\U0010FFFF]";
  static final String DOT = "[^\\\\x0A\\\\x0D]";

  static {
    Map<String, BuiltinRule> p = new LinkedHashMap<>();
    p.put("boolean", new BuiltinRule("(\"true\" | \"false\") space", List.of()));
    p.put("decimal-part", new BuiltinRule("[0-9] " + UP_TO_15, List.of()));
    p.put("integral-part", new BuiltinRule("[0-9] | [1-9] " + UP_TO_15, List.of()));
    p.put(
        "number",
        new BuiltinRule(
            "(\"-\"? integral-part) (\".\" decimal-part)? ([eE] [-+]? integral-part)? space",
            List.of("integral-part", "decimal-part")));
    p.put("integer", new BuiltinRule("(\"-\"? integral-part) space", List.of("integral-part")));
    p.put(
        "value",
        new BuiltinRule(
            "object | array | string | number | boolean | null",
            List.of("object", "array", "string", "number", "boolean", "null")));
    p.put(
        "object",
        new BuiltinRule(
            "\"{\" space ( string \":\" space value (\",\" space string \":\" space value)* )? \"}\" space",
            List.of("string", "value")));
    p.put(
        "array",
        new BuiltinRule(
            "\"[\" space ( value (\",\" space value)* )? \"]\" space", List.of("value")));
    StringBuilder uuid = new StringBuilder("\"\\\"\" ");
    int[] segs = {8, 4, 4, 4, 12};
    for (int k = 0; k < segs.length; k++) {
      if (k > 0) uuid.append(" \"-\" ");
      uuid.append("[0-9a-fA-F]".repeat(segs[k]));
    }
    uuid.append(" \"\\\"\" space");
    p.put("uuid", new BuiltinRule(uuid.toString(), List.of()));
    p.put(
        "char",
        new BuiltinRule(
            "[^\"\\\\] | \"\\\\\" ([\"\\\\/bfnrt] | \"u\" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F])",
            List.of()));
    p.put("string", new BuiltinRule("\"\\\"\" char* \"\\\"\" space", List.of("char")));
    p.put("null", new BuiltinRule("\"null\" space", List.of()));
    PRIMITIVE_RULES = p;

    Map<String, BuiltinRule> sf = new LinkedHashMap<>();
    sf.put(
        "date",
        new BuiltinRule(
            "[0-9] [0-9] [0-9] [0-9] \"-\" ( \"0\" [1-9] | \"1\" [0-2] ) \"-\" ( \"0\" [1-9] | [1-2] [0-9] | \"3\" [0-1] )",
            List.of()));
    sf.put(
        "time",
        new BuiltinRule(
            "([01] [0-9] | \"2\" [0-3]) \":\" [0-5] [0-9] \":\" [0-5] [0-9] ( \".\" [0-9] [0-9] [0-9] )? ( \"Z\" | ( \"+\" | \"-\" ) ( [01] [0-9] | \"2\" [0-3] ) \":\" [0-5] [0-9] )",
            List.of()));
    sf.put("date-time", new BuiltinRule("date \"T\" time", List.of("date", "time")));
    sf.put(
        "date-string",
        new BuiltinRule(
            '"' + "\\" + '"' + '"' + " date " + '"' + "\\" + '"' + '"' + " space",
            List.of("date")));
    sf.put(
        "time-string",
        new BuiltinRule(
            '"' + "\\" + '"' + '"' + " time " + '"' + "\\" + '"' + '"' + " space",
            List.of("time")));
    sf.put(
        "date-time-string",
        new BuiltinRule(
            '"' + "\\" + '"' + '"' + " date-time " + '"' + "\\" + '"' + '"' + " space",
            List.of("date-time")));
    STRING_FORMAT_RULES = sf;

    Set<String> r = new LinkedHashSet<>();
    r.add("root");
    r.add("dot");
    r.addAll(p.keySet());
    r.addAll(sf.keySet());
    RESERVED_NAMES = r;
  }

  // ---------------------------------------------------------------------------
  // SchemaConverter

  /** requests.get port for https $ref fetch. */
  public interface Fetcher {
    Object getJson(String url);
  }

  public static final class SchemaConverter {
    final Map<String, Integer> propOrder;
    final boolean allowFetch, dotall, rawPattern;
    final LinkedHashMap<String, String> rules = new LinkedHashMap<>();
    final Map<String, Object> refs = new HashMap<>();
    final Set<String> refsBeingResolved = new LinkedHashSet<>();
    Fetcher fetcher;

    public SchemaConverter(
        Map<String, Integer> propOrder,
        boolean allowFetch,
        boolean dotall,
        boolean rawPattern,
        Fetcher fetcher) {
      this.propOrder = propOrder;
      this.allowFetch = allowFetch;
      this.dotall = dotall;
      this.rawPattern = rawPattern;
      this.fetcher = fetcher;
      rules.put("space", SPACE_RULE);
    }

    String formatLiteral(String literal) {
      return "\"" + literal.replace("\r", "\\r").replace("\n", "\\n").replace("\"", "\\\"") + "\"";
    }

    public String notLiteral(String literal, boolean dotall, boolean maybeEscapedUnderscores) {
      if (literal.isEmpty()) {
        throw error(PythonErrorType.ASSERTION_ERROR, "Empty literal not supported");
      }
      StringBuilder b = new StringBuilder("(");
      recurseNotLiteral(literal, 0, maybeEscapedUnderscores, b);
      return b.append(")").toString();
    }

    private void recurseNotLiteral(String literal, int i, boolean esc, StringBuilder b) {
      char c = literal.charAt(i);
      if (esc && c == '_') {
        b.append("[^").append(c).append("\\\\]");
        b.append(" | ");
        b.append("\"\\\\\"? \"").append(c).append("\"");
      } else {
        b.append("[^").append(c).append("]");
      }
      if (i < literal.length() - 1) {
        b.append(" | ");
        b.append(formatLiteral(String.valueOf(c)));
        b.append(" (");
        recurseNotLiteral(literal, i + 1, esc, b);
        b.append(")?");
      }
    }

    String addRule(String name, String rule) {
      String escName = name.replaceAll("[^a-zA-Z0-9-]+", "-");
      String key;
      if (!rules.containsKey(escName) || rules.get(escName).equals(rule)) {
        key = escName;
      } else {
        int i = 0;
        while (rules.containsKey(escName + i) && !rules.get(escName + i).equals(rule)) {
          i++;
        }
        key = escName + i;
      }
      rules.put(key, rule);
      return key;
    }

    public Object resolveRefs(Object schema, String url) {
      return visitRefs(schema, url, schema);
    }

    private Object visitRefs(Object n, String url, Object rootSchema) {
      if (n instanceof List) {
        List<Object> out = new ArrayList<>();
        for (Object x : list(n)) out.add(visitRefs(x, url, rootSchema));
        return out;
      } else if (n instanceof Map) {
        Map<String, Object> m = map(n);
        Object ref = m.get("$ref");
        if (ref != null && !refs.containsKey(ref)) {
          Object target = null;
          String refStr = (String) ref;
          if (refStr.startsWith("https://")) {
            if (!allowFetch) {
              throw error(
                  PythonErrorType.ASSERTION_ERROR,
                  "Fetching remote schemas is not allowed (use --allow-fetch for force)");
            }
            String[] fragSplit = refStr.split("#", -1);
            String baseUrl = fragSplit[0];
            target = refs.get(baseUrl);
            if (target == null) {
              target = resolveRefs(fetcher.getJson(refStr), baseUrl);
              refs.put(baseUrl, target);
            }
            if (fragSplit.length == 1 || fragSplit[fragSplit.length - 1].isEmpty()) {
              return target;
            }
          } else if (refStr.startsWith("#/")) {
            target = rootSchema;
            ref = url + refStr;
            m.put("$ref", ref);
          } else {
            throw error(PythonErrorType.VALUE_ERROR, "Unsupported ref " + refStr);
          }
          String[] fragAll = ((String) ref).split("#", -1);
          String[] parts = fragAll[fragAll.length - 1].split("/", -1);
          for (int k = 1; k < parts.length; k++) {
            String sel = parts[k];
            if (!(target instanceof Map) || !map(target).containsKey(sel)) {
              throw error(
                  PythonErrorType.ASSERTION_ERROR,
                  "Error resolving ref " + ref + ": " + sel + " not in " + target);
            }
            target = map(target).get(sel);
          }
          refs.put((String) ref, target);
        } else {
          for (Object v : m.values()) visitRefs(v, url, rootSchema);
        }
      }
      return n;
    }

    String generateUnionRule(String name, List<Object> altSchemas) {
      List<String> parts = new ArrayList<>();
      for (int i = 0; i < altSchemas.size(); i++) {
        parts.add(visit(altSchemas.get(i), name + (name.isEmpty() ? "alternative-" : "-") + i));
      }
      return String.join(" | ", parts);
    }

    String resolveRef(String ref) {
      String refName = ref.substring(ref.lastIndexOf('/') + 1);
      if (!rules.containsKey(refName) && !refsBeingResolved.contains(ref)) {
        refsBeingResolved.add(ref);
        Object resolved = refs.get(ref);
        refName = visit(resolved, refName);
        refsBeingResolved.remove(ref);
      }
      return refName;
    }

    String generateConstantRule(Object value) {
      return formatLiteral(PythonJson.dumps(value));
    }

    public String visit(Object schemaObj, String name) {
      Map<String, Object> schema = map(schemaObj);
      Object schemaType = schema.get("type");
      Object schemaFormat = schema.get("format");
      String ruleName =
          RESERVED_NAMES.contains(name) ? name + "-" : (name.isEmpty() ? "root" : name);

      Object ref = schema.get("$ref");
      if (ref != null) {
        return addRule(ruleName, resolveRef((String) ref));
      } else if (schema.containsKey("oneOf") || schema.containsKey("anyOf")) {
        Object alts = schema.get("oneOf") != null ? schema.get("oneOf") : schema.get("anyOf");
        return addRule(ruleName, generateUnionRule(name, list(alts)));
      } else if (schemaType instanceof List) {
        List<Object> alt = new ArrayList<>();
        for (Object t : list(schemaType)) {
          Map<String, Object> d = new LinkedHashMap<>();
          d.put("type", t);
          alt.add(d);
        }
        return addRule(ruleName, generateUnionRule(name, alt));
      } else if (schema.containsKey("const")) {
        return addRule(ruleName, generateConstantRule(schema.get("const")));
      } else if (schema.containsKey("enum")) {
        List<String> parts = new ArrayList<>();
        for (Object v : list(schema.get("enum"))) parts.add(generateConstantRule(v));
        return addRule(ruleName, String.join(" | ", parts));
      } else if ((schemaType == null || "object".equals(schemaType))
          && (schema.containsKey("properties")
              || (schema.containsKey("additionalProperties")
                  && !Boolean.TRUE.equals(schema.get("additionalProperties"))))) {
        Set<String> required = new LinkedHashSet<>();
        for (Object r : list(schema.getOrDefault("required", List.of()))) required.add(str(r));
        List<Object[]> properties = new ArrayList<>();
        for (var e : map(schema.getOrDefault("properties", Map.of())).entrySet())
          properties.add(new Object[] {e.getKey(), e.getValue()});
        return addRule(
            ruleName,
            buildObjectRule(properties, required, name, schema.get("additionalProperties")));
      } else if ((schemaType == null || "object".equals(schemaType))
          && schema.containsKey("allOf")) {
        Set<String> required = new LinkedHashSet<>();
        List<Object[]> properties = new ArrayList<>();
        for (Object t : list(schema.get("allOf"))) {
          if (map(t).containsKey("anyOf")) {
            for (Object tt : list(map(t).get("anyOf")))
              addComponent(tt, properties, required, false);
          } else {
            addComponent(t, properties, required, true);
          }
        }
        return addRule(ruleName, buildObjectRule(properties, required, name, List.of()));
      } else if ((schemaType == null || "array".equals(schemaType))
          && (schema.containsKey("items") || schema.containsKey("prefixItems"))) {
        Object items =
            schema.get("items") != null ? schema.get("items") : schema.get("prefixItems");
        if (items instanceof List) {
          List<String> parts = new ArrayList<>();
          for (int i = 0; i < list(items).size(); i++) {
            parts.add(visit(list(items).get(i), name + (name.isEmpty() ? "" : "-") + "tuple-" + i));
          }
          return addRule(
              ruleName, "\"[\" space " + String.join(" \",\" space ", parts) + " \"]\" space");
        } else {
          String itemRuleName = visit(items, name + (name.isEmpty() ? "" : "-") + "item");
          long minItems = schema.get("minItems") instanceof Number mn ? mn.longValue() : 0;
          Long maxItems = schema.get("maxItems") instanceof Number mx ? mx.longValue() : null;
          return addRule(
              ruleName,
              "\"[\" space "
                  + buildRepetition(itemRuleName, minItems, maxItems, "\",\" space", false)
                  + " \"]\" space");
        }
      } else if ((schemaType == null || "string".equals(schemaType))
          && schema.containsKey("pattern")) {
        return visitPattern(str(schema.get("pattern")), ruleName);
      } else if ((schemaType == null || "string".equals(schemaType))
          && str(schemaFormat).matches("uuid[1-5]?")) {
        return addPrimitive(
            "root".equals(ruleName) ? "root" : str(schemaFormat), PRIMITIVE_RULES.get("uuid"));
      } else if ((schemaType == null || "string".equals(schemaType))
          && STRING_FORMAT_RULES.containsKey(schemaFormat + "-string")) {
        String primName = schemaFormat + "-string";
        return addRule(ruleName, addPrimitive(primName, STRING_FORMAT_RULES.get(primName)));
      } else if ("string".equals(schemaType)
          && (schema.containsKey("minLength") || schema.containsKey("maxLength"))) {
        String charRule = addPrimitive("char", PRIMITIVE_RULES.get("char"));
        long minLen = schema.get("minLength") instanceof Number mn ? mn.longValue() : 0;
        Long maxLen = schema.get("maxLength") instanceof Number mx ? mx.longValue() : null;
        return addRule(
            ruleName,
            "\"\\\"\" "
                + buildRepetition(charRule, minLen, maxLen, null, false)
                + " \"\\\"\" space");
      } else if ("object".equals(schemaType) || schema.isEmpty()) {
        return addRule(ruleName, addPrimitive("object", PRIMITIVE_RULES.get("object")));
      } else {
        if (!(schemaType instanceof String) || !PRIMITIVE_RULES.containsKey(schemaType)) {
          throw error(
              PythonErrorType.ASSERTION_ERROR, "Unrecognized schema: " + pythonRepr(schema));
        }
        return addPrimitive(
            "root".equals(ruleName) ? "root" : str(schemaType), PRIMITIVE_RULES.get(schemaType));
      }
    }

    private void addComponent(
        Object compObj, List<Object[]> properties, Set<String> required, boolean isRequired) {
      Object compSchema = compObj;
      Object ref = map(compSchema).get("$ref");
      if (ref != null) {
        compSchema = refs.get(ref);
      }
      if (compSchema instanceof Map && map(compSchema).containsKey("properties")) {
        for (var e : map(map(compSchema).get("properties")).entrySet()) {
          properties.add(new Object[] {e.getKey(), e.getValue()});
          if (isRequired) required.add(String.valueOf(e.getKey()));
        }
      }
    }

    String addPrimitive(String name, BuiltinRule rule) {
      String n = addRule(name, rule.content);
      for (String dep : rule.deps) {
        BuiltinRule depRule = PRIMITIVE_RULES.get(dep);
        if (depRule == null) depRule = STRING_FORMAT_RULES.get(dep);
        if (depRule == null) {
          throw error(PythonErrorType.ASSERTION_ERROR, "Rule " + dep + " not known");
        }
        if (!rules.containsKey(dep)) addPrimitive(dep, depRule);
      }
      return n;
    }

    String buildObjectRule(
        List<Object[]> properties, Set<String> required, String name, Object additionalProperties) {
      Map<String, Integer> order = propOrder;
      List<int[]> idx = new ArrayList<>();
      for (int i = 0; i < properties.size(); i++) idx.add(new int[] {i});
      idx.sort(
          Comparator.<int[], Integer>comparing(
                  iv -> order.getOrDefault(String.valueOf(properties.get(iv[0])[0]), order.size()))
              .thenComparing(iv -> iv[0]));
      List<String> sortedProps = new ArrayList<>();
      for (int[] iv : idx) sortedProps.add(String.valueOf(properties.get(iv[0])[0]));

      Map<String, String> kvRuleNames = new LinkedHashMap<>();
      for (Object[] kv : properties) {
        String propName = String.valueOf(kv[0]);
        String propRuleName = visit(kv[1], name + (name.isEmpty() ? "" : "-") + propName);
        kvRuleNames.put(
            propName,
            addRule(
                name + (name.isEmpty() ? "" : "-") + propName + "-kv",
                formatLiteral(PythonJson.dumps(propName)) + " space \":\" space " + propRuleName));
      }
      List<String> requiredProps = new ArrayList<>();
      List<String> optionalProps = new ArrayList<>();
      for (String k : sortedProps) {
        (required.contains(k) ? requiredProps : optionalProps).add(k);
      }
      if (Boolean.TRUE.equals(additionalProperties) || additionalProperties instanceof Map) {
        String subName = name + (name.isEmpty() ? "" : "-") + "additional";
        String valueRule =
            visit(
                Boolean.TRUE.equals(additionalProperties) ? Map.of() : additionalProperties,
                subName + "-value");
        kvRuleNames.put(
            "*",
            addRule(
                subName + "-kv",
                addPrimitive("string", PRIMITIVE_RULES.get("string"))
                    + " \":\" space "
                    + valueRule));
        optionalProps.add("*");
      }

      StringBuilder rule = new StringBuilder("\"{\" space ");
      rule.append(
          String.join(" \",\" space ", requiredProps.stream().map(kvRuleNames::get).toList()));
      if (!optionalProps.isEmpty()) {
        rule.append(" (");
        if (!requiredProps.isEmpty()) rule.append(" \",\" space ( ");
        List<String> alts = new ArrayList<>();
        for (int i = 0; i < optionalProps.size(); i++) {
          alts.add(
              recursiveRefs(
                  optionalProps.subList(i, optionalProps.size()), false, kvRuleNames, name));
        }
        rule.append(String.join(" | ", alts));
        if (!requiredProps.isEmpty()) rule.append(" )");
        rule.append(" )?");
      }
      rule.append(" \"}\" space");
      return rule.toString();
    }

    private String recursiveRefs(
        List<String> ks, boolean firstIsOptional, Map<String, String> kvRuleNames, String name) {
      String k = ks.get(0);
      List<String> rest = ks.subList(1, ks.size());
      String kvRuleName = kvRuleNames.get(k);
      String res;
      if ("*".equals(k)) {
        res =
            addRule(
                name + (name.isEmpty() ? "" : "-") + "additional-kvs",
                kvRuleName + " ( \",\" space " + kvRuleName + " )*");
      } else if (firstIsOptional) {
        res = "( \",\" space " + kvRuleName + " )?";
      } else {
        res = kvRuleName;
      }
      if (!rest.isEmpty()) {
        res +=
            " "
                + addRule(
                    name + (name.isEmpty() ? "" : "-") + k + "-rest",
                    recursiveRefs(rest, true, kvRuleNames, name));
      }
      return res;
    }

    String visitPattern(String pattern, String name) {
      if (!(pattern.startsWith("^") && pattern.endsWith("$"))) {
        throw error(
            PythonErrorType.ASSERTION_ERROR, "Pattern must start with \"^\" and end with \"$\"");
      }
      PatternCtx ctx = new PatternCtx(pattern.substring(1, pattern.length() - 1), name);
      Object[] r = transform(ctx);
      String body = rawPattern ? toRule(r) : "\"\\\"\" " + toRule(r) + " \"\\\"\" space";
      return addRule(name, body);
    }

    private static final class PatternCtx {
      final String pattern;
      final String name;
      final Map<String, String> subRuleIds = new HashMap<>();
      int i;

      PatternCtx(String pattern, String name) {
        this.pattern = pattern;
        this.name = name;
      }
    }

    private static String toRule(Object[] s) {
      String txt = (String) s[0];
      boolean isLiteral = (Boolean) s[1];
      return isLiteral ? "\"" + txt + "\"" : txt;
    }

    private String getDot() {
      return addRule("dot", dotall ? DOTALL : DOT);
    }

    private Object[] transform(PatternCtx ctx) {
      int start = ctx.i;
      List<Object[]> seq = new ArrayList<>();
      int length = ctx.pattern.length();
      while (ctx.i < length) {
        char c = ctx.pattern.charAt(ctx.i);
        if (c == '.') {
          seq.add(new Object[] {getDot(), false});
          ctx.i++;
        } else if (c == '(') {
          ctx.i++;
          if (ctx.i < length && ctx.pattern.charAt(ctx.i) == '?') {
            throw error(
                PythonErrorType.ASSERTION_ERROR,
                "Unsupported pattern syntax \""
                    + ctx.pattern.charAt(ctx.i)
                    + "\" at index "
                    + ctx.i
                    + " of /"
                    + ctx.pattern
                    + "/");
          }
          seq.add(new Object[] {"(" + toRule(transform(ctx)) + ")", false});
        } else if (c == ')') {
          ctx.i++;
          if (!(start > 0 && ctx.pattern.charAt(start - 1) == '(')) {
            throw error(
                PythonErrorType.ASSERTION_ERROR,
                "Unbalanced parentheses; start = "
                    + start
                    + ", i = "
                    + ctx.i
                    + ", pattern = "
                    + ctx.pattern);
          }
          return joinSeq(seq);
        } else if (c == '[') {
          StringBuilder sb = new StringBuilder("[");
          ctx.i++;
          while (ctx.i < length && ctx.pattern.charAt(ctx.i) != ']') {
            if (ctx.pattern.charAt(ctx.i) == '\\') {
              sb.append(ctx.pattern, ctx.i, ctx.i + 2);
              ctx.i += 2;
            } else {
              sb.append(ctx.pattern.charAt(ctx.i));
              ctx.i++;
            }
          }
          if (!(ctx.i < length)) {
            throw error(
                PythonErrorType.ASSERTION_ERROR,
                "Unbalanced square brackets; start = "
                    + start
                    + ", i = "
                    + ctx.i
                    + ", pattern = "
                    + ctx.pattern);
          }
          sb.append(']');
          ctx.i++;
          seq.add(new Object[] {sb.toString(), false});
        } else if (c == '|') {
          seq.add(new Object[] {"|", false});
          ctx.i++;
        } else if (c == '*' || c == '+' || c == '?') {
          if (seq.isEmpty()) {
            throw error(PythonErrorType.INDEX_ERROR, "list index out of range");
          }
          Object[] last = seq.get(seq.size() - 1);
          seq.set(seq.size() - 1, new Object[] {toRule(last) + c, false});
          ctx.i++;
        } else if (c == '{') {
          StringBuilder cb = new StringBuilder("{");
          ctx.i++;
          while (ctx.i < length && ctx.pattern.charAt(ctx.i) != '}') {
            cb.append(ctx.pattern.charAt(ctx.i));
            ctx.i++;
          }
          if (!(ctx.i < length)) {
            throw error(
                PythonErrorType.ASSERTION_ERROR,
                "Unbalanced curly brackets; start = "
                    + start
                    + ", i = "
                    + ctx.i
                    + ", pattern = "
                    + ctx.pattern);
          }
          cb.append('}');
          ctx.i++;
          String curly = cb.toString();
          String[] nums = curly.substring(1, curly.length() - 1).split(",", -1);
          for (int k = 0; k < nums.length; k++) nums[k] = nums[k].strip();
          long minTimes;
          Long maxTimes;
          try {
            if (nums.length == 1) {
              minTimes = Long.parseLong(nums[0]);
              maxTimes = minTimes;
            } else {
              if (nums.length != 2) throw new NumberFormatException();
              minTimes = nums[0].isEmpty() ? 0 : Long.parseLong(nums[0]);
              maxTimes = nums[1].isEmpty() ? null : Long.parseLong(nums[1]);
            }
          } catch (RuntimeException e) {
            throw error(
                PythonErrorType.VALUE_ERROR,
                "Invalid quantifier " + curly + " in /" + ctx.pattern + "/");
          }
          if (seq.isEmpty()) {
            throw error(PythonErrorType.INDEX_ERROR, "list index out of range");
          }
          Object[] last = seq.get(seq.size() - 1);
          String sub = (String) last[0];
          boolean subIsLiteral = (Boolean) last[1];
          if (!subIsLiteral) {
            String id = ctx.subRuleIds.get(sub);
            if (id == null) {
              id = addRule(ctx.name + "-" + (ctx.subRuleIds.size() + 1), sub);
              ctx.subRuleIds.put(sub, id);
            }
            sub = id;
          }
          seq.set(
              seq.size() - 1,
              new Object[] {
                buildRepetition(
                    subIsLiteral ? "\"" + sub + "\"" : sub, minTimes, maxTimes, null, subIsLiteral),
                false
              });
        } else {
          StringBuilder literal = new StringBuilder();
          while (ctx.i < length) {
            char pc = ctx.pattern.charAt(ctx.i);
            if (pc == '\\' && ctx.i < length - 1) {
              char next = ctx.pattern.charAt(ctx.i + 1);
              if (ESCAPED_IN_REGEXPS_BUT_NOT_IN_LITERALS.contains(next)) {
                ctx.i++;
                literal.append(ctx.pattern.charAt(ctx.i));
                ctx.i++;
              } else {
                literal.append(ctx.pattern, ctx.i, ctx.i + 2);
                ctx.i += 2;
              }
            } else if (pc == '"' && !rawPattern) {
              literal.append("\\\"");
              ctx.i++;
            } else if (!NON_LITERAL_SET.contains(pc)
                && (ctx.i == length - 1
                    || literal.length() == 0
                    || ctx.pattern.charAt(ctx.i + 1) == '.'
                    || !NON_LITERAL_SET.contains(ctx.pattern.charAt(ctx.i + 1)))) {
              literal.append(pc);
              ctx.i++;
            } else {
              break;
            }
          }
          if (literal.length() > 0) {
            seq.add(new Object[] {literal.toString(), true});
          }
        }
      }
      return joinSeq(seq);
    }

    private static Object[] joinSeq(List<Object[]> seq) {
      List<Object[]> ret = new ArrayList<>();
      boolean prevLit = false, first = true;
      StringBuilder lit = new StringBuilder();
      for (Object[] x : seq) {
        boolean isLit = (Boolean) x[1];
        if (isLit) {
          if (first || !prevLit) lit = new StringBuilder();
          lit.append(x[0]);
          prevLit = true;
        } else {
          if (prevLit) ret.add(new Object[] {lit.toString(), true});
          ret.add(x);
          prevLit = false;
        }
        first = false;
      }
      if (prevLit) ret.add(new Object[] {lit.toString(), true});
      if (ret.size() == 1) return ret.get(0);
      List<String> parts = new ArrayList<>();
      for (Object[] x : seq) parts.add(toRule(x));
      return new Object[] {String.join(" ", parts), false};
    }

    public String formatGrammar() {
      List<String> lines = new ArrayList<>();
      rules.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(e -> lines.add(e.getKey() + " ::= " + e.getValue()));
      return String.join("\n", lines);
    }
  }

  // ---------------------------------------------------------------------------
  // json_schema_to_gbnf

  public static String jsonSchemaToGbnf(String schema, List<Object> propOrder) {
    Object schemaObj = PythonJson.loads(schema);
    Map<String, Integer> orderMap = new LinkedHashMap<>();
    if (propOrder != null) {
      int i = 0;
      for (Object p : propOrder) orderMap.put(str(p), i++);
    }
    SchemaConverter converter = new SchemaConverter(orderMap, false, false, false, null);
    Object resolved = converter.resolveRefs(schemaObj, "stdin");
    converter.visit(resolved, "");
    return converter.formatGrammar();
  }
}

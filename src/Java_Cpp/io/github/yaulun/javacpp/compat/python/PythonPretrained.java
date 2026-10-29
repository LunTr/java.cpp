package io.github.yaulun.javacpp.compat.python;

import static io.github.yaulun.javacpp.compat.python.PythonValues.*;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Original repository matching/download/construction order with an explicit Java repository port.
 */
public final class PythonPretrained {
  public interface Repository {
    void validate(String repoId);

    List<?> files(String repoId);

    String download(Map<String, Object> arguments);
  }

  private PythonPretrained() {}

  public static <T> T fromPretrained(
      Map<String, Object> supplied,
      Repository repository,
      Function<Map<String, Object>, T> constructor) {
    var options = PythonLlamaDefaults.bind("from_pretrained", supplied);
    if (repository == null)
      throw error(
          PythonErrorType.IMPORT_ERROR,
          "Llama.from_pretrained requires the huggingface-hub package. You can install it with `pip install huggingface-hub`.");
    String repo = (String) options.get("repo_id");
    repository.validate(repo);
    List<String> files = new ArrayList<>(), relative = new ArrayList<>();
    for (Object item : repository.files(repo))
      files.add((String) (item instanceof Map<?, ?> map ? get(map, "name") : item));
    for (String file : files) {
      Path base = Path.of(repo), target = Path.of(file);
      if (!target.startsWith(base))
        throw error(
            PythonErrorType.VALUE_ERROR,
            repr(target.toString()) + " is not in the subpath of " + repr(base.toString()));
      relative.add(base.relativize(target).toString());
    }
    String matched = match(repo, options.get("filename"), relative, files);
    Path path = Path.of(matched);
    String filename = path.getFileName().toString();
    String folder = path.getParent() == null ? "." : path.getParent().toString();
    Map<String, Object> download =
        dict(
            "repo_id",
            repo,
            "filename",
            filename,
            "subfolder",
            folder,
            "local_dir",
            options.get("local_dir"),
            "local_dir_use_symlinks",
            options.get("local_dir_use_symlinks"),
            "cache_dir",
            options.get("cache_dir"));
    repository.download(download);
    if (truth(options.get("additional_files")))
      for (Object pattern : list(options.get("additional_files"))) {
        Path extra = Path.of(match(repo, pattern, relative, files));
        repository.download(
            dict(
                "repo_id",
                repo,
                "filename",
                extra.getFileName().toString(),
                "subfolder",
                extra.getParent() == null ? "." : extra.getParent().toString(),
                "local_dir",
                options.get("local_dir"),
                "local_dir_use_symlinks",
                options.get("local_dir_use_symlinks"),
                "cache_dir",
                options.get("cache_dir")));
      }
    String model;
    if (options.get("local_dir") == null) {
      var cachedRequest = new LinkedHashMap<>(download);
      cachedRequest.put("local_files_only", true);
      model = repository.download(cachedRequest);
    } else model = Path.of(options.get("local_dir").toString(), filename).toString();
    var kwargs = new LinkedHashMap<>(supplied);
    for (String name :
        List.of(
            "repo_id",
            "filename",
            "additional_files",
            "local_dir",
            "local_dir_use_symlinks",
            "cache_dir")) kwargs.remove(name);
    var arguments = dict("model_path", model);
    if (kwargs.containsKey("model_path"))
      throw error(
          PythonErrorType.TYPE_ERROR,
          "llama_cpp.llama.Llama() got multiple values for keyword argument 'model_path'");
    arguments.putAll(kwargs);
    return constructor.apply(arguments);
  }

  private static String match(String repo, Object glob, List<String> relative, List<String> files) {
    if (!(glob instanceof String pattern))
      throw error(
          PythonErrorType.TYPE_ERROR,
          "expected str, bytes or os.PathLike object, not " + typeName(glob));
    Pattern regex = glob(pattern.toLowerCase(Locale.ROOT).replace('/', '\\'));
    List<String> found =
        relative.stream()
            .filter(
                file -> regex.matcher(file.toLowerCase(Locale.ROOT).replace('/', '\\')).matches())
            .toList();
    if (found.isEmpty())
      throw error(
          PythonErrorType.VALUE_ERROR,
          "No file found in "
              + repo
              + " that match "
              + pattern
              + "\n\nAvailable Files:\n"
              + json(relative));
    if (found.size() > 1)
      throw error(
          PythonErrorType.VALUE_ERROR,
          "Multiple files found in "
              + repo
              + " matching "
              + pattern
              + "\n\nAvailable Files:\n"
              + json(files));
    return found.getFirst();
  }

  private static Pattern glob(String pattern) {
    StringBuilder result = new StringBuilder("(?s)");
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '*') result.append(".*");
      else if (c == '?') result.append('.');
      else if (c == '[') {
        int first = i + 1;
        if (first < pattern.length() && pattern.charAt(first) == '!') first++;
        if (first < pattern.length() && pattern.charAt(first) == ']') first++;
        int end = pattern.indexOf(']', first);
        if (end < 0) result.append("\\[");
        else {
          String range = pattern.substring(i + 1, end);
          if (range.indexOf('-') >= 0) {
            List<String> chunks = new ArrayList<>();
            int begin = i + 1, k = begin + (range.startsWith("!") ? 2 : 1);
            while (true) {
              k = pattern.indexOf('-', k);
              if (k < 0 || k >= end) break;
              chunks.add(pattern.substring(begin, k));
              begin = k + 1;
              k += 3;
            }
            String last = pattern.substring(begin, end);
            if (!last.isEmpty()) chunks.add(last);
            else chunks.set(chunks.size() - 1, chunks.getLast() + "-");
            for (int n = chunks.size() - 1; n > 0; n--) {
              String left = chunks.get(n - 1), right = chunks.get(n);
              if (left.codePointBefore(left.length()) > right.codePointAt(0)) {
                chunks.set(
                    n - 1,
                    left.substring(0, left.offsetByCodePoints(left.length(), -1))
                        + right.substring(right.offsetByCodePoints(0, 1)));
                chunks.remove(n);
              }
            }
            List<String> encoded = new ArrayList<>();
            for (String chunk : chunks)
              encoded.add(chunk.replace("\\", "\\\\").replace("-", "\\-"));
            range = String.join("-", encoded);
          } else range = range.replace("\\", "\\\\");
          if (range.isEmpty()) {
            result.append("(?!)");
            i = end;
            continue;
          }
          if (range.equals("!")) {
            result.append('.');
            i = end;
            continue;
          }
          result.append('[');
          if (range.startsWith("!")) {
            result.append('^');
            range = range.substring(1);
          } else if (range.startsWith("^")) result.append("\\");
          for (char value : range.toCharArray()) {
            if (value == '[' || value == ']' || value == '&' || value == '~' || value == '|')
              result.append('\\');
            result.append(value);
          }
          result.append(']');
          i = end;
        }
      } else result.append(Pattern.quote(String.valueOf(c)));
    }
    return Pattern.compile(result.toString());
  }

  private static String json(List<String> strings) {
    List<String> encoded = new ArrayList<>();
    for (String value : strings) {
      StringBuilder text = new StringBuilder("\"");
      for (char c : value.toCharArray())
        switch (c) {
          case '"', '\\' -> text.append('\\').append(c);
          case '\n' -> text.append("\\n");
          case '\r' -> text.append("\\r");
          case '\t' -> text.append("\\t");
          case '\b' -> text.append("\\b");
          case '\f' -> text.append("\\f");
          default -> {
            if (c < 32 || c > 126) text.append(String.format("\\u%04x", (int) c));
            else text.append(c);
          }
        }
      encoded.add(text.append('"').toString());
    }
    return "[" + String.join(", ", encoded) + "]";
  }
}

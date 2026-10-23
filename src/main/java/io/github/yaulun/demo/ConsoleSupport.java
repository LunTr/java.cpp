package io.github.yaulun.demo;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class ConsoleSupport {
  private static final BufferedReader input =
      new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

  private ConsoleSupport() {}

  static String readLine(String prompt) throws IOException {
    Console console = System.console();
    if (console != null) {
      return console.readLine("%s", prompt);
    }
    System.out.print(prompt);
    System.out.flush();
    return input.readLine();
  }
}

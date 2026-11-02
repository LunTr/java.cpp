package io.github.yaulun.javacpp.compat.python;

import java.util.LinkedHashMap;
import java.util.Map;

/** _internals.LlamaSamplingParams dataclass with the original field defaults. */
public class PythonLlamaSamplingParams {
  public int nPrev = 64;
  public int nProbs = 0;
  public int topK = 40;
  public double topP = 0.95;
  public double minP = 0.05;
  public double tfsZ = 1.00;
  public double typicalP = 1.00;
  public double temp = 0.80;
  public int penaltyLastN = 64;
  public double penaltyRepeat = 1.0;
  public double penaltyFreq = 0.00;
  public double penaltyPresent = 0.00;
  public int mirostat = 0;
  public double mirostatTau = 5.00;
  public double mirostatEta = 0.10;
  public boolean penalizeNl = true;
  public String grammar = "";
  public String cfgNegativePrompt = "";
  public double cfgScale = 1.00;
  public Map<Integer, Double> logitBias = new LinkedHashMap<>();
}

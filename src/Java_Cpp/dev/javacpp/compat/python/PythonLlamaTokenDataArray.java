package dev.javacpp.compat.python;

import java.util.Arrays;
import java.util.Objects;

/**
 * _internals.LlamaTokenDataArray: id/logit/p parallel arrays plus the sorted/size bookkeeping the
 * original keeps on the ctypes candidates struct. Storage goes through the port so the
 * llama_token_data_array construction is observable.
 */
public final class PythonLlamaTokenDataArray {
  private final PythonInternalsPort port;
  private final int nVocab;
  private final int[] ids;
  private final float[] logits;
  private final float[] probs;
  private final Object data;
  private final Object candidates;
  private boolean sorted;
  private int size;

  public PythonLlamaTokenDataArray(PythonInternalsPort port, int nVocab) {
    this.port = Objects.requireNonNull(port, "port");
    this.nVocab = nVocab;
    this.ids = new int[nVocab];
    this.logits = new float[nVocab];
    this.probs = new float[nVocab];
    this.data = port.candidatesData(nVocab);
    this.candidates = port.tokenDataArray(data, nVocab, false);
    this.sorted = false;
    this.size = nVocab;
  }

  /** copy_logits: ids reset to 0..n-1, logits copied in, p zeroed, sorted=false, size=n_vocab. */
  public void copyLogits(float[] values) {
    Arrays.setAll(ids, i -> i);
    System.arraycopy(values, 0, logits, 0, nVocab);
    Arrays.fill(probs, 0.0f);
    port.candidatesStore(data, ids, logits, probs);
    port.candidatesAssign(candidates, "sorted", false);
    port.candidatesAssign(candidates, "size", nVocab);
    sorted = false;
    size = nVocab;
  }

  public int nVocab() {
    return nVocab;
  }

  public int[] ids() {
    return ids;
  }

  public float[] logits() {
    return logits;
  }

  public float[] probs() {
    return probs;
  }

  public boolean sorted() {
    return sorted;
  }

  public int size() {
    return size;
  }

  /** The candidates handle passed to llama_sampler_apply. */
  public Object candidates() {
    return candidates;
  }
}

package dev.javacpp.internal;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/** llava.h llava_image_embed: float* embed + int n_image_pos. */
@Structure.FieldOrder({"embed", "n_image_pos"})
public class LlavaImageEmbed extends Structure {
  public Pointer embed;
  public int n_image_pos;
}

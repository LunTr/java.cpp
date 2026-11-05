package dev.javacpp.python;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;

/** Immutable set value carrying CPython's frozenset type name and repr. */
public final class PyFrozenSet extends AbstractSet<Object> {

  private final LinkedHashSet<Object> elements;

  public PyFrozenSet(Collection<?> elements) {
    this.elements = new LinkedHashSet<>(elements);
  }

  @Override
  public Iterator<Object> iterator() {
    return elements.iterator();
  }

  @Override
  public int size() {
    return elements.size();
  }
}

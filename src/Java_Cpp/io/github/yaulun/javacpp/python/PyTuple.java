package io.github.yaulun.javacpp.python;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

/**
 * Immutable Python tuple view over an existing element list. Equality and hashing follow Java list
 * semantics so a tuple can key ordered maps; {@link Py#typeName} and {@link Py#equal} distinguish
 * it from a Python list.
 */
public final class PyTuple<E> extends AbstractList<E> implements RandomAccess {
  private final List<E> elements;

  private PyTuple(List<E> elements) {
    this.elements = elements;
  }

  /** Wraps the supplied list without copying; the caller must not mutate it afterwards. */
  public static <E> PyTuple<E> wrap(List<E> elements) {
    return new PyTuple<>(elements);
  }

  /** Copies any iterable into a tuple, reusing an existing tuple unchanged like tuple(tuple). */
  @SuppressWarnings("unchecked")
  public static <E> PyTuple<E> of(Iterable<E> source) {
    if (source instanceof PyTuple<E> tuple) return tuple;
    if (source instanceof List<E> list) return new PyTuple<>(List.copyOf(list));
    var result = new java.util.ArrayList<E>();
    for (E element : source) result.add(element);
    return new PyTuple<>(result);
  }

  @Override
  public E get(int index) {
    return elements.get(index);
  }

  @Override
  public int size() {
    return elements.size();
  }
}

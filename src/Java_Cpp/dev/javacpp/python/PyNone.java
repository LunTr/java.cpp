package dev.javacpp.python;

import java.util.AbstractCollection;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Python None as a runtime value. Extends AbstractCollection so template engines treat it as
 * falsy/empty while repr/type-name still report "None"/"NoneType".
 */
public final class PyNone extends AbstractCollection<Object> {

  public static final PyNone INSTANCE = new PyNone();

  private PyNone() {}

  @Override
  public Iterator<Object> iterator() {
    return new Iterator<>() {
      public boolean hasNext() {
        return false;
      }

      public Object next() {
        throw new NoSuchElementException();
      }
    };
  }

  @Override
  public int size() {
    return 0;
  }

  @Override
  public String toString() {
    return "None";
  }
}

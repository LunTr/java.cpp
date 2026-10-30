package io.github.yaulun.javacpp.python;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * CPython abstract object protocol over ordinary Java values. The representation is deliberately
 * Java-native: {@code null} is None, {@link Boolean} is bool, integral {@link Number} types and
 * {@link BigInteger} are int, {@link Float}/{@link Double} are float, {@link String} is str,
 * {@code byte[]} is bytes, {@link List} is list, {@link PyTuple} is tuple, {@link Map} is dict,
 * {@link Set} is set, and {@link Iterable} values are iterable. Custom objects join the protocols
 * through {@link PyCallable}, {@link PyAttributed}, {@link PyItemAccess}, {@link PySized},
 * {@link PyIndexed} and {@link PyComparable}. Every operation reproduces the pinned CPython error
 * category and message.
 */
public final class Py {
  private Py() {}

  /** Sentinel returned by {@link PyAttributed#attr} when the attribute is not defined. */
  public static final Object ABSENT = new Object();

  /** Sentinel returned by {@link PyComparable#pyCompare} for NotImplemented. */
  public static final Object NOT_IMPLEMENTED = new Object();

  /** Dynamic type categories used by the protocol dispatch. */
  public enum Type {
    NONE,
    BOOL,
    INT,
    FLOAT,
    STR,
    BYTES,
    LIST,
    TUPLE,
    DICT,
    SET,
    OTHER
  }

  /** Rich comparison operations matching PyObject_RichCompare opcodes. */
  public enum Op {
    LT,
    LE,
    EQ,
    NE,
    GT,
    GE
  }

  /** A slice key (start, stop, step) for {@link #getItem} and {@link #setItem}. */
  public record Slice(Object start, Object stop, Object step) {
    public static final Slice ALL = new Slice(null, null, null);
  }

  public static PythonException error(PythonErrorType type, String message) {
    return new PythonException(type, message, null);
  }

  public static Type typeOf(Object value) {
    if (value == null) return Type.NONE;
    if (value instanceof Boolean) return Type.BOOL;
    if (value instanceof BigInteger
        || value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) return Type.INT;
    if (value instanceof Double || value instanceof Float || value instanceof BigDecimal)
      return Type.FLOAT;
    if (value instanceof String) return Type.STR;
    if (value instanceof byte[]) return Type.BYTES;
    if (value instanceof PyTuple) return Type.TUPLE;
    if (value instanceof List) return Type.LIST;
    if (value instanceof Map) return Type.DICT;
    if (value instanceof Set) return Type.SET;
    return Type.OTHER;
  }

  /** Class name reported in Python error text for the value's dynamic type. */
  public static String typeName(Object value) {
    Type type = typeOf(value);
    if (type != Type.OTHER) {
      return switch (type) {
        case NONE -> "NoneType";
        case BOOL -> "bool";
        case INT -> "int";
        case FLOAT -> "float";
        case STR -> "str";
        case BYTES -> "bytes";
        case LIST -> "list";
        case TUPLE -> "tuple";
        case DICT -> "dict";
        case SET -> "set";
        default -> throw new AssertionError();
      };
    }
    if (value instanceof AdaptedIterator adapted) return adapted.pythonName;
    if (value instanceof PyAttributed attributed) return attributed.pyType();
    return value.getClass().getSimpleName();
  }

  /** Whether the value participates in CPython numeric slots (bool, int or float). */
  public static boolean isNumber(Object value) {
    Type type = typeOf(value);
    return type == Type.BOOL || type == Type.INT || type == Type.FLOAT;
  }

  // ---- Truth, length and iteration ---------------------------------------------------------

  /** PyObject_IsTrue: nb_bool, then __len__, then default true. */
  public static boolean truth(Object value) {
    if (value == null) return false;
    if (value instanceof Boolean flag) return flag;
    if (value instanceof BigInteger number) return number.signum() != 0;
    if (value instanceof Number number) return number.doubleValue() != 0;
    if (value instanceof PySized sized) return sized.pyLen() != 0;
    if (value instanceof Collection<?> values) return !values.isEmpty();
    if (value instanceof Map<?, ?> values) return !values.isEmpty();
    if (value instanceof String text) return !text.isEmpty();
    if (value instanceof byte[] bytes) return bytes.length != 0;
    return true;
  }

  /** PyObject_Length with the canonical TypeError for unsized values. */
  public static int len(Object value) {
    if (value instanceof String text) return text.codePointCount(0, text.length());
    if (value instanceof Collection<?> values) return values.size();
    if (value instanceof Map<?, ?> values) return values.size();
    if (value instanceof byte[] bytes) return bytes.length;
    if (value instanceof PySized sized) return sized.pyLen();
    throw error(
        PythonErrorType.TYPE_ERROR, "object of type '" + typeName(value) + "' has no len()");
  }

  /** iter(): tp_iter then sequence protocol; other values raise TypeError. */
  public static PyIterator iter(Object value) {
    if (value instanceof PyIterator iterator) return iterator;
    if (value instanceof Iterator<?> iterator) return adapt(iterator, "list_iterator");
    if (value instanceof PyTuple tuple) return adapt(tuple.iterator(), "tuple_iterator");
    if (value instanceof List<?> list) return adapt(list.iterator(), "list_iterator");
    if (value instanceof Set<?> set) return adapt(set.iterator(), "set_iterator");
    if (value instanceof Iterable<?> iterable) return adapt(iterable.iterator(), "list_iterator");
    if (value instanceof Map<?, ?> map) return adapt(map.keySet().iterator(), "dict_keyiterator");
    if (value instanceof String text) {
      Iterator<Integer> codePoints = text.codePoints().iterator();
      boolean ascii = text.codePoints().allMatch(cp -> cp < 128);
      return adapt(
          new Iterator<Object>() {
            public boolean hasNext() {
              return codePoints.hasNext();
            }

            public Object next() {
              return new String(Character.toChars(codePoints.next()));
            }
          },
          ascii ? "str_ascii_iterator" : "str_iterator");
    }
    if (value instanceof byte[] bytes) {
      return adapt(
          new Iterator<Object>() {
            private int position;

            public boolean hasNext() {
              return position < bytes.length;
            }

            public Object next() {
              return Byte.toUnsignedInt(bytes[position++]);
            }
          },
          "bytes_iterator");
    }
    throw error(
        PythonErrorType.TYPE_ERROR, "'" + typeName(value) + "' object is not iterable");
  }

  /** next(): returns the yielded value or raises StopIteration. */
  public static Object next(PyIterator iterator) {
    if (!iterator.hasNext())
      throw error(PythonErrorType.STOP_ITERATION, "");
    return iterator.next();
  }

  private static PyIterator adapt(Iterator<?> iterator, String pythonName) {
    return new AdaptedIterator(iterator, pythonName);
  }

  private static final class AdaptedIterator implements PyIterator {
    private final Iterator<?> iterator;
    final String pythonName;

    AdaptedIterator(Iterator<?> iterator, String pythonName) {
      this.iterator = iterator;
      this.pythonName = pythonName;
    }

    public boolean hasNext() {
      return iterator.hasNext();
    }

    public Object next() {
      if (!iterator.hasNext()) throw error(PythonErrorType.STOP_ITERATION, "");
      return iterator.next();
    }
  }

  /** Iterates the value, collecting into a new mutable list like list(value). */
  public static List<Object> list(Object value) {
    List<Object> result = new ArrayList<>();
    PyIterator iterator = iter(value);
    while (iterator.hasNext()) result.add(iterator.next());
    return result;
  }

  /** tuple(value): a tuple is returned unchanged, other iterables are copied. */
  public static PyTuple<Object> tuple(Object value) {
    if (value instanceof PyTuple) {
      @SuppressWarnings("unchecked")
      PyTuple<Object> same = (PyTuple<Object>) value;
      return same;
    }
    return PyTuple.wrap(list(value));
  }

  // ---- Item access -------------------------------------------------------------------------

  /** obj[key]: mapping, sequence and custom subscription with CPython error text. */
  public static Object getItem(Object container, Object key) {
    if (container instanceof PyItemAccess access) return access.pyGet(key);
    if (container instanceof Map<?, ?> map) {
      if (!map.containsKey(key))
        throw error(PythonErrorType.KEY_ERROR, repr(key));
      return map.get(key);
    }
    if (key instanceof Slice slice) {
      if (container instanceof byte[] || container instanceof List || container instanceof String)
        return slice(container, slice.start(), slice.stop(), slice.step());
      if (container == null)
        throw error(PythonErrorType.TYPE_ERROR, "'NoneType' object is not subscriptable");
      throw error(
          PythonErrorType.TYPE_ERROR, "'" + typeName(container) + "' object is not subscriptable");
    }
    if (container instanceof List<?> list) {
      String kind = container instanceof PyTuple ? "tuple" : "list";
      int index = sequenceIndex(key, list.size(), kind);
      return list.get(index);
    }
    if (container instanceof String text) {
      int size = text.codePointCount(0, text.length());
      int index = stringIndex(key, size);
      int position = text.offsetByCodePoints(0, index);
      return new String(Character.toChars(text.codePointAt(position)));
    }
    if (container instanceof byte[] bytes) {
      int index = byteIndex(key, bytes.length);
      return Byte.toUnsignedInt(bytes[index]);
    }
    throw error(
        PythonErrorType.TYPE_ERROR, "'" + typeName(container) + "' object is not subscriptable");
  }

  /** obj[key] = value: mapping and mutable sequence assignment. */
  @SuppressWarnings("unchecked")
  public static void setItem(Object container, Object key, Object value) {
    if (container instanceof PyItemAccess access) {
      access.pySet(key, value);
      return;
    }
    if (container instanceof Map) {
      ((Map<Object, Object>) container).put(key, value);
      return;
    }
    if (container instanceof PyTuple) {
      throw error(
          PythonErrorType.TYPE_ERROR, "'tuple' object does not support item assignment");
    }
    if (container instanceof List list) {
      if (key instanceof Slice slice) {
        assignSlice(list, slice, value);
        return;
      }
      int index = sequenceIndexForAssign(key, list.size());
      list.set(index, value);
      return;
    }
    throw error(
        PythonErrorType.TYPE_ERROR,
        "'" + typeName(container) + "' object does not support item assignment");
  }

  /** del obj[key]: mapping and mutable sequence deletion. */
  @SuppressWarnings("unchecked")
  public static void delItem(Object container, Object key) {
    if (container instanceof Map) {
      Map<Object, Object> map = (Map<Object, Object>) container;
      if (!map.containsKey(key)) throw error(PythonErrorType.KEY_ERROR, repr(key));
      map.remove(key);
      return;
    }
    if (container instanceof PyTuple || container instanceof String || container instanceof byte[]) {
      throw error(
          PythonErrorType.TYPE_ERROR,
          "'" + typeName(container) + "' object doesn't support item deletion");
    }
    if (container instanceof List list) {
      int index = sequenceIndexForAssign(key, list.size());
      list.remove(index);
      return;
    }
    if (container instanceof PyItemAccess)
      throw error(
          PythonErrorType.TYPE_ERROR,
          "'" + typeName(container) + "' object doesn't support item deletion");
    throw error(
        PythonErrorType.TYPE_ERROR,
        "'" + typeName(container) + "' object does not support item deletion");
  }

  /** item in container: membership test with iteration fallback. */
  public static boolean contains(Object container, Object item) {
    if (container instanceof PyItemAccess access) return access.pyContains(item);
    if (container instanceof Map<?, ?> map) return map.containsKey(item);
    if (container instanceof String text) {
      if (!(item instanceof String piece))
        throw error(
            PythonErrorType.TYPE_ERROR,
            "'in <string>' requires string as left operand, not " + typeName(item));
      return text.contains(piece);
    }
    if (container instanceof byte[] bytes) {
      if (item instanceof byte[] piece) {
        outer:
        for (int i = 0; i <= bytes.length - piece.length; i++) {
          for (int j = 0; j < piece.length; j++) if (bytes[i + j] != piece[j]) continue outer;
          return true;
        }
        return false;
      }
      BigInteger index;
      try {
        index = index(item);
      } catch (PythonException failure) {
        throw error(
            PythonErrorType.TYPE_ERROR,
            "a bytes-like object is required, not '" + typeName(item) + "'");
      }
      if (index.signum() < 0 || index.compareTo(BigInteger.valueOf(255)) > 0)
        throw error(PythonErrorType.VALUE_ERROR, "byte must be in range(0, 256)");
      for (byte b : bytes) if (Byte.toUnsignedInt(b) == index.intValue()) return true;
      return false;
    }
    if (container instanceof List<?> list) {
      for (Object element : list) if (equal(element, item)) return true;
      return false;
    }
    if (container instanceof Set<?> set) {
      for (Object element : set) if (equal(element, item)) return true;
      return false;
    }
    if (container instanceof Iterable<?> iterable) {
      for (Object element : iterable) if (equal(element, item)) return true;
      return false;
    }
    throw error(
        PythonErrorType.TYPE_ERROR,
        "argument of type '" + typeName(container) + "' is not iterable");
  }

  private static int sequenceIndex(Object key, int size, String kind) {
    BigInteger index;
    try {
      index = index(key);
    } catch (PythonException failure) {
      throw error(
          PythonErrorType.TYPE_ERROR,
          kind + " indices must be integers or slices, not " + typeName(key));
    }
    return checkedIndex(index, size, kind + " index out of range");
  }

  private static int sequenceIndexForAssign(Object key, int size) {
    BigInteger index;
    try {
      index = index(key);
    } catch (PythonException failure) {
      throw error(
          PythonErrorType.TYPE_ERROR,
          "list indices must be integers or slices, not " + typeName(key));
    }
    return checkedIndex(index, size, "list assignment index out of range");
  }

  private static int stringIndex(Object key, int size) {
    BigInteger index;
    try {
      index = index(key);
    } catch (PythonException failure) {
      throw error(
          PythonErrorType.TYPE_ERROR,
          "string indices must be integers, not '" + typeName(key) + "'");
    }
    return checkedIndex(index, size, "string index out of range");
  }

  private static int byteIndex(Object key, int size) {
    BigInteger index;
    try {
      index = index(key);
    } catch (PythonException failure) {
      throw error(
          PythonErrorType.TYPE_ERROR,
          "byte indices must be integers or slices, not " + typeName(key));
    }
    return checkedIndex(index, size, "index out of range");
  }

  private static int checkedIndex(BigInteger index, int size, String message) {
    BigInteger position = index.signum() < 0 ? index.add(BigInteger.valueOf(size)) : index;
    if (position.signum() < 0 || position.compareTo(BigInteger.valueOf(size)) >= 0)
      throw error(PythonErrorType.INDEX_ERROR, message);
    return position.intValue();
  }

  private static void assignSlice(List<Object> list, Slice slice, Object value) {
    List<Object> items = list(value);
    int size = list.size();
    BigInteger stride = slice.step() == null ? BigInteger.ONE : index(slice.step());
    if (stride.signum() == 0)
      throw error(PythonErrorType.VALUE_ERROR, "slice step cannot be zero");
    boolean reverse = stride.signum() < 0;
    int first =
        slice.start() == null
            ? (reverse ? size - 1 : 0)
            : bound(index(slice.start()), size, reverse);
    int last =
        slice.stop() == null
            ? (reverse ? -1 : size)
            : bound(index(slice.stop()), size, reverse);
    long distance = stride.abs().min(BigInteger.valueOf((long) size + 1)).longValue();
    if (reverse) distance = -distance;
    int count = 0;
    for (long i = first; reverse ? i > last : i < last; i += distance) count++;
    if (distance != 1 && items.size() != count)
      throw error(
          PythonErrorType.VALUE_ERROR,
          "attempt to assign sequence of size "
              + items.size()
              + " to extended slice of size "
              + count);
    if (distance == 1) {
      list.subList(first, Math.max(first, last)).clear();
      list.addAll(first, items);
      return;
    }
    int n = 0;
    for (long i = first; reverse ? i > last : i < last; i += distance)
      list.set((int) i, items.get(n++));
  }

  /** CPython slice-index clamping shared by the ports. */
  public static int bound(BigInteger value, int size, boolean reverse) {
    if (value.signum() < 0) value = value.add(BigInteger.valueOf(size));
    return value
        .max(BigInteger.valueOf(reverse ? -1 : 0))
        .min(BigInteger.valueOf(reverse ? size - 1 : size))
        .intValue();
  }

  // ---- Attributes and calls -----------------------------------------------------------------

  /** obj.name attribute lookup through the PyAttributed protocol. */
  public static Object getAttr(Object owner, String name) {
    if (owner instanceof PyAttributed attributed) {
      Object value = attributed.attr(name);
      if (value != ABSENT) return value;
    }
    throw error(
        PythonErrorType.ATTRIBUTE_ERROR,
        "'" + typeName(owner) + "' object has no attribute '" + name + "'");
  }

  /** callable(*args, **kwargs): PyCallable and common Java functional types. */
  @SuppressWarnings("unchecked")
  public static Object call(Object callable, List<?> arguments, Map<String, ?> keywords) {
    if (callable instanceof PyCallable pyCallable)
      return pyCallable.call(arguments, keywords);
    if (keywords != null && !keywords.isEmpty())
      throw error(
          PythonErrorType.TYPE_ERROR,
          "'" + typeName(callable) + "' object is not callable with keyword arguments");
    if (callable instanceof Supplier<?> supplier && arguments.isEmpty()) return supplier.get();
    if (callable instanceof java.util.function.Function<?, ?> function && arguments.size() == 1)
      return ((java.util.function.Function<Object, Object>) function).apply(arguments.get(0));
    if (callable instanceof java.util.function.BiFunction<?, ?, ?> function
        && arguments.size() == 2)
      return ((java.util.function.BiFunction<Object, Object, Object>) function)
          .apply(arguments.get(0), arguments.get(1));
    throw error(
        PythonErrorType.TYPE_ERROR, "'" + typeName(callable) + "' object is not callable");
  }

  public static Object call(Object callable, Object... arguments) {
    return call(callable, List.of(arguments), null);
  }

  // ---- Comparison ---------------------------------------------------------------------------

  /** Python equality (==): custom __eq__ first, then builtin value equality, then identity. */
  public static boolean equal(Object left, Object right) {
    if (left == right) return true;
    if (left instanceof PyComparable comparable) {
      Object result = comparable.pyCompare(right, Op.EQ);
      if (result != NOT_IMPLEMENTED) return truth(result);
    }
    if (right instanceof PyComparable comparable) {
      Object result = comparable.pyCompare(left, Op.EQ);
      if (result != NOT_IMPLEMENTED) return truth(result);
    }
    Type a = typeOf(left), b = typeOf(right);
    if (isNumber(left) && isNumber(right)) return numericCompare(left, right) == 0;
    if (a != b) return false;
    return switch (a) {
      case NONE -> true;
      case BOOL, INT, FLOAT, STR -> left.equals(right);
      case BYTES -> java.util.Arrays.equals((byte[]) left, (byte[]) right);
      case LIST, TUPLE -> {
        List<?> x = (List<?>) left, y = (List<?>) right;
        if (x.size() != y.size()) yield false;
        for (int i = 0; i < x.size(); i++) if (!equal(x.get(i), y.get(i))) yield false;
        yield true;
      }
      case DICT -> {
        Map<?, ?> x = (Map<?, ?>) left, y = (Map<?, ?>) right;
        if (x.size() != y.size()) yield false;
        for (var entry : x.entrySet()) {
          Object match = NOT_IMPLEMENTED;
          for (var other : y.entrySet())
            if (equal(entry.getKey(), other.getKey())) {
              match = other.getValue();
              break;
            }
          if (match == NOT_IMPLEMENTED || !equal(entry.getValue(), match)) yield false;
        }
        yield true;
      }
      case SET -> {
        Set<?> x = (Set<?>) left, y = (Set<?>) right;
        if (x.size() != y.size()) yield false;
        outer:
        for (Object element : x) {
          for (Object other : y) if (equal(element, other)) continue outer;
          yield false;
        }
        yield true;
      }
      default -> false;
    };
  }

  /** PyObject_RichCompare over the supported operations. */
  public static boolean compare(Object left, Op operation, Object right) {
    if (operation == Op.EQ) return equal(left, right);
    if (operation == Op.NE) return !equal(left, right);
    if (isNumber(left) && isNumber(right)) {
      int result = numericCompare(left, right);
      if (result == Integer.MIN_VALUE) return false;
      return switch (operation) {
        case LT -> result < 0;
        case LE -> result <= 0;
        case GT -> result > 0;
        case GE -> result >= 0;
        default -> throw new AssertionError();
      };
    }
    Type a = typeOf(left), b = typeOf(right);
    if (a == b) {
      switch (a) {
        case STR:
          return ordering(codePointCompare((String) left, (String) right), operation);
        case BYTES:
          return ordering(bytesCompare((byte[]) left, (byte[]) right), operation);
        case LIST:
        case TUPLE:
          return ordering(sequenceCompare((List<?>) left, (List<?>) right), operation);
        default:
      }
    }
    if (left instanceof PyComparable comparable) {
      Object result = comparable.pyCompare(right, operation);
      if (result != NOT_IMPLEMENTED) return truth(result);
    }
    if (right instanceof PyComparable comparable) {
      Object result = comparable.pyCompare(left, reflected(operation));
      if (result != NOT_IMPLEMENTED) return truth(result);
    }
    throw error(
        PythonErrorType.TYPE_ERROR,
        "'"
            + operationSymbol(operation)
            + "' not supported between instances of '"
            + typeName(left)
            + "' and '"
            + typeName(right)
            + "'");
  }

  private static String operationSymbol(Op operation) {
    return switch (operation) {
      case LT -> "<";
      case LE -> "<=";
      case EQ -> "==";
      case NE -> "!=";
      case GT -> ">";
      case GE -> ">=";
    };
  }

  private static boolean ordering(int result, Op operation) {
    return switch (operation) {
      case LT -> result < 0;
      case LE -> result <= 0;
      case GT -> result > 0;
      case GE -> result >= 0;
      default -> throw new AssertionError();
    };
  }

  private static Op reflected(Op operation) {
    return switch (operation) {
      case LT -> Op.GT;
      case LE -> Op.GE;
      case GT -> Op.LT;
      case GE -> Op.LE;
      default -> operation;
    };
  }

  /**
   * Exact numeric ordering; returns Integer.MIN_VALUE when either side is NaN, where every
   * comparison is false and != is true.
   */
  private static int numericCompare(Object left, Object right) {
    double l = floatingOrNull(left), r = floatingOrNull(right);
    if (Double.isNaN(l) || Double.isNaN(r)) return Integer.MIN_VALUE;
    if (!isFloat(left) && !isFloat(right))
      return integer(left).compareTo(integer(right));
    if (left instanceof BigDecimal || right instanceof BigDecimal)
      return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
    if (isFloat(left) && isFloat(right)) return l < r ? -1 : l > r ? 1 : 0;
    if (isFloat(left)) {
      if (Double.isInfinite(l)) return l < 0 ? -1 : 1;
      return new BigDecimal(l).compareTo(new BigDecimal(integer(right)));
    }
    if (Double.isInfinite(r)) return r < 0 ? 1 : -1;
    return new BigDecimal(integer(left)).compareTo(new BigDecimal(r));
  }

  private static boolean isFloat(Object value) {
    return value instanceof Double || value instanceof Float || value instanceof BigDecimal;
  }

  private static double floatingOrNull(Object value) {
    if (value instanceof Number number) return number.doubleValue();
    return Double.NaN;
  }

  private static int codePointCompare(String left, String right) {
    Iterator<Integer> a = left.codePoints().iterator(), b = right.codePoints().iterator();
    while (a.hasNext() && b.hasNext()) {
      int result = Integer.compare(a.next(), b.next());
      if (result != 0) return result;
    }
    return Integer.compare(
        left.codePointCount(0, left.length()), right.codePointCount(0, right.length()));
  }

  private static int bytesCompare(byte[] left, byte[] right) {
    int shared = Math.min(left.length, right.length);
    for (int i = 0; i < shared; i++) {
      int result = Integer.compare(Byte.toUnsignedInt(left[i]), Byte.toUnsignedInt(right[i]));
      if (result != 0) return result;
    }
    return Integer.compare(left.length, right.length);
  }

  private static int sequenceCompare(List<?> left, List<?> right) {
    int shared = Math.min(left.size(), right.size());
    for (int i = 0; i < shared; i++) {
      if (!equal(left.get(i), right.get(i))) {
        return compare(left.get(i), Op.LT, right.get(i)) ? -1 : 1;
      }
    }
    return Integer.compare(left.size(), right.size());
  }

  // ---- Arithmetic ---------------------------------------------------------------------------

  /** a + b: numeric addition or same-kind sequence concatenation. */
  public static Object add(Object left, Object right) {
    if (isNumber(left) && isNumber(right)) {
      if (isFloat(left) || isFloat(right))
        return floatingOrNull(left) + floatingOrNull(right);
      return integer(left).add(integer(right));
    }
    if (left instanceof String) {
      if (!(right instanceof String))
        throw error(
            PythonErrorType.TYPE_ERROR,
            "can only concatenate str (not \"" + typeName(right) + "\") to str");
      return (String) left + right;
    }
    if (left instanceof List) {
      if (!(right instanceof List) || (right instanceof PyTuple) != (left instanceof PyTuple))
        throw error(
            PythonErrorType.TYPE_ERROR,
            "can only concatenate "
                + typeName(left)
                + " (not \""
                + typeName(right)
                + "\") to "
                + typeName(left));
      List<Object> result = new ArrayList<>((List<?>) left);
      result.addAll((List<?>) right);
      return left instanceof PyTuple ? PyTuple.wrap(result) : result;
    }
    if (left instanceof byte[] bytes) {
      if (!(right instanceof byte[] tail))
        throw error(
            PythonErrorType.TYPE_ERROR,
            "can't concat " + typeName(right) + " to bytes");
      byte[] result = java.util.Arrays.copyOf(bytes, bytes.length + tail.length);
      System.arraycopy(tail, 0, result, bytes.length, tail.length);
      return result;
    }
    throw error(
        PythonErrorType.TYPE_ERROR,
        "unsupported operand type(s) for +: '"
            + typeName(left)
            + "' and '"
            + typeName(right)
            + "'");
  }

  /** a - b: numeric subtraction with the canonical TypeError. */
  public static Object subtract(Object left, Object right) {
    if (isNumber(left) && isNumber(right)) {
      if (isFloat(left) || isFloat(right))
        return floatingOrNull(left) - floatingOrNull(right);
      return integer(left).subtract(integer(right));
    }
    throw error(
        PythonErrorType.TYPE_ERROR,
        "unsupported operand type(s) for -: '"
            + typeName(left)
            + "' and '"
            + typeName(right)
            + "'");
  }

  /** a * b: numeric multiplication or sequence repetition. */
  public static Object multiply(Object left, Object right) {
    if (isNumber(left) && isNumber(right)) {
      if (isFloat(left) || isFloat(right))
        return floatingOrNull(left) * floatingOrNull(right);
      return integer(left).multiply(integer(right));
    }
    if (left instanceof List || left instanceof String || left instanceof byte[])
      return repeat(left, right);
    if (right instanceof List || right instanceof String || right instanceof byte[])
      return repeat(right, left);
    throw error(
        PythonErrorType.TYPE_ERROR,
        "unsupported operand type(s) for *: '"
            + typeName(left)
            + "' and '"
            + typeName(right)
            + "'");
  }

  private static Object repeat(Object sequence, Object count) {
    BigInteger times;
    try {
      times = index(count);
    } catch (PythonException failure) {
      throw error(
          PythonErrorType.TYPE_ERROR,
          "can't multiply sequence by non-int of type '" + typeName(count) + "'");
    }
    int n = times.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0
        ? Integer.MAX_VALUE
        : Math.max(0, times.intValue());
    if (sequence instanceof String text) {
      StringBuilder result = new StringBuilder();
      for (int i = 0; i < n; i++) result.append(text);
      return result.toString();
    }
    if (sequence instanceof byte[] bytes) {
      byte[] result = new byte[bytes.length * n];
      for (int i = 0; i < n; i++) System.arraycopy(bytes, 0, result, i * bytes.length, bytes.length);
      return result;
    }
    List<?> source = (List<?>) sequence;
    List<Object> result = new ArrayList<>(source.size() * n);
    for (int i = 0; i < n; i++) result.addAll(source);
    return sequence instanceof PyTuple ? PyTuple.wrap(result) : result;
  }

  /** -a: unary minus over numbers. */
  public static Object negate(Object value) {
    if (value instanceof Boolean flag) return flag ? BigInteger.valueOf(-1) : BigInteger.ZERO;
    if (isFloat(value)) return -floatingOrNull(value);
    if (typeOf(value) == Type.INT) return integer(value).negate();
    throw error(
        PythonErrorType.TYPE_ERROR,
        "bad operand type for unary -: '" + typeName(value) + "'");
  }

  // ---- Numeric conversion --------------------------------------------------------------------

  /** int(value): __index__, integer truncation of float, bool, and string/bytes parsing. */
  public static BigInteger integer(Object value) {
    if (value instanceof Boolean flag) return flag ? BigInteger.ONE : BigInteger.ZERO;
    if (value instanceof BigInteger number) return number;
    if (value instanceof PyIndexed indexed) return indexed.pyIndex();
    if (value instanceof BigDecimal number) return number.toBigInteger();
    if (value instanceof Float || value instanceof Double) {
      double number = ((Number) value).doubleValue();
      if (Double.isNaN(number))
        throw error(PythonErrorType.VALUE_ERROR, "cannot convert float NaN to integer");
      if (Double.isInfinite(number))
        throw error(PythonErrorType.OVERFLOW_ERROR, "cannot convert float infinity to integer");
      return new BigDecimal(number).toBigInteger();
    }
    if (value instanceof Number number) return BigInteger.valueOf(number.longValue());
    if (value instanceof String || value instanceof byte[]) {
      String text = decimalText(value);
      if (text.matches("[+-]?[0-9]+(?:_[0-9]+)*"))
        return new BigInteger(text.replace("_", ""));
      throw error(
          PythonErrorType.VALUE_ERROR,
          "invalid literal for int() with base 10: " + representation(value));
    }
    throw error(
        PythonErrorType.TYPE_ERROR,
        "int() argument must be a string, a bytes-like object or a real number, not '"
            + typeName(value)
            + "'");
  }

  /** float(value): numeric conversion and string/bytes parsing. */
  public static double floating(Object value) {
    if (value instanceof Boolean flag) return flag ? 1 : 0;
    if (value instanceof Number number) {
      double result = number.doubleValue();
      if (value instanceof BigInteger && Double.isInfinite(result))
        throw error(PythonErrorType.OVERFLOW_ERROR, "int too large to convert to float");
      return result;
    }
    if (value instanceof String || value instanceof byte[]) {
      String text = decimalText(value), lower = text.toLowerCase(Locale.ROOT);
      if (lower.matches("[+-]?nan"))
        return lower.startsWith("-") ? Double.longBitsToDouble(0xfff8000000000000L) : Double.NaN;
      if (lower.matches("[+-]?inf(?:inity)?"))
        return lower.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
      String digits = "[0-9](?:_?[0-9])*";
      if (text.matches(
          "[+-]?(?:"
              + digits
              + "(?:\\.(?:"
              + digits
              + ")?)?|\\."
              + digits
              + ")(?:[eE][+-]?"
              + digits
              + ")?")) return Double.parseDouble(text.replace("_", ""));
      throw error(
          PythonErrorType.VALUE_ERROR,
          "could not convert string to float: " + representation(value));
    }
    throw error(
        PythonErrorType.TYPE_ERROR,
        "float() argument must be a string or a real number, not '" + typeName(value) + "'");
  }

  /** PyNumber_Index: exact integer conversion for index positions. */
  public static BigInteger index(Object value) {
    if (value instanceof Boolean
        || value instanceof BigInteger
        || value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) return integer(value);
    if (value instanceof PyIndexed indexed) return indexed.pyIndex();
    throw error(
        PythonErrorType.TYPE_ERROR,
        "'" + typeName(value) + "' object cannot be interpreted as an integer");
  }

  /** CPython c_int-style conversion: __index__ then a signed 32-bit range check. */
  public static int cInt(Object value) {
    BigInteger number = index(value);
    try {
      return number.intValueExact();
    } catch (ArithmeticException overflow) {
      throw error(
          PythonErrorType.OVERFLOW_ERROR, "Python int too large to convert to C long");
    }
  }

  /** a or b: returns the operand, not a boolean. */
  public static Object orElse(Object left, Supplier<Object> right) {
    return truth(left) ? left : right.get();
  }

  /** a and b: returns the operand, not a boolean. */
  public static Object andThen(Object left, Supplier<Object> right) {
    return truth(left) ? right.get() : left;
  }

  /** obj[start:stop:step] for list, tuple, str and bytes receivers. */
  public static Object slice(Object value, Object start, Object stop, Object step) {
    if (value instanceof byte[] bytes) {
      List<?> indexes = (List<?>) PythonSlices.slice(list(value), start, stop, step);
      byte[] result = new byte[indexes.size()];
      for (int i = 0; i < indexes.size(); i++) result[i] = (byte) (int) indexes.get(i);
      return result;
    }
    Object result = PythonSlices.slice(value, start, stop, step);
    if (value instanceof PyTuple) return PyTuple.wrap(castList(result));
    return result;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> castList(Object value) {
    return (List<Object>) value;
  }

  // ---- Text ---------------------------------------------------------------------------------

  /** str(value): str returns the string itself; other values format through repr. */
  public static String str(Object value) {
    if (value instanceof String text) return text;
    return repr(value);
  }

  /** repr(value): CPython repr for the built-in representation types. */
  public static String repr(Object value) {
    if (value == null) return "None";
    if (value instanceof Boolean flag) return flag ? "True" : "False";
    if (value instanceof Double || value instanceof Float)
      return floatRepr(((Number) value).doubleValue());
    if (value instanceof byte[] bytes) return PythonBytes.repr(bytes);
    if (value instanceof String text) {
      char quote = text.indexOf('\'') >= 0 && text.indexOf('"') < 0 ? '"' : '\'';
      StringBuilder result = new StringBuilder().append(quote);
      text.codePoints()
          .forEach(
              cp -> {
                if (cp == quote || cp == '\\') result.append('\\').appendCodePoint(cp);
                else if (cp == '\n') result.append("\\n");
                else if (cp == '\r') result.append("\\r");
                else if (cp == '\t') result.append("\\t");
                else if (!PythonUnicode.printable(cp)) {
                  result.append(
                      String.format(
                          Locale.ROOT,
                          cp <= 255 ? "\\x%02x" : cp <= 65535 ? "\\u%04x" : "\\U%08x",
                          cp));
                } else result.appendCodePoint(cp);
              });
      return result.append(quote).toString();
    }
    if (value instanceof List<?> values) {
      StringBuilder result = new StringBuilder(value instanceof PyTuple ? "(" : "[");
      for (int i = 0; i < values.size(); i++) {
        if (i > 0) result.append(", ");
        result.append(repr(values.get(i)));
      }
      if (value instanceof PyTuple)
        return result.append(values.size() == 1 ? ",)" : ")").toString();
      return result.append("]").toString();
    }
    if (value instanceof Map<?, ?> values) {
      StringBuilder result = new StringBuilder("{");
      boolean first = true;
      for (var entry : values.entrySet()) {
        if (!first) result.append(", ");
        first = false;
        result.append(repr(entry.getKey())).append(": ").append(repr(entry.getValue()));
      }
      return result.append("}").toString();
    }
    if (value instanceof Set<?> values) {
      if (values.isEmpty()) return "set()";
      StringBuilder result = new StringBuilder("{");
      boolean first = true;
      for (Object element : values) {
        if (!first) result.append(", ");
        first = false;
        result.append(repr(element));
      }
      return result.append("}").toString();
    }
    return value.toString();
  }

  private static String floatRepr(double value) {
    if (Double.isNaN(value)) return "nan";
    if (value == Double.POSITIVE_INFINITY) return "inf";
    if (value == Double.NEGATIVE_INFINITY) return "-inf";
    if (value == 0) return Double.doubleToRawLongBits(value) < 0 ? "-0.0" : "0.0";
    BigDecimal exact = new BigDecimal(value);
    BigDecimal shortest = exact;
    for (int precision = 1; precision <= 17; precision++) {
      BigDecimal candidate = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
      if (Double.doubleToRawLongBits(candidate.doubleValue())
          == Double.doubleToRawLongBits(value)) {
        shortest = candidate.stripTrailingZeros();
        break;
      }
    }
    int exponent = shortest.precision() - shortest.scale() - 1;
    if (exponent < -4 || exponent >= 16) {
      String mantissa = shortest.movePointLeft(exponent).toPlainString();
      return mantissa
          + "e"
          + (exponent >= 0 ? "+" : "-")
          + String.format(Locale.ROOT, "%02d", Math.abs(exponent));
    }
    String result = shortest.toPlainString();
    return result.contains(".") ? result : result + ".0";
  }

  private static String representation(Object value) {
    return value instanceof byte[] bytes ? PythonBytes.repr(bytes) : repr(value);
  }

  private static String decimalText(Object value) {
    String text =
        value instanceof byte[] bytes
            ? new String(bytes, StandardCharsets.ISO_8859_1)
            : (String) value;
    int start = 0, end = text.length();
    while (start < end && space(text.codePointAt(start)))
      start += Character.charCount(text.codePointAt(start));
    while (end > start && space(text.codePointBefore(end)))
      end -= Character.charCount(text.codePointBefore(end));
    StringBuilder result = new StringBuilder();
    for (int cp : text.substring(start, end).codePoints().toArray()) {
      if (value instanceof String && Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER)
        result.append(Character.digit(cp, 10));
      else result.appendCodePoint(cp);
    }
    return result.toString();
  }

  private static boolean space(int cp) {
    return cp < 128
        ? cp == 32 || (cp >= 9 && cp <= 13)
        : Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85;
  }

  // ---- Miscellany ----------------------------------------------------------------------------

  /** Cannot-instantiate-abstract-class TypeError with the CPython 3.12+ message. */
  public static PythonException abstractError(String className, List<String> methods) {
    List<String> sorted = new ArrayList<>(methods);
    sorted.sort(null);
    String joined = String.join(", ", sorted.stream().map(m -> "'" + m + "'").toList());
    String plural = sorted.size() == 1 ? "abstract method " : "abstract methods ";
    return error(
        PythonErrorType.TYPE_ERROR,
        "Can't instantiate abstract class "
            + className
            + " without an implementation for "
            + plural
            + joined);
  }

  /** Ordered dict view: a mutable insertion-ordered map (collections.OrderedDict). */
  public static <K, V> LinkedHashMap<K, V> orderedMap() {
    return new LinkedHashMap<>();
  }
}

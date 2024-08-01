package de.tudarmstadt.esa.treenail.codegen;

import com.minres.coredsl.analysis.AnalysisContext;
import com.minres.coredsl.coreDsl.Expression;
import com.minres.coredsl.coreDsl.IntegerConstant;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.type.ArrayType;
import com.minres.coredsl.type.IntegerType;
import com.minres.coredsl.util.TypedBigInteger;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class ConstructionContext {
  private final Set<NamedEntity> updatedEntities = new LinkedHashSet<>();

  private final Map<NamedEntity, MLIRValue> values;
  private final AtomicInteger counter;
  private final AnalysisContext ac;
  private final StringBuilder sb;

  private boolean terminatorWasEmitted = false;

  ConstructionContext(Map<NamedEntity, MLIRValue> values, AtomicInteger counter,
                      AnalysisContext ac, StringBuilder sb) {
    this.values = values;
    this.counter = counter;
    this.ac = ac;
    this.sb = sb;
  }

  boolean isConstant(Expression expr) {
    return expr instanceof IntegerConstant || ac.isExpressionValueSet(expr);
  }

  // This method ensures that the return value is a standard `BigInteger`, as
  // the frontend's `TypedBigInteger` has a `toString()` method that is
  // unsuitable for use here.
  static BigInteger ensureBigInteger(BigInteger value, MLIRType targetType) {
    if (value == null)
      return null;
    if (value instanceof TypedBigInteger)
      value = new BigInteger(value.toByteArray());

    // Signed type edge case handling
    if (targetType != null && targetType.isSigned && value.signum() > 0 &&
        BigInteger.ONE.shiftLeft(targetType.width - 1).equals(value)) {
      // If value equals minValue, we have to negate the value to make it
      // representable by the target type
      return value.negate();
    }

    return value;
  }

  BigInteger getConstantValue(Expression expr, MLIRType type) {
    return ensureBigInteger(expr instanceof IntegerConstant
                                ? ((IntegerConstant)expr).getValue()
                                : ac.getExpressionValue(expr).getValue(),
                            type);
  }

  IntegerType getElementType(NamedEntity ent) {
    var type = ac.getDeclaredType(ent);
    assert type.isArrayType();
    var arrayType = (ArrayType)type;
    assert arrayType.elementType.isIntegerType();
    return (IntegerType)arrayType.elementType;
  }

  boolean hasValue(NamedEntity ent) { return values.containsKey(ent); }

  MLIRValue getValue(NamedEntity ent) { return values.get(ent); }

  void setValue(NamedEntity ent, MLIRValue value) {
    values.put(ent, value);
    updatedEntities.add(ent);
  }

  MLIRValue makeAnonymousValue(MLIRType type) {
    return new MLIRValue(Integer.toString(counter.getAndIncrement()), type);
  }

  MLIRValue makeConst(BigInteger value, MLIRType type) {
    assert !(value instanceof TypedBigInteger);
    var result = makeAnonymousValue(type);
    emitLn("%s = hwarith.constant %d : %s", result, value, type);
    return result;
  }

  MLIRValue makeIndexConst(BigInteger value) {
    assert !(value instanceof TypedBigInteger);
    var result = makeAnonymousValue(MLIRType.getType(64, false));
    emitLn("%s = arith.constant %d : index", result, value);
    return result;
  }

  MLIRValue makeCast(MLIRValue value, MLIRType type) {
    if (type == value.type)
      return value;

    var result = makeAnonymousValue(type);
    emitLn("%s = coredsl.cast %s : %s to %s", result, value, value.type, type);
    return result;
  }

  MLIRValue makeI1Cast(MLIRValue value) {
    var result = makeAnonymousValue(MLIRType.DUMMY);
    emitLn("%s = coredsl.cast %s : %s to i1", result, value, value.type);
    return result;
  }

  MLIRValue makeIndexCast(MLIRValue value, MLIRType type) {
    var temp = makeAnonymousValue(MLIRType.DUMMY);
    var result = makeAnonymousValue(type);
    emitLn("%s = arith.index_castui %s : index to i%d", temp, value,
           type.width);
    emitLn("%s = hwarith.cast %s : (i%d) -> %s", result, temp, type.width,
           type);
    return result;
  }

  void emitLn(String format, Object... args) {
    sb.append(String.format(format, args)).append('\n');
  }

  Set<NamedEntity> getUpdatedEntities() {
    return Collections.unmodifiableSet(updatedEntities);
  }

  Map<NamedEntity, MLIRValue> getValues() {
    return Collections.unmodifiableMap(values);
  }

  int getCounter() { return counter.get(); }

  boolean getTerminatorWasEmitted() { return terminatorWasEmitted; }

  void setTerminatorWasEmitted() { terminatorWasEmitted = true; }

  AnalysisContext getAnalysisContext() { return ac; }

  StringBuilder getStringBuilder() { return sb; }
}

package de.tudarmstadt.esa.treenail.codegen;

import com.minres.coredsl.analysis.AnalysisContext;
import com.minres.coredsl.analysis.CoreDslConstantExpressionEvaluator;
import com.minres.coredsl.coreDsl.Expression;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.type.ArrayType;
import com.minres.coredsl.type.IntegerType;
import com.minres.coredsl.util.TypedBigInteger;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class ConstructionContext {
  private final Set<NamedEntity> updatedEntities = new LinkedHashSet<>();

  private final Map<NamedEntity, MLIRValue> values;
  private final AtomicInteger valueCounter;
  private final AnalysisContext ac;
  private final StringBuilder sb;

  private boolean terminatorWasEmitted = false;

  ConstructionContext(Map<NamedEntity, MLIRValue> values,
                      AtomicInteger valueCounter, AnalysisContext ac,
                      StringBuilder sb) {
    this.values = values;
    this.valueCounter = valueCounter;
    this.ac = ac;
    this.sb = sb;
  }

  boolean isConstant(Expression expr) {
    var constExprEvalRes =
        CoreDslConstantExpressionEvaluator.tryEvaluate(ac, expr);
    return constExprEvalRes.isValid();
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
    var constExprEvalRes =
        CoreDslConstantExpressionEvaluator.evaluate(ac, expr);
    assert constExprEvalRes.isValid();
    return ensureBigInteger(constExprEvalRes.getValue(), type);
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
    return new MLIRValue(Integer.toString(valueCounter.getAndIncrement()),
                         type);
  }

  MLIRValue makeConst(BigInteger value, MLIRType type) {
    assert !(value instanceof TypedBigInteger);
    var result = makeAnonymousValue(type);
    emitLn("%s = hwarith.constant %d : %s", result, value, type);
    return result;
  }

  MLIRValue makeHWConst(BigInteger value, int bitWidth) {
    assert !(value instanceof TypedBigInteger);
    var result = makeAnonymousValue(MLIRType.getType(bitWidth, false));
    emitLn("%s = hw.constant %d : i%d", result, value, bitWidth);
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

  MLIRValue makeSignlessCast(MLIRValue value, int newWidth) {
    assert value.type.width <= newWidth : "Possibly unintended truncation";
    var result = makeAnonymousValue(MLIRType.DUMMY);
    emitLn("%s = hwarith.cast %s : (%s) -> i%d", result, value, value.type,
           newWidth);
    return result;
  }

  MLIRValue makeHWConstCast(MLIRValue value, int inputWidth, MLIRType type) {
    var result = makeAnonymousValue(type);
    emitLn("%s = hwarith.cast %s : (i%d) -> %s", result, value, inputWidth,
           type);
    return result;
  }

  // Either get the existing MLIR value that represents a local value or load
  // the value using coredsl.get if it is architectural state
  MLIRValue getOrLoad(NamedEntity entity) {
    var mlirValue = getValue(entity);
    if (mlirValue == null) {
      var type = MLIRType.mapType(ac.getDeclaredType(entity));
      mlirValue = makeAnonymousValue(type);
      emitLn("%s = coredsl.get @%s : %s", mlirValue, entity.getName(), type);
    }
    return mlirValue;
  }

  void emit(String format, Object... args) {
    sb.append(String.format(format, args));
  }

  void emitLn(String format, Object... args) {
    emit(format, args);
    sb.append('\n');
  }

  Set<NamedEntity> getUpdatedEntities() {
    return Collections.unmodifiableSet(updatedEntities);
  }

  Map<NamedEntity, MLIRValue> getValues() {
    return Collections.unmodifiableMap(values);
  }

  int getValueCounter() { return valueCounter.get(); }

  boolean getTerminatorWasEmitted() { return terminatorWasEmitted; }

  void setTerminatorWasEmitted() { terminatorWasEmitted = true; }

  AnalysisContext getAnalysisContext() { return ac; }

  StringBuilder getStringBuilder() { return sb; }

  // Create a ConstructionContext with a copy of the current value map
  ConstructionContext createDerivedCC() {
    return new ConstructionContext(new LinkedHashMap<>(values),
                                   new AtomicInteger(getValueCounter()), ac,
                                   new StringBuilder());
  }

  // This should only be used for contexts gotten from createDerivedCC
  // Useful for operations that we don't know yet whether they are needed or
  // not. These can be emitted into a derived context, which is then merged
  // using this function when we know that the operations are needed in the
  // main context
  void appendDerivedCC(ConstructionContext derived) {
    assert getValueCounter() <= derived.getValueCounter();
    valueCounter.set(derived.getValueCounter());
    // TODO: make this check if all values of values are in derived.values (not
    //  necessarily the same value but same key)
    values.putAll(derived.values);
    emit("%s", derived.sb.toString());
  }
}

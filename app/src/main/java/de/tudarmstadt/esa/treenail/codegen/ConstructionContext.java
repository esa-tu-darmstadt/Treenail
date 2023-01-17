package de.tudarmstadt.esa.treenail.codegen;

import com.minres.coredsl.analysis.AnalysisContext;
import com.minres.coredsl.coreDsl.Expression;
import com.minres.coredsl.coreDsl.IntegerConstant;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.type.ArrayType;
import com.minres.coredsl.type.CoreDslType;
import com.minres.coredsl.type.IntegerType;
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

  // FIXME: should really switch to using the BigIntegers here.
  int getConstantValue(Expression expr) {
    if (expr instanceof IntegerConstant)
      return ((IntegerConstant)expr).getValue().intValue();
    return ac.getExpressionValue(expr).getValue().intValue();
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

  MLIRValue makeConst(int value, MLIRType type) {
    var result = makeAnonymousValue(type);
    emitLn("%s = hwarith.constant %d : %s", result, value, type);
    return result;
  }

  MLIRValue makeCast(MLIRValue value, MLIRType type) {
    if (type == value.type)
      return value;

    var result = makeAnonymousValue(type);
    emitLn("%s = coredsl.cast %s : %s to %s", result, value, value.type, type);
    return result;
  }

  MLIRValue makeSignlessCast(MLIRValue value) {
    var result =
        makeAnonymousValue(MLIRType.getType(1, false) /* doesn't matter */);
    emitLn("%s = coredsl.cast %s : %s to i1", result, value, value.type);
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

  AnalysisContext getAnalysisContext() { return ac; }

  StringBuilder getStringBuilder() { return sb; }
}

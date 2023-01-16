package de.tudarmstadt.esa.treenail.codegen;

import com.minres.coredsl.analysis.ConstantValue.StatusCode;
import com.minres.coredsl.analysis.ElaborationContext;
import com.minres.coredsl.analysis.ElaborationContext.NodeInfo;
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
import org.eclipse.emf.ecore.EObject;

class ConstructionContext {
  private final Set<NamedEntity> updatedEntities = new LinkedHashSet<>();

  private final Map<NamedEntity, MLIRValue> values;
  private final AtomicInteger counter;
  private final ElaborationContext ctx;
  private final StringBuilder sb;

  ConstructionContext(Map<NamedEntity, MLIRValue> values, AtomicInteger counter,
                      ElaborationContext ctx, StringBuilder sb) {
    this.values = values;
    this.counter = counter;
    this.ctx = ctx;
    this.sb = sb;
  }

  NodeInfo getInfo(EObject obj) { return ctx.getNodeInfo(obj); }

  CoreDslType getType(EObject obj) { return getInfo(obj).getType(); }

  boolean isConstant(Expression expr) {
    return expr instanceof IntegerConstant || getInfo(expr).isValueSet();
  }

  // FIXME: should really switch to using the BigIntegers here.
  int getConstantValue(Expression expr) {
    if (expr instanceof IntegerConstant)
      return ((IntegerConstant)expr).getValue().intValue();
    return getInfo(expr).getValue().getValue().intValue();
  }

  IntegerType getElementType(NamedEntity ent) {
    var type = getType(ent);
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

  ElaborationContext getElaborationContext() { return ctx; }

  StringBuilder getStringBuilder() { return sb; }
}

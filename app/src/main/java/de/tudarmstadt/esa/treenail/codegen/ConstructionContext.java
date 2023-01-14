package de.tudarmstadt.esa.treenail.codegen;

import com.minres.coredsl.analysis.ElaborationContext;
import com.minres.coredsl.analysis.ElaborationContext.NodeInfo;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.type.ArrayType;
import com.minres.coredsl.type.CoreDslType;
import com.minres.coredsl.type.IntegerType;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.emf.ecore.EObject;

class ConstructionContext {
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

  IntegerType getElementType(NamedEntity ent) {
    var type = getType(ent);
    assert type.isArrayType();
    var arrayType = (ArrayType)type;
    assert arrayType.elementType.isIntegerType();
    return (IntegerType)arrayType.elementType;
  }

  boolean hasValue(NamedEntity ent) { return values.containsKey(ent); }

  MLIRValue getValue(NamedEntity ent) { return values.get(ent); }

  void setValue(NamedEntity ent, MLIRValue value) { values.put(ent, value); }

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

  StringBuilder emitLn(String format, Object... args) {
    return sb.append(String.format(format, args)).append('\n');
  }
}

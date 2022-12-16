package de.tudarmstadt.esa.treenail.codegen;

import static java.lang.String.format;

import com.minres.coredsl.coreDsl.ArrayAccessExpression;
import com.minres.coredsl.coreDsl.AssignmentExpression;
import com.minres.coredsl.coreDsl.Declaration;
import com.minres.coredsl.coreDsl.EntityReference;
import com.minres.coredsl.coreDsl.IntegerConstant;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.coreDsl.util.CoreDslSwitch;
import com.minres.coredsl.util.BigIntegerWithRadix;
import java.util.Map;

class ExpressionSwitch extends CoreDslSwitch<MLIRValue> {
  private final Map<NamedEntity, MLIRValue> values;
  private final StringBuilder sb;
  private final TypeSwitch typeSwitch;

  private int anonymousValueCounter = 0;

  ExpressionSwitch(Map<NamedEntity, MLIRValue> values, StringBuilder sb) {
    this.values = values;
    this.sb = sb;
    this.typeSwitch = new TypeSwitch();
  }

  private MLIRType getType(NamedEntity entity) {
    var container = entity.eContainer();
    if (container == null || !(container instanceof Declaration))
      return null;

    return typeSwitch.doSwitch(((Declaration)container).getType());
  }

  private MLIRValue makeAnonymousValue(MLIRType type) {
    return new MLIRValue(Integer.toString(anonymousValueCounter++), type);
  }

  private MLIRValue cast(MLIRValue value, MLIRType type) {
    if (type == value.type)
      return value;

    var result = makeAnonymousValue(type);
    sb.append(format("%s = coredsl.cast %s : %s to %s\n", result, value,
                     value.type, type));
    return result;
  }

  private void store(EntityReference reference, MLIRValue newValue) {
    var entity = reference.getTarget();
    if (values.containsKey(entity)) {
      // It's a local variable, just put it in the value map.
      values.put(entity, newValue);
      return;
    }

    // Otherwise, we update an architectural state element and have to emit a
    // `coredsl.set`.
    var type = getType(entity);
    sb.append(format("coredsl.set @%s = %s : %s\n", entity.getName(),
                     cast(newValue, type), type));
  }

  private void store(ArrayAccessExpression access, MLIRValue newValue) {
    var arrayReference = access.getTarget();
    assert arrayReference instanceof EntityReference;

    var arrayEntity = ((EntityReference)arrayReference).getTarget();
    assert !values.containsKey(arrayEntity) : "NYI: Local arrays";

    // Evaluate the index expression.
    var index = doSwitch(access.getIndex());

    // We are accessing an array-like architectural state element. Doesn't
    // matter whether it is a register or address space.
    var elementType = getType(arrayEntity);
    sb.append(format("coredsl.set @%s[%s : %s] = %s : %s\n",
                     arrayEntity.getName(), index, index.type,
                     cast(newValue, elementType), elementType));
  }

  @Override
  public MLIRValue caseAssignmentExpression(AssignmentExpression assign) {
    assert "=".equals(assign.getOperator()) : "NYI: Combined assignments";

    var rhs = doSwitch(assign.getValue());
    var lhs = assign.getTarget();
    if (lhs instanceof EntityReference)
      store((EntityReference)lhs, rhs);
    else if (lhs instanceof ArrayAccessExpression)
      store((ArrayAccessExpression)lhs, rhs);
    else
      assert false : "NYI: Lvalue other than entity or array access";

    return rhs;
  }

  @Override
  public MLIRValue caseIntegerConstant(IntegerConstant konst) {
    var bigInt = (BigIntegerWithRadix)konst.getValue();
    var type = MLIRType.getType(
        bigInt.getSize(), bigInt.getType() == BigIntegerWithRadix.TYPE.SIGNED);
    var result = makeAnonymousValue(type);
    sb.append(format("%s = hwarith.constant %d : %s\n", result,
                     bigInt.intValue(), type));
    return result;
  }

  @Override
  public MLIRValue caseEntityReference(EntityReference reference) {
    var entity = reference.getTarget();
    if (values.containsKey(entity))
      // It's a local variable, retrieve its last definition.
      return values.get(entity);

    // Otherwise, emit a `coredsl.get`.
    var type = getType(entity);
    var result = makeAnonymousValue(type);
    sb.append(
        format("%s = coredsl.get @%s : %s\n", result, entity.getName(), type));
    return result;
  }

  @Override
  public MLIRValue caseArrayAccessExpression(ArrayAccessExpression access) {
    var arrayReference = access.getTarget();
    assert arrayReference instanceof EntityReference;

    var arrayEntity = ((EntityReference)arrayReference).getTarget();
    assert !values.containsKey(arrayEntity) : "NYI: Local arrays";

    // Evaluate the index expression.
    var index = doSwitch(access.getIndex());

    // We are accessing an array-like architectural state element. Doesn't
    // matter whether it is a register or address space.
    var elementType = getType(arrayEntity);
    var result = makeAnonymousValue(elementType);
    sb.append(format("%s = coredsl.get @%s[%s : %s] : %s\n", result,
                     arrayEntity.getName(), index, index.type, elementType));
    return result;
  }
}

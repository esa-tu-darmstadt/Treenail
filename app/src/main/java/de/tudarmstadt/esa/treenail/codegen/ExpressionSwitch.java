package de.tudarmstadt.esa.treenail.codegen;

import static de.tudarmstadt.esa.treenail.codegen.MLIRType.mapType;
import static java.lang.String.format;

import com.minres.coredsl.analysis.ElaborationContext;
import com.minres.coredsl.analysis.ElaborationContext.NodeInfo;
import com.minres.coredsl.coreDsl.AssignmentExpression;
import com.minres.coredsl.coreDsl.CastExpression;
import com.minres.coredsl.coreDsl.EntityReference;
import com.minres.coredsl.coreDsl.IndexAccessExpression;
import com.minres.coredsl.coreDsl.InfixExpression;
import com.minres.coredsl.coreDsl.IntegerConstant;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.coreDsl.ParenthesisExpression;
import com.minres.coredsl.coreDsl.PrefixExpression;
import com.minres.coredsl.coreDsl.util.CoreDslSwitch;
import com.minres.coredsl.type.ArrayType;
import com.minres.coredsl.type.CoreDslType;
import com.minres.coredsl.type.IntegerType;
import java.util.AbstractMap;
import java.util.Map;
import org.eclipse.emf.ecore.EObject;

class ExpressionSwitch extends CoreDslSwitch<MLIRValue> {
  private final ElaborationContext ctx;
  private final Map<NamedEntity, MLIRValue> values;
  private final StringBuilder sb;

  private int anonymousValueCounter = 0;

  ExpressionSwitch(ElaborationContext ctx, Map<NamedEntity, MLIRValue> values,
                   StringBuilder sb) {
    this.ctx = ctx;
    this.values = values;
    this.sb = sb;
  }

  private NodeInfo getInfo(EObject obj) { return ctx.getNodeInfo(obj); }
  private CoreDslType getType(EObject obj) { return getInfo(obj).getType(); }
  private IntegerType getElementType(NamedEntity ent) {
    var type = getType(ent);
    assert type.isArrayType();
    var arrayType = (ArrayType)type;
    assert arrayType.elementType.isIntegerType();
    return (IntegerType)arrayType.elementType;
  }
  private int getConstValue(EObject obj) {
    return getInfo(obj).getValue().value.intValue();
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
    var type = mapType(getType(entity));
    sb.append(format("coredsl.set @%s = %s : %s\n", entity.getName(),
                     cast(newValue, type), type));
  }

  private void store(IndexAccessExpression access, MLIRValue newValue) {
    var arrayReference = access.getTarget();
    assert arrayReference instanceof EntityReference;

    var arrayEntity = ((EntityReference)arrayReference).getTarget();
    assert !values.containsKey(arrayEntity) : "NYI: Local arrays";

    // Evaluate the index expression.
    var index = doSwitch(access.getIndex());

    // We are accessing an array-like architectural state element. Doesn't
    // matter whether it is a register or address space.
    var elementType = mapType(getElementType(arrayEntity));
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
    else if (lhs instanceof IndexAccessExpression)
      store((IndexAccessExpression)lhs, rhs);
    else
      assert false : "NYI: Lvalue other than entity or array access";

    return rhs;
  }

  @Override
  public MLIRValue caseIntegerConstant(IntegerConstant konst) {
    var type = mapType(getType(konst));
    // TODO: var value = getConstValue(konst);
    var value = konst.getValue().intValue();
    var result = makeAnonymousValue(type);
    sb.append(format("%s = hwarith.constant %d : %s\n", result, value, type));
    return result;
  }

  @Override
  public MLIRValue caseEntityReference(EntityReference reference) {
    var entity = reference.getTarget();
    if (values.containsKey(entity))
      // It's a local variable, retrieve its last definition.
      return values.get(entity);

    // Otherwise, emit a `coredsl.get`.
    var type = mapType(getType(entity));
    var result = makeAnonymousValue(type);
    sb.append(
        format("%s = coredsl.get @%s : %s\n", result, entity.getName(), type));
    return result;
  }

  @Override
  public MLIRValue caseIndexAccessExpression(IndexAccessExpression access) {
    var arrayReference = access.getTarget();
    assert arrayReference instanceof EntityReference;

    var arrayEntity = ((EntityReference)arrayReference).getTarget();
    assert !values.containsKey(arrayEntity) : "NYI: Local arrays";

    // Evaluate the index expression.
    var index = doSwitch(access.getIndex());

    // We are accessing an array-like architectural state element. Doesn't
    // matter whether it is a register or address space.
    var elementType = mapType(getElementType(arrayEntity));
    var result = makeAnonymousValue(elementType);
    sb.append(format("%s = coredsl.get @%s[%s : %s] : %s\n", result,
                     arrayEntity.getName(), index, index.type, elementType));
    return result;
  }

  private static AbstractMap.SimpleEntry<String, String> mapping(String coreDsl,
                                                                 String mlir) {
    return new AbstractMap.SimpleEntry<String, String>(coreDsl, mlir);
  }
  private static final Map<String, String> operatorMap =
      Map.ofEntries(mapping("+", "hwarith.add"), mapping("-", "hwarith.sub"),
                    mapping("*", "hwarith.mul"), mapping("/", "hwarith.div"),
                    mapping("%", "coredsl.mod"), mapping("&", "coredsl.and"),
                    mapping("|", "coredsl.or"), mapping("^", "coredsl.xor"),
                    mapping("<<", "coredsl.shift_left"),
                    mapping(">>", "coredsl.shift_right"));

  private void emitBinaryOp(String op, MLIRValue res, MLIRValue lhs,
                            MLIRValue rhs) {
    if (op.startsWith("hwarith."))
      sb.append(format("%s = %s %s, %s : (%s, %s) -> %s\n", res, op, lhs, rhs,
                       lhs.type, rhs.type, res.type));
    else
      sb.append(format("%s = %s %s, %s : %s, %s\n", res, op, lhs, rhs, lhs.type,
                       rhs.type));
  }

  @Override
  public MLIRValue caseInfixExpression(InfixExpression expr) {
    var lhs = doSwitch(expr.getLeft());
    var rhs = doSwitch(expr.getRight());
    var res = makeAnonymousValue(mapType(getType(expr)));

    var op = operatorMap.get(expr.getOperator());
    assert op != null : "NYI: operator " + expr.getOperator();
    emitBinaryOp(op, res, lhs, rhs);

    return res;
  }

  @Override
  public MLIRValue casePrefixExpression(PrefixExpression expr) {
    var oprnd = doSwitch(expr.getOperand());
    var type = mapType(getType(expr));
    var res = makeAnonymousValue(type);

    // The target dialect don't have unary operations, hence we must construct
    // equivalent binary operations here.
    var zero = makeAnonymousValue(type);
    sb.append(format("%s = hwarith.constant 0 : %s\n", zero, type));

    // TODO: ! -> cmp, - -> sub. Encountered type mismatches.
    assert expr.getOperator().equals("~")
        : "NYI: operator " +
        expr.getOperator();
    emitBinaryOp("coredsl.xor", res, zero, oprnd);

    return res;
  }

  @Override
  public MLIRValue caseCastExpression(CastExpression cast) {
    var source = doSwitch(cast.getOperand());
    var type = mapType(getType(cast));
    return cast(source, type);
  }

  @Override
  public MLIRValue caseParenthesisExpression(ParenthesisExpression expr) {
    return doSwitch(expr.getInner());
  }

  @Override
  public MLIRValue defaultCase(EObject obj) {
    sb.append("// unhandled: ").append(obj).append('\n');
    return makeAnonymousValue(MLIRType.getType(1, false));
  }
}

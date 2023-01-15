package de.tudarmstadt.esa.treenail.codegen;

import static de.tudarmstadt.esa.treenail.codegen.MLIRType.mapType;

import com.minres.coredsl.coreDsl.AssignmentExpression;
import com.minres.coredsl.coreDsl.CastExpression;
import com.minres.coredsl.coreDsl.EntityReference;
import com.minres.coredsl.coreDsl.IndexAccessExpression;
import com.minres.coredsl.coreDsl.InfixExpression;
import com.minres.coredsl.coreDsl.IntegerConstant;
import com.minres.coredsl.coreDsl.ParenthesisExpression;
import com.minres.coredsl.coreDsl.PrefixExpression;
import com.minres.coredsl.coreDsl.util.CoreDslSwitch;
import java.util.AbstractMap;
import java.util.Map;
import org.eclipse.emf.ecore.EObject;

class ExpressionSwitch extends CoreDslSwitch<MLIRValue> {
  private final ConstructionContext cc;

  ExpressionSwitch(ConstructionContext cc) { this.cc = cc; }

  private void store(EntityReference reference, MLIRValue newValue) {
    var entity = reference.getTarget();
    var type = mapType(cc.getType(entity));
    var castValue = cc.makeCast(newValue, type);

    if (cc.hasValue(entity)) {
      // It's a local variable, just put it in the value map.
      cc.setValue(entity, castValue);
      return;
    }

    // Otherwise, we update an architectural state element and have to emit a
    // `coredsl.set`.
    cc.emitLn("coredsl.set @%s = %s : %s", entity.getName(), castValue, type);
  }

  private void store(IndexAccessExpression access, MLIRValue newValue) {
    var arrayReference = access.getTarget();
    assert arrayReference instanceof EntityReference;

    var arrayEntity = ((EntityReference)arrayReference).getTarget();
    assert !cc.hasValue(arrayEntity) : "NYI: Local arrays";

    // Evaluate the index expression.
    var index = doSwitch(access.getIndex());

    // We are accessing an array-like architectural state element. Doesn't
    // matter whether it is a register or address space.
    var elementType = mapType(cc.getElementType(arrayEntity));
    cc.emitLn("coredsl.set @%s[%s : %s] = %s : %s", arrayEntity.getName(),
              index, index.type, cc.makeCast(newValue, elementType),
              elementType);
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
    var type = mapType(cc.getType(konst));
    // TODO: var value = getConstValue(konst);
    var value = konst.getValue().intValue();
    return cc.makeConst(value, type);
  }

  @Override
  public MLIRValue caseEntityReference(EntityReference reference) {
    var entity = reference.getTarget();
    if (cc.hasValue(entity))
      // It's a local variable, retrieve its last definition.
      return cc.getValue(entity);

    // Otherwise, emit a `coredsl.get`.
    var type = mapType(cc.getType(entity));
    var result = cc.makeAnonymousValue(type);
    cc.emitLn("%s = coredsl.get @%s : %s", result, entity.getName(), type);
    return result;
  }

  private MLIRValue handleSingleBitAccess(IndexAccessExpression access) {
    var target = doSwitch(access.getTarget());

    // FIXME: Node info not set for access
    // var type = mapType(cc.getType(access));
    var type = MLIRType.getType(1, false);
    var result = cc.makeAnonymousValue(type);
    // FIXME: Constant value not set for integerConstant
    // var indexInfo = cc.getInfo(access.getIndex());
    // if (indexInfo.getValue().isValid()) {
    if (access.getIndex() instanceof IntegerConstant) {
      var constIndex =
          ((IntegerConstant)access.getIndex()).getValue().intValue();
      cc.emitLn("%s = coredsl.bitextract %s[%d] : (%s) -> %s", result, target,
                constIndex, target.type, type);
      return result;
    }

    var index = doSwitch(access.getIndex());
    cc.emitLn("%s = coredsl.bitextract %s[%s : %s] : (%s) -> %s", result,
              target, index, index.type, target.type, type);

    return result;
  }

  private MLIRValue handleBitRangeAccess(IndexAccessExpression access) {
    var target = doSwitch(access.getTarget());
    assert access.getIndex() instanceof IntegerConstant &&
        access.getEndIndex() instanceof IntegerConstant
        : "NYI: Variable bit-ranges";

    var from = ((IntegerConstant)access.getIndex()).getValue().intValue();
    var to = ((IntegerConstant)access.getEndIndex()).getValue().intValue();

    // FIXME: Node info not set for access
    var type = MLIRType.getType(Math.abs(from - to) + 1, false);
    var result = cc.makeAnonymousValue(type);
    cc.emitLn("%s = coredsl.bitextract %s[%d:%d] : (%s) -> %s", result, target,
              from, to, target.type, type);

    return result;
  }

  private MLIRValue handleSingleElementAccess(IndexAccessExpression access) {
    assert access.getTarget() instanceof EntityReference
        : "NYI: Element access into a temporary object";
    var entity = ((EntityReference)access.getTarget()).getTarget();
    assert !cc.hasValue(entity) : "NYI: Element access into local arrays";

    // We are accessing an array-like architectural state element. Doesn't
    // matter whether it is a register or address space.
    var elementType = mapType(cc.getElementType(entity));
    var result = cc.makeAnonymousValue(elementType);
    var index = doSwitch(access.getIndex());
    cc.emitLn("%s = coredsl.get @%s[%s : %s] : %s", result, entity.getName(),
              index, index.type, elementType);

    return result;
  }

  private MLIRValue handleElementRangeAccess(IndexAccessExpression access) {
    assert false : "NYI: Address space range access";
    return null;
  }

  @Override
  public MLIRValue caseIndexAccessExpression(IndexAccessExpression access) {
    // An innocuous `[...]` expression has different lowerings depending on the
    // type of the expression that is indexed into, and the presence of an end
    // index (i.e. it's a range index).

    var targetType = cc.getType(access.getTarget());
    // It's a bit-level access if we're indexing into a scalar.
    if (targetType.isIntegerType()) {
      if (access.getEndIndex() != null)
        return handleBitRangeAccess(access);
      return handleSingleBitAccess(access);
    }

    // Otherwise, we indexing into an array and retrieve one or more elements.
    assert targetType.isArrayType();
    if (access.getEndIndex() != null)
      return handleElementRangeAccess(access);
    return handleSingleElementAccess(access);
  }

  private static AbstractMap.SimpleEntry<String, String> m(String coreDsl,
                                                           String mlir) {
    return new AbstractMap.SimpleEntry<String, String>(coreDsl, mlir);
  }
  private static final Map<String, String> binaryOperatorMap = Map.ofEntries(
      m("+", "hwarith.add"), m("-", "hwarith.sub"), m("*", "hwarith.mul"),
      m("/", "hwarith.div"), m("%", "coredsl.mod"), m("&", "coredsl.and"),
      m("|", "coredsl.or"), m("^", "coredsl.xor"),
      m("<<", "coredsl.shift_left"), m(">>", "coredsl.shift_right"),
      m("==", "hwarith.icmp eq"), m("!=", "hwarith.icmp ne"),
      m("<", "hwarith.icmp lt"), m("<=", "hwarith.icmp le"),
      m(">", "hwarith.icmp gt"), m(">=", "hwarith.icmp ge"));

  private MLIRValue emitBinaryOp(String op, MLIRType resType, MLIRValue lhs,
                                 MLIRValue rhs) {
    var res = cc.makeAnonymousValue(resType);
    if (op.startsWith("hwarith.") && !op.startsWith("hwarith.icmp"))
      cc.emitLn("%s = %s %s, %s : (%s, %s) -> %s", res, op, lhs, rhs, lhs.type,
                rhs.type, res.type);
    else
      cc.emitLn("%s = %s %s, %s : %s, %s", res, op, lhs, rhs, lhs.type,
                rhs.type);
    return res;
  }

  @Override
  public MLIRValue caseInfixExpression(InfixExpression expr) {
    var lhs = doSwitch(expr.getLeft());
    var rhs = doSwitch(expr.getRight());
    var type = mapType(cc.getType(expr));

    var op = binaryOperatorMap.get(expr.getOperator());
    assert op != null : "NYI: operator " + expr.getOperator();
    return emitBinaryOp(op, type, lhs, rhs);
  }

  @Override
  public MLIRValue casePrefixExpression(PrefixExpression expr) {
    var oprnd = doSwitch(expr.getOperand());
    var type = mapType(cc.getType(expr));

    // The target dialect don't have unary operations, hence we must construct
    // equivalent binary operations here.
    var zero = cc.makeConst(0, MLIRType.getType(1, false));

    switch (expr.getOperator()) {
    case "-":
      return emitBinaryOp("hwarith.sub", type, zero, oprnd);
    case "!":
      return emitBinaryOp("hwarith.icmp ne", type, zero, oprnd);
    case "~":
      return emitBinaryOp("coredsl.xor", type, zero, oprnd);
    default:
      assert false : "NYI: operator " + expr.getOperator();
      return null;
    }
  }

  @Override
  public MLIRValue caseCastExpression(CastExpression cast) {
    var source = doSwitch(cast.getOperand());
    var type = mapType(cc.getType(cast));
    return cc.makeCast(source, type);
  }

  @Override
  public MLIRValue caseParenthesisExpression(ParenthesisExpression expr) {
    return doSwitch(expr.getInner());
  }

  @Override
  public MLIRValue defaultCase(EObject obj) {
    cc.emitLn("// unhandled: %s", obj);
    return cc.makeAnonymousValue(MLIRType.getType(1, false));
  }
}

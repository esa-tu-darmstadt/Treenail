package de.tudarmstadt.esa.treenail.codegen;

import static de.tudarmstadt.esa.treenail.codegen.MLIRType.mapType;

import com.minres.coredsl.coreDsl.AssignmentExpression;
import com.minres.coredsl.coreDsl.CastExpression;
import com.minres.coredsl.coreDsl.ConcatenationExpression;
import com.minres.coredsl.coreDsl.EntityReference;
import com.minres.coredsl.coreDsl.Expression;
import com.minres.coredsl.coreDsl.IndexAccessExpression;
import com.minres.coredsl.coreDsl.InfixExpression;
import com.minres.coredsl.coreDsl.IntegerConstant;
import com.minres.coredsl.coreDsl.NamedEntity;
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

  private MLIRValue storeSingleBit(MLIRValue oldValue, Expression indexExpr,
                                   MLIRValue newValue, MLIRType accessType) {
    var result = cc.makeAnonymousValue(oldValue.type);
    if (cc.isConstant(indexExpr)) {
      cc.emitLn("%s = coredsl.bitset %s[%d] = %s: (%s, %s) -> %s", result,
                oldValue, cc.getConstantValue(indexExpr), newValue,
                oldValue.type, newValue.type, result.type);
      return result;
    }

    var index = doSwitch(indexExpr);
    assert newValue.type == accessType;
    cc.emitLn("%s = coredsl.bitset %s[%s : %s] = %s : (%s, %s) -> %s", result,
              oldValue, index, index.type, newValue, oldValue.type,
              newValue.type, result.type);
    return result;
  }

  private MLIRValue storeBitRange(MLIRValue oldValue, Expression fromExpr,
                                  Expression toExpr, MLIRValue newValue,
                                  MLIRType accessType) {
    var rangeRes = RangeAnalyzer.analyze(fromExpr, toExpr, cc, this);
    assert rangeRes != null : "Invalid range";

    var result = cc.makeAnonymousValue(oldValue.type);
    cc.emitLn("%s = coredsl.bitset %s[%s] = %s : (%s, %s) -> %s", result,
              oldValue, rangeRes, cc.makeCast(newValue, accessType),
              oldValue.type, accessType, result.type);

    return result;
  }

  private void storeSingleElement(NamedEntity entity, Expression index,
                                  MLIRValue newValue, MLIRType accessType) {
    assert !cc.hasValue(entity) : "NYI: local arrays";

    // TODO: could also check here for a constant index
    var idx = doSwitch(index);

    cc.emitLn("coredsl.set @%s[%s : %s] = %s : %s", entity.getName(), idx,
              idx.type, cc.makeCast(newValue, accessType), accessType);
  }

  private void storeElementRange(NamedEntity entity, Expression fromExpr,
                                 Expression toExpr, MLIRValue newValue,
                                 MLIRType accessType) {
    assert !cc.hasValue(entity) : "NYI: local arrays";

    var rangeRes = RangeAnalyzer.analyze(fromExpr, toExpr, cc, this);
    assert rangeRes != null : "Invalid range";

    cc.emitLn("coredsl.set @%s[%s] = %s : %s", entity.getName(), rangeRes,
              cc.makeCast(newValue, accessType), accessType);
  }

  private void store(IndexAccessExpression access, MLIRValue newValue) {
    assert access.getTarget() instanceof EntityReference
        : "NYI: Nested Lvalues";
    var entity = ((EntityReference)access.getTarget()).getTarget();
    var entityType = cc.getType(entity);

    var from = access.getIndex();
    var to = access.getEndIndex();

    var isLocal = cc.hasValue(entity);
    var isBitAccess = entityType.isIntegerType();
    var isRange = to != null;

    var accessType = mapType(cc.getType(access));

    if (!isBitAccess) {
      if (isRange)
        storeElementRange(entity, from, to, newValue, accessType);
      else
        storeSingleElement(entity, from, newValue, accessType);
      return;
    }

    MLIRValue oldValue;
    if (isLocal)
      oldValue = cc.getValue(entity);
    else {
      oldValue = cc.makeAnonymousValue(mapType(entityType));
      cc.emitLn("%s = coredsl.get @%s : %s", oldValue, entity.getName(),
                oldValue.type);
    }

    var updatedValue =
        isRange ? storeBitRange(oldValue, from, to, newValue, accessType)
                : storeSingleBit(oldValue, from, newValue, accessType);

    if (isLocal)
      cc.setValue(entity, updatedValue);
    else
      cc.emitLn("coredsl.set @%s = %s : %s", entity.getName(), updatedValue,
                updatedValue.type);
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
    var value = cc.getConstantValue(konst);
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

  @Override
  public MLIRValue caseIndexAccessExpression(IndexAccessExpression access) {
    // An innocuous `[...]` expression has different lowerings depending on the
    // type of the expression that is indexed into, and the presence of an end
    // index (i.e. it's a range index).

    var type = mapType(cc.getType(access));
    var result = cc.makeAnonymousValue(type);
    var index = RangeAnalyzer.analyze(access.getIndex(), access.getEndIndex(),
                                      cc, this);

    var targetType = cc.getType(access.getTarget());
    // It's a bit-level access if we're indexing into a scalar.
    if (targetType.isIntegerType()) {
      var target = doSwitch(access.getTarget());
      cc.emitLn("%s = coredsl.bitextract %s[%s] : (%s) -> %s", result, target,
                index, target.type, type);
      return result;
    }

    // Otherwise, we indexing into an array and retrieve one or more elements.
    assert targetType.isArrayType();
    assert access.getTarget() instanceof EntityReference
        : "NYI: Element access into a temporary object";
    var entity = ((EntityReference)access.getTarget()).getTarget();
    assert !cc.hasValue(entity) : "NYI: Element access into local arrays";

    cc.emitLn("%s = coredsl.get @%s[%s] : %s", result, entity.getName(), index,
              type);
    return result;
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
  public MLIRValue caseConcatenationExpression(ConcatenationExpression concat) {
    var parts = concat.getParts();
    assert parts.size() >= 2;

    var head = doSwitch(parts.get(0));
    for (int i = 1; i < parts.size(); ++i) {
      var next = doSwitch(parts.get(i));
      head = emitBinaryOp(
          "coredsl.concat",
          MLIRType.getType(head.type.width + next.type.width, false), head,
          next);
    }

    return head;
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

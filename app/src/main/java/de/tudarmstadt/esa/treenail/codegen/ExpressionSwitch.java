package de.tudarmstadt.esa.treenail.codegen;

import static de.tudarmstadt.esa.treenail.codegen.LongnailCodegen.N_SPACES;
import static de.tudarmstadt.esa.treenail.codegen.MLIRType.getType;
import static de.tudarmstadt.esa.treenail.codegen.MLIRType.mapType;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.Streams;
import com.minres.coredsl.analysis.AnalysisContext;
import com.minres.coredsl.coreDsl.AssignmentExpression;
import com.minres.coredsl.coreDsl.CastExpression;
import com.minres.coredsl.coreDsl.ConcatenationExpression;
import com.minres.coredsl.coreDsl.ConditionalExpression;
import com.minres.coredsl.coreDsl.EntityReference;
import com.minres.coredsl.coreDsl.FunctionCallExpression;
import com.minres.coredsl.coreDsl.FunctionDefinition;
import com.minres.coredsl.coreDsl.IndexAccessExpression;
import com.minres.coredsl.coreDsl.InfixExpression;
import com.minres.coredsl.coreDsl.IntegerConstant;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.coreDsl.ParenthesisExpression;
import com.minres.coredsl.coreDsl.PostfixExpression;
import com.minres.coredsl.coreDsl.PrefixExpression;
import com.minres.coredsl.coreDsl.util.CoreDslSwitch;
import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.eclipse.emf.ecore.EObject;

class ExpressionSwitch extends CoreDslSwitch<MLIRValue> {
  private final AnalysisContext ac;
  private final ConstructionContext cc;

  ExpressionSwitch(ConstructionContext cc) {
    this.ac = cc.getAnalysisContext();
    this.cc = cc;
  }

  class StoreSwitch extends CoreDslSwitch<MLIRValue> {
    private final MLIRValue newValue;
    StoreSwitch(MLIRValue newValue) { this.newValue = newValue; }

    @Override
    public MLIRValue caseEntityReference(EntityReference reference) {
      var entity = reference.getTarget();
      var type = mapType(ac.getDeclaredType(entity));
      var castValue = cc.makeCast(newValue, type);

      if (cc.hasValue(entity)) {
        // It's a local variable, just put it in the value map.
        cc.setValue(entity, castValue);
        return castValue;
      }

      // Otherwise, we update an architectural state element and have to emit a
      // `coredsl.set`.
      cc.emitLn("coredsl.set @%s = %s : %s", entity.getName(), castValue, type);
      return castValue;
    }

    @Override
    public MLIRValue caseIndexAccessExpression(IndexAccessExpression access) {
      var target = access.getTarget();
      assert target instanceof EntityReference : "NYI: Nested Lvalues";
      var entity = ((EntityReference)target).getTarget();
      var entityType = ac.getDeclaredType(entity);

      var isLocal = cc.hasValue(entity);
      var isBitAccess = entityType.isIntegerType();

      var accessType = mapType(ac.getExpressionType(access));
      var castValue = cc.makeCast(newValue, accessType);
      var index = RangeAnalyzer.analyze(access.getIndex(), access.getEndIndex(),
                                        entityType, cc, ExpressionSwitch.this);

      if (!isBitAccess) {
        assert !isLocal : "NYI: local arrays";
        cc.emitLn("coredsl.set @%s[%s] = %s : %s", entity.getName(), index,
                  castValue, accessType);
        return castValue;
      }

      MLIRValue oldValue;
      if (isLocal)
        oldValue = cc.getValue(entity);
      else {
        oldValue = cc.makeAnonymousValue(mapType(entityType));
        cc.emitLn("%s = coredsl.get @%s : %s", oldValue, entity.getName(),
                  oldValue.type);
      }

      var updatedValue = cc.makeAnonymousValue(oldValue.type);
      cc.emitLn("%s = coredsl.bitset %s[%s] = %s : (%s, %s) -> %s",
                updatedValue, oldValue, index, castValue, oldValue.type,
                accessType, updatedValue.type);

      if (isLocal)
        cc.setValue(entity, updatedValue);
      else
        cc.emitLn("coredsl.set @%s = %s : %s", entity.getName(), updatedValue,
                  updatedValue.type);
      return updatedValue;
    }

    @Override
    public MLIRValue defaultCase(EObject obj) {
      assert false : "NYI: Lvalue other than entity or array access";
      return null;
    }
  }

  private int getAddSubResultWidth(MLIRType lhsTy, MLIRType rhsTy) {
    if (lhsTy.isSigned == rhsTy.isSigned)
      return Math.max(lhsTy.width, rhsTy.width) + 1;

    // Extra bit necessary if the respective operand is _unsigned_.
    int lhsExtraBit = lhsTy.isSigned ? 0 : 1;
    int rhsExtraBit = rhsTy.isSigned ? 0 : 1;
    return Math.max(lhsTy.width + lhsExtraBit, rhsTy.width + rhsExtraBit) + 1;
  }

  @Override
  public MLIRValue caseAssignmentExpression(AssignmentExpression assign) {
    var lhs = assign.getTarget();
    var opr = assign.getOperator();
    var rhs = assign.getValue();

    var rhsVal = doSwitch(rhs);
    if (opr.length() > 1) {
      // It's a compound assignment; evaluate the current LHS value.
      var lhsVal = doSwitch(lhs);

      var binOpr = opr.substring(0, opr.length() - 1);
      var lhsTy = lhsVal.type;
      var rhsTy = rhsVal.type;

      // The result type of the underlying binary op cannot be queried from the
      // analysis context, so we have to manually compute it again here.
      MLIRType type = null;
      switch (binOpr) {
      case "&":
      case "|":
      case "^":
      case "<<":
      case ">>":
        // Easy: The LHS type dictates the result type.
        type = lhsTy;
        break;
      case "+":
        type = getType(getAddSubResultWidth(lhsTy, rhsTy),
                       lhsTy.isSigned | rhsTy.isSigned);
        break;
      case "-":
        type = getType(getAddSubResultWidth(lhsTy, rhsTy), true);
        break;
      case "*":
        type =
            getType(lhsTy.width + rhsTy.width, lhsTy.isSigned | rhsTy.isSigned);
        break;
      case "/":
        type = getType(lhsTy.width + (rhsTy.isSigned ? 1 : 0), lhsTy.isSigned);
        break;
      case "%":
        if (lhsTy.isSigned == rhsTy.isSigned)
          type = getType(Math.min(lhsTy.width, rhsTy.width), lhsTy.isSigned);
        else if (lhsTy.isSigned)
          type = getType(Math.min(lhsTy.width, rhsTy.width + 1), true);
        else
          type = getType(Math.min(lhsTy.width, Math.max(1, rhsTy.width - 1)),
                         false);
        break;
      default:
        assert false : "NYI: Unhandled compound assignment operator: " + opr;
      }

      rhsVal =
          emitBinaryOp(binaryOperatorMap.get(binOpr), type, lhsVal, rhsVal);
    }

    // Perform the store (may fail if `lhs` is an unsupported Lvalue).
    return new StoreSwitch(rhsVal).doSwitch(lhs);
  }

  @Override
  public MLIRValue caseIntegerConstant(IntegerConstant konst) {
    var type = mapType(ac.getExpressionType(konst));
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
    var type = mapType(ac.getDeclaredType(entity));
    var result = cc.makeAnonymousValue(type);
    cc.emitLn("%s = coredsl.get @%s : %s", result, entity.getName(), type);
    return result;
  }

  @Override
  public MLIRValue caseIndexAccessExpression(IndexAccessExpression access) {
    // An innocuous `[...]` expression has different lowerings depending on the
    // type of the expression that is indexed into, and the presence of an end
    // index (i.e. it's a range index).

    var type = mapType(ac.getExpressionType(access));
    var targetType = ac.getExpressionType(access.getTarget());
    var result = cc.makeAnonymousValue(type);
    var index = RangeAnalyzer.analyze(access.getIndex(), access.getEndIndex(),
                                      targetType, cc, this);

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
      m("&&", "coredsl.and"), m("|", "coredsl.or"), m("||", "coredsl.or"),
      m("^", "coredsl.xor"), m("<<", "coredsl.shift_left"),
      m(">>", "coredsl.shift_right"), m("==", "hwarith.icmp eq"),
      m("!=", "hwarith.icmp ne"), m("<", "hwarith.icmp lt"),
      m("<=", "hwarith.icmp le"), m(">", "hwarith.icmp gt"),
      m(">=", "hwarith.icmp ge"));

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
    var type = mapType(ac.getExpressionType(expr));

    var opr = expr.getOperator();
    var op = binaryOperatorMap.get(opr);
    assert op != null : "NYI: operator " + opr;
    if ("&&".equals(opr) || "||".equals(opr))
      // TODO: Emit icmp for operands that are not bools.
      System.err.println("[WARN] NYI: Short-circuit evaluation. Evaluating `" +
                         opr + "` eagerly.");
    return emitBinaryOp(op, type, lhs, rhs);
  }

  private static final Map<String, String> unaryOperatorMap = Map.ofEntries(
      m("-", "hwarith.sub"), m("!", "hwarith.icmp ne"), m("~", "coredsl.xor"));

  private MLIRValue emitIncrementOrDecrement(MLIRValue value,
                                             boolean decrement) {
    var type = getType(value.type.width + 1, value.type.isSigned || decrement);
    var one = cc.makeConst(BigInteger.ONE, getType(1, false));
    return emitBinaryOp(binaryOperatorMap.get(decrement ? "-" : "+"), type,
                        value, one);
  }

  @Override
  public MLIRValue casePrefixExpression(PrefixExpression expr) {
    var opr = expr.getOperator();

    var oprndExpr = expr.getOperand();
    var oprnd = doSwitch(oprndExpr);

    if (unaryOperatorMap.containsKey(opr)) {
      // The target dialect don't have unary operations, hence we must construct
      // equivalent binary operations here.
      var zero = cc.makeConst(BigInteger.ZERO, getType(1, false));
      var type = mapType(ac.getExpressionType(expr));
      return emitBinaryOp(unaryOperatorMap.get(opr), type, zero, oprnd);
    }

    assert "++".equals(opr) || "--".equals(opr)
        : "Prefix expression is neither increment nor decrement";

    var incrDecr = emitIncrementOrDecrement(oprnd, "--".equals(opr));

    // Store the incremented/decremented value (may fail if the operand is not a
    // supported Lvalue).
    return new StoreSwitch(incrDecr).doSwitch(oprndExpr);
  }

  @Override
  public MLIRValue casePostfixExpression(PostfixExpression expr) {
    var opr = expr.getOperator();
    assert "++".equals(opr) ||
        "--".equals(opr) : "NYI: Postfix operator: " + opr;

    var oprndExpr = expr.getOperand();
    var oprnd = doSwitch(oprndExpr);

    var incrDecr = emitIncrementOrDecrement(oprnd, "--".equals(opr));
    new StoreSwitch(incrDecr).doSwitch(oprndExpr);

    return oprnd;
  }

  @Override
  public MLIRValue caseConcatenationExpression(ConcatenationExpression concat) {
    var parts = concat.getParts();
    assert parts.size() >= 2;

    var head = doSwitch(parts.get(0));
    for (int i = 1; i < parts.size(); ++i) {
      var next = doSwitch(parts.get(i));
      head = emitBinaryOp("coredsl.concat",
                          getType(head.type.width + next.type.width, false),
                          head, next);
    }

    return head;
  }

  @Override
  public MLIRValue caseConditionalExpression(ConditionalExpression expr) {
    var type = mapType(ac.getExpressionType(expr));

    var cond = doSwitch(expr.getCondition());
    var cast = cc.makeI1Cast(cond);

    var values = cc.getValues();
    var counter = cc.getCounter();

    var thenCC = new ConstructionContext(
        new LinkedHashMap<NamedEntity, MLIRValue>(values),
        new AtomicInteger(counter), ac, new StringBuilder());

    var elseCC = new ConstructionContext(
        new LinkedHashMap<NamedEntity, MLIRValue>(values),
        new AtomicInteger(counter), ac, new StringBuilder());

    Streams.forEachPair(
        Stream.of(thenCC, elseCC),
        Stream.of(expr.getThenExpression(), expr.getElseExpression()),
        (xCC, xExpr) -> {
          var xVal = new ExpressionSwitch(xCC).doSwitch(xExpr);
          assert xCC.getUpdatedEntities().isEmpty()
              : "NYI: conditional expressions with side-effects";
          var xCast = xCC.makeCast(xVal, type);
          xCC.emitLn("scf.yield %s : %s", xCast, type);
        });

    var result = cc.makeAnonymousValue(type);
    cc.emitLn("%s = scf.if %s -> (%s) {\n%s} else {\n%s}", result, cast, type,
              thenCC.getStringBuilder().toString().indent(N_SPACES),
              elseCC.getStringBuilder().toString().indent(N_SPACES));

    return result;
  }

  @Override
  public MLIRValue caseCastExpression(CastExpression cast) {
    var source = doSwitch(cast.getOperand());
    var type = mapType(ac.getExpressionType(cast));
    return cc.makeCast(source, type);
  }

  @Override
  public MLIRValue caseParenthesisExpression(ParenthesisExpression expr) {
    return doSwitch(expr.getInner());
  }

  @Override
  public MLIRValue caseFunctionCallExpression(FunctionCallExpression call) {
    var calleeRef = call.getTarget();
    assert calleeRef instanceof EntityReference : "Indirect call encountered";
    var callee = ((EntityReference)calleeRef).getTarget();
    assert callee instanceof FunctionDefinition;
    var funcTy = ac.getFunctionSignature((FunctionDefinition)callee);

    var args = call.getArguments().stream().map(this::doSwitch).toList();
    var argTys =
        funcTy.getParamTypes().stream().map(MLIRType::mapType).toList();
    var argsCastStr = Streams
                          .zip(args.stream(), argTys.stream(),
                               (arg, ty) -> cc.makeCast(arg, ty))
                          .map(Object::toString)
                          .collect(joining(", "));
    var argTysStr =
        argTys.stream().map(Object::toString).collect(joining(", "));
    if (funcTy.getReturnType().isVoid()) {
      cc.emitLn("func.call @%s(%s) : (%s) -> ()", callee.getName(), argsCastStr,
                argTysStr);
      return cc.makeAnonymousValue(MLIRType.DUMMY);
    }

    var retTy = mapType(funcTy.getReturnType());
    var retVal = cc.makeAnonymousValue(retTy);
    cc.emitLn("%s = func.call @%s(%s) : (%s) -> %s", retVal, callee.getName(),
              argsCastStr, argTysStr, retTy);
    return retVal;
  }

  @Override
  public MLIRValue defaultCase(EObject obj) {
    cc.emitLn("// unhandled: %s", obj);
    return cc.makeAnonymousValue(MLIRType.DUMMY);
  }
}

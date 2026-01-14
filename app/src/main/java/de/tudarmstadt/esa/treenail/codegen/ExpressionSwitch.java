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
import java.util.Stack;
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
    private boolean isNestedLvalue = false;
    private record StoreInfo (boolean isBitAccess,
                              RangeAnalyzer.RangeResult index,
                              // The original value modified through this store
                              MLIRValue modifiedValue,
                              MLIRType accessType) {}
    private final Stack<StoreInfo> storeStack;
    // The final store is special, because we are not setting an MLIRValue, but a NamedEntity
    private record FinalStoreInfo (boolean isBitAccess,
                                   RangeAnalyzer.RangeResult index,
                                   NamedEntity destEntity,
                                   // For other accesses, we can write to destEntity directly, but for bit accesses, the temporary the value was originally loaded into is required
                                   MLIRValue bitAccessTmpVal,
                                   MLIRType accessType) {}
    private FinalStoreInfo finalStore = null;
    StoreSwitch(MLIRValue newValue) {
      this.newValue = newValue;
      this.storeStack = new Stack<>();
    }

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
      var targetType = ac.getExpressionType(target);
      var isBitAccess = targetType.isIntegerType();

      var accessType = mapType(ac.getExpressionType(access));
      var index = RangeAnalyzer.analyze(access.getIndex(), access.getEndIndex(),
              targetType, cc, ExpressionSwitch.this);
      final boolean isTopLevel = !isNestedLvalue;
      if (target instanceof EntityReference) {
        var entity = ((EntityReference) target).getTarget();
        assert ac.getDeclaredType(entity) == targetType;
        var isLocal = cc.hasValue(entity);

        if (!isBitAccess) {
          assert !isLocal : "NYI: local arrays";
          if (isTopLevel) {
            var castValue = cc.makeCast(newValue, accessType);
            cc.emitLn("coredsl.set @%s[%s] = %s : %s", entity.getName(), index,
                    castValue, accessType);
            return castValue;
          } else {
            var writtenValue = cc.makeAnonymousValue(accessType);
            cc.emitLn("%s = coredsl.get @%s[%s] : %s", writtenValue, entity.getName(), index, accessType);
            finalStore = new FinalStoreInfo(false, index, entity, null, accessType);
            return writtenValue;
          }
        }

        MLIRValue oldValue;
        if (isLocal)
          oldValue = cc.getValue(entity);
        else {
          oldValue = cc.makeAnonymousValue(mapType(targetType));
          cc.emitLn("%s = coredsl.get @%s : %s", oldValue, entity.getName(),
                  oldValue.type);
        }

        if (isTopLevel) {
          var castValue = cc.makeCast(newValue, accessType);
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
        } else {
          var resValue = cc.makeAnonymousValue(accessType);
          cc.emitLn("%s = coredsl.bitextract %s[%s] : (%s) -> %s", resValue, oldValue, index, oldValue.type, accessType);
          // TODO: consider isLocal (theoretically already considered, because we can check by cc.hasValue(entity))
          finalStore = new FinalStoreInfo(true, index, entity, oldValue, accessType);
          return resValue;
        }
      } else {
        assert target instanceof IndexAccessExpression : "NYI: Nested Lvalues other than IndexAccessExpression: " + target.getClass();
        isNestedLvalue = true;
        var valueToStore = doSwitch(target);
        var returnValue = valueToStore;
        // TODO: Do we not need to do anything for non-bit accesses?
        // If this is a top level bit access, we don't need to extract the value, as we use bitset directly later
        if (isBitAccess && !isTopLevel) {
          var resValue = cc.makeAnonymousValue(accessType);
          cc.emitLn("%s = coredsl.bitextract %s[%s] : (%s) -> %s", resValue, valueToStore, index, valueToStore.type, accessType);
          returnValue = resValue;
        }
        storeStack.push(new StoreInfo(isBitAccess, index, valueToStore, accessType));
        if (isTopLevel) {
          var castValue = cc.makeCast(newValue, accessType);
          var toStore = castValue;;
          while (!storeStack.isEmpty()) {
            StoreInfo store = storeStack.pop();
            // TODO: implement non bit accesses
            assert store.isBitAccess : "NYI: array access";
            var resVal = cc.makeAnonymousValue(store.modifiedValue.type);
            cc.emitLn("%s = coredsl.bitset %s[%s] = %s : (%s, %s) -> %s", resVal, store.modifiedValue, store.index, toStore, store.modifiedValue.type, store.accessType, store.modifiedValue.type);
            toStore = resVal;
          }
          assert finalStore != null;
          // TODO: Handle locals
          if (finalStore.isBitAccess) {
            var dstType = mapType(ac.getDeclaredType(finalStore.destEntity));
            var tmpRes = cc.makeAnonymousValue(dstType);
            cc.emitLn("%s = coresdl.bitset %s[%s] = %s : (%s, %s) -> %s", tmpRes, finalStore.bitAccessTmpVal, finalStore.index, toStore, dstType, finalStore.accessType, tmpRes.type);
            cc.emitLn("coredsl.set @%s = %s : %s", finalStore.destEntity.getName(), tmpRes, dstType);
          } else {
            cc.emitLn("coredsl.set @%s[%s] = %s : %s", finalStore.destEntity.getName(), finalStore.index, toStore, finalStore.accessType);
          }
          // TODO: Not sure if returning castValue is right here
          return castValue;
        }
        return returnValue;
      }
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
    var value = cc.getConstantValue(konst, type);
    return cc.makeConst(value, type);
  }

  @Override
  public MLIRValue caseEntityReference(EntityReference reference) {
    var entity = reference.getTarget();
    if (cc.hasValue(entity))
      // It's a local variable, retrieve its last definition.
      return cc.getValue(entity);

    var type = mapType(ac.getDeclaredType(entity));
    if (cc.isConstant(reference))
      // If it's a compile-time parameter emit a constant
      return cc.makeConst(cc.getConstantValue(reference, type), type);

    // Otherwise, emit a `coredsl.get`.
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

    // Otherwise, we indexing into an arch state item and retrieve one or more
    // elements.
    assert targetType.isAddressSpaceType() || targetType.isArrayType();
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
    var isICMP = op.startsWith("hwarith.icmp");
    if (op.startsWith("hwarith.") && !isICMP)
      cc.emitLn("%s = %s %s, %s : (%s, %s) -> %s", res, op, lhs, rhs, lhs.type,
                rhs.type, res.type);
    else {
      var immRes = isICMP ? cc.makeAnonymousValue(resType) : res;
      cc.emitLn("%s = %s %s, %s : %s, %s", immRes, op, lhs, rhs, lhs.type,
                rhs.type);
      // For backwards compatibility, additionally emit a cast back to ui1
      if (isICMP)
        cc.emitLn("%s = hwarith.cast %s : (i1) -> ui1", res, immRes);
    }
    return res;
  }

  private static MLIRValue convertIntToBool(MLIRValue value,
                                            ConstructionContext cc) {
    if (value.type.isSigned || value.type.width > 1) {
      var zero = cc.makeConst(BigInteger.ZERO, value.type);
      var valueAsBool = cc.makeAnonymousValue(MLIRType.getType(1, false));
      cc.emitLn("%s = hwarith.icmp ne %s %s", valueAsBool, value, zero);
      return valueAsBool;
    }
    return value;
  }

  @Override
  public MLIRValue caseInfixExpression(InfixExpression expr) {
    var lhs = doSwitch(expr.getLeft());
    var opr = expr.getOperator();
    var type = mapType(ac.getExpressionType(expr));
    final boolean isLAnd = "&&".equals(opr);
    final boolean isLOr = "||".equals(opr);
    if (isLAnd || isLOr) {
      var result = cc.makeAnonymousValue(type);
      var hwarithBoolLhs = convertIntToBool(lhs, cc);
      var boolLhs = cc.makeI1Cast(hwarithBoolLhs);

      var values = cc.getValues();
      var counter = cc.getCounter();
      var thenCC = new ConstructionContext(new LinkedHashMap<>(values),
                                           new AtomicInteger(counter), ac,
                                           new StringBuilder());
      var elseCC = new ConstructionContext(new LinkedHashMap<>(values),
                                           new AtomicInteger(counter), ac,
                                           new StringBuilder());
      /*
      The logic for outputting both branches of the short-circuiting if for
      '&&' and '||' is equivalent, but the branches are swapped. Thus, assign
      which construction context to emit a constant result (and which constant
      result) and which emits the evaluation of rhs.
      '&&':
      %result = %scf.if %lhs -> ui1 {
        ; evaluate rhs here
        yield %rhs : ui1
      } else {
        yield 0 : ui1
      }
      '||'
      %result = %scf.if %lhs {
        yield 1 : ui1
      } else {
        ; evaluate rhs here
        yield %rhs : ui1
      }
      */
      BigInteger constValToYield;
      ConstructionContext yieldConstCC;
      ConstructionContext emitRhsCC;
      if (isLAnd) {
        constValToYield = BigInteger.ZERO;
        yieldConstCC = elseCC;
        emitRhsCC = thenCC;
      } else {
        constValToYield = BigInteger.ONE;
        yieldConstCC = thenCC;
        emitRhsCC = elseCC;
      }
      assert yieldConstCC != emitRhsCC;
      var rhs = new ExpressionSwitch(emitRhsCC).doSwitch(expr.getRight());
      var hwarithBoolRhs = convertIntToBool(rhs, emitRhsCC);
      emitRhsCC.emitLn("scf.yield %s : %s", hwarithBoolRhs, type);

      var constToYield = yieldConstCC.makeConst(constValToYield, type);
      yieldConstCC.emitLn("scf.yield %s : %s", constToYield, type);
      cc.emitLn("%s = scf.if %s -> (%s) {\n%s} else {\n%s}", result, boolLhs,
                type, thenCC.getStringBuilder().toString().indent(N_SPACES),
                elseCC.getStringBuilder().toString().indent(N_SPACES));
      return result;
    }
    var rhs = doSwitch(expr.getRight());

    var op = binaryOperatorMap.get(opr);
    assert op != null : "NYI: operator " + opr;
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
      MLIRValue lhs;
      var type = mapType(ac.getExpressionType(expr));
      if ("~".equals(opr)) {
        // To invert the value we need a -1 constant to xor with
        lhs = cc.makeHWConst(BigInteger.ONE.negate(), type.width);
        lhs = cc.makeHWConstCast(lhs, type.width, type);
      } else {
        lhs = cc.makeConst(BigInteger.ZERO, getType(1, false));
      }
      return emitBinaryOp(unaryOperatorMap.get(opr), type, lhs, oprnd);
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

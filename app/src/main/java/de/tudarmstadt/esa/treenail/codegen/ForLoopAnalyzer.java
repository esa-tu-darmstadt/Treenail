package de.tudarmstadt.esa.treenail.codegen;

import static de.tudarmstadt.esa.treenail.codegen.ConstructionContext.ensureBigInteger;

import com.minres.coredsl.analysis.AnalysisContext;
import com.minres.coredsl.coreDsl.*;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;

class ForLoopAnalyzer {
  // Wrapper to make us be able to write the same logic once for things that
  // can be computed at compile-time or at runtime
  // Note that runtime computed values will emit code into their given
  // ConstructionContext, so the values should be put into a temporary one if
  // we don't know whether the code for them should be emitted
  public static abstract sealed class ConstOrRuntimeValue permits ConstValue,
      RuntimeValue {
    abstract void addOne();
    abstract void subOne();
    abstract void negate();
    // Return whether the value may be negative
    abstract boolean mayBeNegative();
    abstract MLIRType getType();
    abstract MLIRValue getAsMLIRValue(MLIRType type);
  };

  private static final class ConstValue extends ConstOrRuntimeValue {
    BigInteger value;
    ConstructionContext cc;

    ConstValue(BigInteger val, ConstructionContext cc) {
      this.value = val;
      this.cc = cc;
    }

    @Override
    void addOne() {
      value = value.add(BigInteger.ONE);
    }

    @Override
    void subOne() {
      value = value.subtract(BigInteger.ONE);
    }

    @Override
    boolean mayBeNegative() {
      return value.signum() < 0;
    }

    @Override
    void negate() {
      value = value.negate();
    }

    @Override
    MLIRType getType() {
      return MLIRType.determineType(value);
    }

    @Override
    MLIRValue getAsMLIRValue(MLIRType type) {
      return cc.makeHWConst(value, type.width);
    }
  }

  private static final class RuntimeValue extends ConstOrRuntimeValue {
    MLIRValue currValue;
    ConstructionContext cc;
    RuntimeValue(MLIRValue beginValue, ConstructionContext cc) {
      this.currValue = beginValue;
      this.cc = cc;
    }

    @Override
    void addOne() {
      currValue =
          ExpressionSwitch.emitIncrementOrDecrement(cc, currValue, false);
    }

    @Override
    void subOne() {
      currValue =
          ExpressionSwitch.emitIncrementOrDecrement(cc, currValue, true);
    }

    @Override
    void negate() {
      var zero = cc.makeConst(BigInteger.ZERO, MLIRType.getType(1, false));
      int resWidth = currValue.type.isSigned ? currValue.type.width
                                             : currValue.type.width + 1;
      var resultType = MLIRType.getType(resWidth, true);
      var intermediateResultType =
          MLIRType.getSubResultType(zero.type, currValue.type);
      var intermediateRes = cc.makeAnonymousValue(intermediateResultType);
      cc.emitLn("%s = hwarith.sub %s, %s : (%s, %s) -> %s", intermediateRes,
                zero, currValue, zero.type, currValue.type,
                intermediateResultType);
      var newCurr = cc.makeAnonymousValue(resultType);
      cc.emitLn("%s = hwarith.cast %s : (%s) -> %s", newCurr, intermediateRes,
                intermediateRes.type, resultType);
      currValue = newCurr;
    }

    @Override
    boolean mayBeNegative() {
      return currValue.type.isSigned;
    }

    @Override
    MLIRType getType() {
      return currValue.type;
    }

    @Override
    MLIRValue getAsMLIRValue(MLIRType type) {
      return cc.makeSignlessCast(currValue, type.width);
    }
  }

  static class Initialization {
    NamedEntity variable;
    ConstOrRuntimeValue value;
  }

  static class Condition {
    NamedEntity variable;
    String relation;
    ConstOrRuntimeValue bound;
  }

  static class Action {
    NamedEntity variable;
    ConstOrRuntimeValue step;
  }

  static final Set<String> CMP = Set.of("==", "!=", "<", "<=", ">", ">=");
  static final Set<String> FOR_COMPATIBLE_CMP = Set.of("<", "<=", ">", ">=");
  static final Set<String> INCR_DECR = Set.of("++", "--");
  static final Set<String> COMP_ASSIGN = Set.of("+=", "-=");

  // Either get the existing MLIR value that represents entity or load the
  // value using coredsl.get if it is architectural state
  static MLIRValue getOrLoadEntityValue(NamedEntity entity,
                                        ConstructionContext cc,
                                        AnalysisContext ac) {
    var mlirValue = cc.getValue(entity);
    if (mlirValue == null) {
      var type = MLIRType.mapType(ac.getDeclaredType(entity));
      mlirValue = cc.makeAnonymousValue(type);
      cc.emitLn("%s = coredsl.get @%s : %s", mlirValue, entity.getName(), type);
    }
    return mlirValue;
  }

  static Initialization analyzeInitialization(ForLoop loop,
                                              ConstructionContext cc,
                                              AnalysisContext ac) {
    var res = new Initialization();
    try {
      var decl = loop.getStartDeclaration();
      // Only support the in-place declaration form for now, as we otherwise
      // woudn't know if the iteration variable is live after the loop.
      if (decl == null || loop.getStartExpression() != null)
        return null;
      var dtors = decl.getDeclarators();
      if (dtors.size() != 1)
        return null;
      var dtor = dtors.get(0);
      var init = dtor.getInitializer();
      if (init == null)
        return null;
      var exprInit = (ExpressionInitializer)init;
      res.variable = dtor;
      if (exprInit.getValue() instanceof EntityReference entityRef) {
        var mlirValue = getOrLoadEntityValue(entityRef.getTarget(), cc, ac);
        res.value = new RuntimeValue(mlirValue, cc);
      } else if (exprInit.getValue() instanceof IntegerConstant konst) {
        var resVal = ensureBigInteger(konst.getValue(), null);
        res.value = new ConstValue(resVal, cc);
      } else {
        return null;
      }
    } catch (ClassCastException cce) {
      return null;
    }
    return res;
  }

  static List<Statement> getLoopBody(ForLoop loop) {
    var loopStmt = loop.getBody();
    if (loopStmt instanceof CompoundStatement compound) {
      return compound.getStatements();
    } else {
      return List.of(loopStmt);
    }
  }

  static Condition analyzeCondition(ForLoop loop, ConstructionContext cc,
                                    AnalysisContext ac) {
    var expr = loop.getCondition();
    var res = new Condition();
    if (!(expr instanceof InfixExpression infix)) {
      return null;
    }
    var opr = infix.getOperator();
    if (!CMP.contains(opr))
      return null;
    if (!(infix.getLeft() instanceof EntityReference ref)) {
      return null;
    }
    List<Statement> loopBody = getLoopBody(loop);
    if (cc.isConstant(infix.getRight())) {
      var constVal = cc.getConstantValue(infix.getRight(), null);
      res.bound = new ConstValue(constVal, cc);
    } else {
      if (infix.getRight() instanceof EntityReference entityReference) {
        NamedEntity entity = entityReference.getTarget();
        if (AliasAnalysis.entityMayBeModifiedIn(entity, loopBody)) {
          return null;
        }
        var mlirValue = getOrLoadEntityValue(entity, cc, ac);
        res.bound = new RuntimeValue(mlirValue, cc);
      } else {
        return null;
      }
    }
    // If the iterator is modified in the loop, this cannot be converted to
    // scf.for
    if (AliasAnalysis.entityMayBeModifiedIn(ref.getTarget(), loopBody)) {
      return null;
    }
    res.variable = ref.getTarget();
    res.relation = opr;
    return res;
  }

  private static Action analyzePrefixAction(Expression expr,
                                            ConstructionContext cc) {
    var res = new Action();
    try {
      var prefix = (PrefixExpression)expr;
      var opr = prefix.getOperator();
      if (!INCR_DECR.contains(opr))
        return null;
      var ref = (EntityReference)prefix.getOperand();
      res.variable = ref.getTarget();
      var stepVal = "++".equals(opr) ? BigInteger.ONE : BigInteger.ONE.negate();
      res.step = new ConstValue(stepVal, cc);
    } catch (ClassCastException cce) {
      return null;
    }
    return res;
  }

  private static Action analyzePostfixAction(Expression expr,
                                             ConstructionContext cc) {
    var res = new Action();
    try {
      var postfix = (PostfixExpression)expr;
      var opr = postfix.getOperator();
      if (!INCR_DECR.contains(opr))
        return null;
      var ref = (EntityReference)postfix.getOperand();
      res.variable = ref.getTarget();
      var stepValue =
          "++".equals(opr) ? BigInteger.ONE : BigInteger.ONE.negate();
      res.step = new ConstValue(stepValue, cc);
    } catch (ClassCastException cce) {
      return null;
    }
    return res;
  }

  private static Action analyzeCompoundAssignmentAction(Expression expr,
                                                        List<Statement> loopBody,
                                                        ConstructionContext cc,
                                                        AnalysisContext ac) {
    var res = new Action();
    try {
      var assign = (AssignmentExpression)expr;
      var opr = assign.getOperator();
      if (!COMP_ASSIGN.contains(opr))
        return null;
      var lhs = (EntityReference)assign.getTarget();
      if (assign.getValue() instanceof IntegerConstant rhs) {
        BigInteger stepVal = "+=".equals(opr)
                                 ? ensureBigInteger(rhs.getValue(), null)
                                 : rhs.getValue().negate();
        res.step = new ConstValue(stepVal, cc);
      } else if (assign.getValue() instanceof EntityReference rhs) {
        var stepEntity = rhs.getTarget();
        if (AliasAnalysis.entityMayBeModifiedIn(stepEntity, loopBody)) {
          return null;
        }
        var mlirValue = getOrLoadEntityValue(stepEntity, cc, ac);
        res.step = new RuntimeValue(mlirValue, cc);
        if ("-=".equals(opr)) {
          // TODO: this always makes it impossible to create an scf.for from
          // this, as if the value was unsigned before, it is now signed
          // If the value were negated again after, we still wouldn't know that
          // it was originally unsigned
          res.step.negate();
        }
      } else {
        return null;
      }
      res.variable = lhs.getTarget();
    } catch (ClassCastException cce) {
      return null;
    }
    return res;
  }

  static Action analyzeAction(ForLoop loop, ConstructionContext cc,
                              AnalysisContext ac) {
    var exprs = loop.getLoopExpressions();
    if (exprs.size() != 1)
      return null;
    var expr = exprs.get(0);
    Action res;
    res = analyzePrefixAction(expr, cc);
    if (res != null)
      return res;
    res = analyzePostfixAction(expr, cc);
    if (res != null)
      return res;
    res = analyzeCompoundAssignmentAction(expr, getLoopBody(loop), cc, ac);
    if (res != null)
      return res;
    return null;
  }
}

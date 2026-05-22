package de.tudarmstadt.esa.treenail.codegen;

import static de.tudarmstadt.esa.treenail.codegen.ConstructionContext.ensureBigInteger;
import com.minres.coredsl.coreDsl.AssignmentExpression;
import com.minres.coredsl.coreDsl.EntityReference;
import com.minres.coredsl.coreDsl.Expression;
import com.minres.coredsl.coreDsl.ExpressionInitializer;
import com.minres.coredsl.coreDsl.ForLoop;
import com.minres.coredsl.coreDsl.InfixExpression;
import com.minres.coredsl.coreDsl.IntegerConstant;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.coreDsl.PostfixExpression;
import com.minres.coredsl.coreDsl.PrefixExpression;
import com.minres.coredsl.coreDsl.Declarator;
import com.minres.coredsl.coreDsl.Declaration;
import com.minres.coredsl.coreDsl.IndexAccessExpression;
import com.minres.coredsl.coreDsl.TypeQualifier;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

class ForLoopAnalyzer {
  // Wrapper to make us be able to write the same logic once for things that
  // can be computed at compile-time or at runtime
  // Note that runtime computed values will emit code into their given
  // ConstructionContext, so the values should be put into a temporary one if
  // we don't know whether the code for them should be emitted
  public static abstract sealed class ConstOrRuntimeValue permits ConstValue, RuntimeValue {
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
      var one = cc.makeConst(BigInteger.ONE, MLIRType.getType(1, false));
      var resultType = MLIRType.getAddResultType(currValue.type, one.type);
      var newCurr = cc.makeAnonymousValue(resultType);
      cc.emitLn("%s = hwarith.add %s, %s : %s", newCurr, currValue, one, newCurr.type);
      currValue = newCurr;
    }

    @Override
    void subOne() {
      var one = cc.makeConst(BigInteger.ONE, MLIRType.getType(1, false));
      var resultType = MLIRType.getSubResultType(currValue.type, one.type);
      var newCurr = cc.makeAnonymousValue(resultType);
      cc.emitLn("%s = hwarith.sub %s, %s : %s", newCurr, currValue, one, newCurr.type);
      currValue = newCurr;
    }

    @Override
    void negate() {
      var zero = cc.makeConst(BigInteger.ZERO, MLIRType.getType(1, false));
      int resWidth = currValue.type.isSigned ? currValue.type.width : currValue.type.width + 1;
      var resultType = MLIRType.getType(resWidth, true);
      var newCurr = cc.makeAnonymousValue(resultType);
      cc.emitLn("%s = hwarith.sub %s, %s : %s", newCurr, zero, currValue, newCurr.type);
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
      var res = cc.makeAnonymousValue(MLIRType.DUMMY);
      cc.emitLn("%s = hwarith.cast %s : (%s) -> i%d", res, currValue, currValue.type, type.width);
      return res;
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

  static Initialization analyzeInitialization(ForLoop loop, ConstructionContext cc) {
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
        res.value = new RuntimeValue(cc.getValue(entityRef.getTarget()), cc);
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

  private static Declarator getEntityDeclarator(NamedEntity entity) {
    Declaration decl = (Declaration)entity.eContainer();
    for (Declarator d : decl.getDeclarators()) {
      if (d.getName().equals(entity.getName())) {
        return d;
      }
    }
    assert false : "This should be unreachable";
    return null;
  }

  // Returns null if object is not an alias declaration
  private static void getAliasDeclarators(EObject object, ArrayList<Declarator> aliasDeclarators) {
    if (!(object instanceof Declaration decl)) {
      return;
    }
    for (Declarator declarator : decl.getDeclarators()) {
      if (declarator.isAlias()) {
        aliasDeclarators.add(declarator);
      }
    }
  }

  // TODO: needs to also find other aliases
  // Returns null if any alias initializer could not be resolved
  private static HashSet<NamedEntity> getAllEntityAliases(NamedEntity entity) {
    Declarator d = getEntityDeclarator(entity);
    var confirmedAliases = new HashSet<NamedEntity>();
    while (d.isAlias()) {
      confirmedAliases.add(d);
      if (d.getInitializer() instanceof EntityReference entityRef) {
        d = getEntityDeclarator(entityRef.getTarget());
      } else {
        // TODO: could check more complicated declarations here as well
        return null;
      }
    }
    confirmedAliases.add(d);
    var aliasDeclarators = new ArrayList<Declarator>();
    for (NamedEntity namedEntity : confirmedAliases) {
      if (namedEntity.eContainer() instanceof Declarator decl) {
        // TODO: also search the scope of this entity for aliases of it
        var declParent = decl.eContainer();
        for (var it = declParent.eAllContents(); it.hasNext(); ) {
          EObject item = it.next();
          getAliasDeclarators(item, aliasDeclarators);
        }
      }
    }
    // iterate alias declarators to see if any reference res
    for (Declarator aliasDeclarator : aliasDeclarators) {
      var init = aliasDeclarator.getInitializer();
      assert init != null : "Alias declarations must have an initializer";
      // TODO: is that cast allowed?
      if (containsOneOf((Expression)init, confirmedAliases)) {
        // TODO: need to insert the NamedEntity into confirmedAliases
      }
    }
    return confirmedAliases;
  }

  private static boolean containsOneOf(Expression expr, HashSet<NamedEntity> entities) {
    while (expr instanceof IndexAccessExpression indexAccess) {
      expr = indexAccess.getTarget();
    }
    assert expr instanceof EntityReference;
    EntityReference entityReference = (EntityReference)expr;
    return entities.contains(entityReference.getTarget());
  }

  // Returns true if we cannot prove that the entity is not modified in the loop
  private static boolean entityMayBeModifiedInLoop(NamedEntity entity, ForLoop loop) {
    var aliases = getAllEntityAliases(entity);
    if (aliases == null) {
      return true;
    }
    var nonConstAliases = new HashSet<NamedEntity>();
    for (NamedEntity e : aliases) {
      Declaration d = (Declaration)e.eContainer();
      if (!d.getQualifiers().contains(TypeQualifier.CONST)) {
        nonConstAliases.add(e);
      }
    }
    if (nonConstAliases.isEmpty()) {
      return false;
    }
    // TODO: check if any of the given entities is modified in the for loop
    // - modified if any of the nonConstAliases is in an assignment expression
    for (var expr : loop.getLoopExpressions()) {
      for (TreeIterator<EObject> it = expr.eAllContents(); it.hasNext(); ) {
        var item = it.next();
        if (item instanceof AssignmentExpression assignmentExpression) {
          Expression target = assignmentExpression.getTarget();
          if (containsOneOf(target, nonConstAliases)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  static Condition analyzeCondition(ForLoop loop, ConstructionContext cc) {
    var expr = loop.getCondition();
    var res = new Condition();
    try {
      var infix = (InfixExpression)expr;
      var opr = infix.getOperator();
      if (!CMP.contains(opr))
        return null;
      var ref = (EntityReference)infix.getLeft();
      if (cc.isConstant(infix.getRight())) {
        var constVal = cc.getConstantValue(infix.getRight(), null);
        res.bound = new ConstValue(constVal, cc);
      } else {
        if (infix.getRight() instanceof EntityReference entityReference) {
          NamedEntity entity = entityReference.getTarget();
          if (entityMayBeModifiedInLoop(entity, loop)) {
            return null;
          }

          res.bound = new RuntimeValue(cc.getValue(entityReference.getTarget()), cc);
        } else {
          return null;
        }
      }
      res.variable = ref.getTarget();
      res.relation = opr;
    } catch (ClassCastException cce) {
      return null;
    }
    return res;
  }

  private static Action analyzePrefixAction(Expression expr, ConstructionContext cc) {
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

  private static Action analyzePostfixAction(Expression expr, ConstructionContext cc) {
    var res = new Action();
    try {
      var postfix = (PostfixExpression)expr;
      var opr = postfix.getOperator();
      if (!INCR_DECR.contains(opr))
        return null;
      var ref = (EntityReference)postfix.getOperand();
      res.variable = ref.getTarget();
      var stepValue = "++".equals(opr) ? BigInteger.ONE : BigInteger.ONE.negate();
      res.step = new ConstValue(stepValue, cc);
    } catch (ClassCastException cce) {
      return null;
    }
    return res;
  }

  private static Action analyzeCompoundAssignmentAction(Expression expr, ConstructionContext cc) {
    var res = new Action();
    try {
      var assign = (AssignmentExpression)expr;
      var opr = assign.getOperator();
      if (!COMP_ASSIGN.contains(opr))
        return null;
      var lhs = (EntityReference)assign.getTarget();
      if (assign.getValue() instanceof IntegerConstant rhs) {
        BigInteger stepVal = "+=".equals(opr) ? ensureBigInteger(rhs.getValue(), null)
                : rhs.getValue().negate();
        res.step = new ConstValue(stepVal, cc);
      } else if (assign.getValue() instanceof EntityReference rhs) {
        res.step = null;
        res.step = new RuntimeValue(cc.getValue(rhs.getTarget()), cc);
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

  static Action analyzeAction(ForLoop loop, ConstructionContext cc) {
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
    res = analyzeCompoundAssignmentAction(expr, cc);
    if (res != null)
      return res;
    return null;
  }
}

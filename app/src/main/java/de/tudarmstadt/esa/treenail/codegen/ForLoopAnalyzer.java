package de.tudarmstadt.esa.treenail.codegen;

import static de.tudarmstadt.esa.treenail.codegen.ConstructionContext.ensureBigInteger;

import com.minres.coredsl.analysis.AnalysisContext;
import com.minres.coredsl.coreDsl.AssignmentExpression;
import com.minres.coredsl.coreDsl.Statement;
import com.minres.coredsl.coreDsl.CompoundStatement;
import com.minres.coredsl.coreDsl.ISA;
import com.minres.coredsl.coreDsl.EntityReference;
import com.minres.coredsl.coreDsl.Expression;
import com.minres.coredsl.coreDsl.ExpressionInitializer;
import com.minres.coredsl.coreDsl.ForLoop;
import com.minres.coredsl.coreDsl.InfixExpression;
import com.minres.coredsl.coreDsl.IntegerConstant;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.coreDsl.PostfixExpression;
import com.minres.coredsl.coreDsl.PrefixExpression;
import com.minres.coredsl.coreDsl.BitField;
import com.minres.coredsl.coreDsl.Declarator;
import com.minres.coredsl.coreDsl.Declaration;
import com.minres.coredsl.coreDsl.IndexAccessExpression;
import com.minres.coredsl.coreDsl.TypeQualifier;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;

import java.math.BigInteger;
import java.util.List;
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

  // Either get the existing MLIR value that represents entity or load the
  // value using coredsl.get
  // TODO: name
  static MLIRValue getOrMakeEntityValue(NamedEntity entity, ConstructionContext cc, AnalysisContext ac) {
    var mlirValue = cc.getValue(entity);
    if (mlirValue == null) {
      var type = MLIRType.mapType(ac.getDeclaredType(entity));
      mlirValue = cc.makeAnonymousValue(type);
      // TODO: check syntax
      cc.emitLn("%s = coredsl.get @%s", mlirValue, entity.getName());
    }
    return mlirValue;
  }

  static Initialization analyzeInitialization(ForLoop loop, ConstructionContext cc, AnalysisContext ac) {
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
        var mlirValue = getOrMakeEntityValue(entityRef.getTarget(), cc, ac);
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
  // TODO: as soon as we reach a const alias, we can quit
  private static HashSet<NamedEntity> getAllEntityAliases(NamedEntity entity) {
    var currEntity = entity;
    var confirmedAliases = new HashSet<NamedEntity>();
    // Alias declarations are only allowed in architectural state, so aliases
    // of local variables are impossible
    // First container is declaration, second declaration statement, third is
    // the statement this is contained in (e.g. CompoundStatement)
    if (!(currEntity.eContainer().eContainer().eContainer() instanceof ISA)) {
      confirmedAliases.add(entity);
      return confirmedAliases;
    }
    System.out.println("Variable " + entity.getName());
    while (currEntity instanceof Declarator d && d.isAlias()) {
      confirmedAliases.add(currEntity);
      var initializer = d.getInitializer();
      if (initializer instanceof ExpressionInitializer exprInit && exprInit.getValue() instanceof EntityReference entityRef) {
        currEntity = entityRef.getTarget();
      } else {
        // TODO: could check more complicated declarations here as well
        System.out.println("Returning null :(");
        if (initializer instanceof ExpressionInitializer exprInit) {
          System.out.println(exprInit.getValue());
        } else
          System.out.println(d.getInitializer());
        return null;
      }
    }
    confirmedAliases.add(currEntity);
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
        confirmedAliases.add(aliasDeclarator);
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
    System.out.println("Aliases:");
    for (var alias : aliases) {
      System.out.println(alias.getName());
    }
    var nonConstAliases = new HashSet<NamedEntity>();
    for (NamedEntity e : aliases) {
      if (e instanceof Declarator) {
        Declaration d = (Declaration)e.eContainer();
        if (!d.getQualifiers().contains(TypeQualifier.CONST)) {
          nonConstAliases.add(e);
        }
      } else {
        assert e instanceof BitField : "NamedEntity other than Declarator or BitField not considered in this code";
        nonConstAliases.add(e);
      }
    }
    if (nonConstAliases.isEmpty()) {
      return false;
    }
    // TODO: check if any of the given entities is modified in the for loop
    // - modified if any of the nonConstAliases is in an assignment expression
    var loopStmt = loop.getBody();
    List<Statement> statements;
    if (loopStmt instanceof CompoundStatement compound) {
      statements = compound.getStatements();
    } else {
      statements = List.of(loopStmt);
    }
    for (var expr : statements) {
      for (TreeIterator<EObject> it = expr.eAllContents(); it.hasNext(); ) {
        var item = it.next();
        // TODO: ++ -- exprs
        if (item instanceof AssignmentExpression assignmentExpression) {
          Expression target = assignmentExpression.getTarget();
          if (containsOneOf(target, nonConstAliases)) {
            return true;
          }
        } else if (item instanceof PrefixExpression prefix) {
          // TODO: are there prefix expressions that don't mutate?
          if (containsOneOf(prefix.getOperand(), nonConstAliases)) {
            return true;
          }
        } else if (item instanceof PostfixExpression postfix) {
          // TODO: are there postfix expressions that don't mutate?
          if (containsOneOf(postfix.getOperand(), nonConstAliases)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  static Condition analyzeCondition(ForLoop loop, ConstructionContext cc, AnalysisContext ac) {
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
    if (cc.isConstant(infix.getRight())) {
      var constVal = cc.getConstantValue(infix.getRight(), null);
      res.bound = new ConstValue(constVal, cc);
    } else {
      if (infix.getRight() instanceof EntityReference entityReference) {
        NamedEntity entity = entityReference.getTarget();
        if (entityMayBeModifiedInLoop(entity, loop)) {
          return null;
        }
        var mlirValue = getOrMakeEntityValue(entity, cc, ac);
        res.bound = new RuntimeValue(mlirValue, cc);
      } else {
        return null;
      }
    }
    // If the iterator is modified in the loop, this cannot be converted to
    // scf.for
    if (entityMayBeModifiedInLoop(ref.getTarget(), loop)) {
      return null;
    }
    res.variable = ref.getTarget();
    res.relation = opr;
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

  private static Action analyzeCompoundAssignmentAction(Expression expr, ConstructionContext cc, AnalysisContext ac) {
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
        var mlirValue = getOrMakeEntityValue(rhs.getTarget(), cc, ac);
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

  static Action analyzeAction(ForLoop loop, ConstructionContext cc, AnalysisContext ac) {
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
    res = analyzeCompoundAssignmentAction(expr, cc, ac);
    if (res != null)
      return res;
    return null;
  }
}

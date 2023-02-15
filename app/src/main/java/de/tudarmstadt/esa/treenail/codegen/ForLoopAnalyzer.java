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
import java.math.BigInteger;
import java.util.Set;

class ForLoopAnalyzer {
  static class Initialization {
    NamedEntity variable;
    BigInteger value;
  }

  static class Condition {
    NamedEntity variable;
    String relation;
    BigInteger bound;
  }

  static class Action {
    NamedEntity variable;
    BigInteger step;
  }

  static final Set<String> CMP = Set.of("==", "!=", "<", "<=", ">", ">=");
  static final Set<String> INCR_DECR = Set.of("++", "--");
  static final Set<String> COMP_ASSIGN = Set.of("+=", "-=");

  static Initialization analyzeInitialization(ForLoop loop) {
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
      var konst = (IntegerConstant)exprInit.getValue();
      res.variable = dtor;
      res.value = ensureBigInteger(konst.getValue());
    } catch (ClassCastException cce) {
      return null;
    }
    return res;
  }

  static Condition analyzeCondition(ForLoop loop) {
    var expr = loop.getCondition();
    var res = new Condition();
    try {
      var infix = (InfixExpression)expr;
      var opr = infix.getOperator();
      if (!CMP.contains(opr))
        return null;
      var ref = (EntityReference)infix.getLeft();
      var konst = (IntegerConstant)infix.getRight();
      res.variable = ref.getTarget();
      res.relation = opr;
      res.bound = ensureBigInteger(konst.getValue());
    } catch (ClassCastException cce) {
      return null;
    }
    return res;
  }

  private static Action analyzePrefixAction(Expression expr) {
    var res = new Action();
    try {
      var prefix = (PrefixExpression)expr;
      var opr = prefix.getOperator();
      if (!INCR_DECR.contains(opr))
        return null;
      var ref = (EntityReference)prefix.getOperand();
      res.variable = ref.getTarget();
      res.step = "++".equals(opr) ? BigInteger.ONE : BigInteger.ONE.negate();
    } catch (ClassCastException cce) {
      return null;
    }
    return res;
  }

  private static Action analyzePostfixAction(Expression expr) {
    var res = new Action();
    try {
      var postfix = (PostfixExpression)expr;
      var opr = postfix.getOperator();
      if (!INCR_DECR.contains(opr))
        return null;
      var ref = (EntityReference)postfix.getOperand();
      res.variable = ref.getTarget();
      res.step = "++".equals(opr) ? BigInteger.ONE : BigInteger.ONE.negate();
    } catch (ClassCastException cce) {
      return null;
    }
    return res;
  }

  private static Action analyzeCompoundAssignmentAction(Expression expr) {
    var res = new Action();
    try {
      var assign = (AssignmentExpression)expr;
      var opr = assign.getOperator();
      if (!COMP_ASSIGN.contains(opr))
        return null;
      var lhs = (EntityReference)assign.getTarget();
      var rhs = (IntegerConstant)assign.getValue();
      res.variable = lhs.getTarget();
      res.step = "+=".equals(opr) ? ensureBigInteger(rhs.getValue())
                                  : rhs.getValue().negate();
    } catch (ClassCastException cce) {
      return null;
    }
    return res;
  }

  static Action analyzeAction(ForLoop loop) {
    var exprs = loop.getLoopExpressions();
    if (exprs.size() != 1)
      return null;
    var expr = exprs.get(0);
    Action res;
    res = analyzePrefixAction(expr);
    if (res != null)
      return res;
    res = analyzePostfixAction(expr);
    if (res != null)
      return res;
    res = analyzeCompoundAssignmentAction(expr);
    if (res != null)
      return res;
    return null;
  }
}

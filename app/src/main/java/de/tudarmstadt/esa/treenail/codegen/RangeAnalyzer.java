package de.tudarmstadt.esa.treenail.codegen;

import static java.lang.String.format;

import com.minres.coredsl.coreDsl.EntityReference;
import com.minres.coredsl.coreDsl.Expression;
import com.minres.coredsl.coreDsl.InfixExpression;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.util.TypedBigInteger;
import java.math.BigInteger;

class RangeAnalyzer {
  static class RangeResult {
    MLIRValue base;
    BigInteger from, to;

    public String toString() {
      assert from == null || !(from instanceof TypedBigInteger);
      assert to == null || !(to instanceof TypedBigInteger);

      if (base != null) {
        if (from != null && to != null)
          return format("%s : %s, %d:%d", base, base.type, from, to);

        return format("%s : %s", base, base.type);
      }

      if (from != null && to != null)
        return format("%d:%d", from, to);

      if (from != null)
        return from.toString();

      return "<invalid>";
    }
  }

  private static NamedEntity getEntity(Expression expr) {
    if (!(expr instanceof EntityReference))
      return null;
    return ((EntityReference)expr).getTarget();
  }

  private static BigInteger getOffset(Expression expr, NamedEntity entity,
                                      ConstructionContext cc) {
    if (!(expr instanceof InfixExpression))
      return null;
    var infixExpr = (InfixExpression)expr;
    var opr = infixExpr.getOperator();
    if (!("+".equals(opr) || "-".equals(opr)))
      return null;
    var ent = getEntity(infixExpr.getLeft());
    if (ent == null || ent != entity)
      return null;
    var rhs = infixExpr.getRight();
    if (!cc.isConstant(rhs))
      return null;

    var val = cc.getConstantValue(rhs);
    if ("-".equals(opr))
      val = val.negate();
    return val;
  }

  static RangeResult analyze(Expression fromExpr, Expression toExpr,
                             ConstructionContext cc,
                             ExpressionSwitch exprSwitch) {
    assert fromExpr != null;

    var res = new RangeResult();

    // Single element
    if (toExpr == null) {
      if (cc.isConstant(fromExpr)) {
        res.from = cc.getConstantValue(fromExpr);
        return res;
      }
      res.base = exprSwitch.doSwitch(fromExpr);
      return res;
    }

    // Constant range
    if (cc.isConstant(fromExpr) && cc.isConstant(toExpr)) {
      res.from = cc.getConstantValue(fromExpr);
      res.to = cc.getConstantValue(toExpr);
      return res;
    }

    // [x ± const : x]
    var entity = getEntity(toExpr);
    if (entity != null) {
      var offset = getOffset(fromExpr, entity, cc);
      if (offset != null) {
        res.base = cc.getValue(entity);
        res.from = offset;
        res.to = BigInteger.ZERO;
        assert res.base != null
            : "NYI: Architectural state element in range specifier";
        return res;
      }
    }

    // [x : x ± const]
    entity = getEntity(fromExpr);
    if (entity != null) {
      var offset = getOffset(toExpr, entity, cc);
      if (offset != null) {
        res.base = cc.getValue(entity);
        res.from = BigInteger.ZERO;
        res.to = offset;
        assert res.base != null
            : "NYI: Architectural state element in range specifier";
        return res;
      }
    }

    return null;
  }
}

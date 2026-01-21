package de.tudarmstadt.esa.treenail.codegen;

import static java.lang.String.format;

import com.minres.coredsl.coreDsl.EntityReference;
import com.minres.coredsl.coreDsl.Expression;
import com.minres.coredsl.coreDsl.InfixExpression;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.type.AddressSpaceType;
import com.minres.coredsl.type.ArrayType;
import com.minres.coredsl.type.CoreDslType;
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

    var val = cc.getConstantValue(rhs, null);
    if ("-".equals(opr))
      val = val.negate();
    return val;
  }

  private static MLIRType getIndexType(CoreDslType baseType) {
    BigInteger numElems;
    if (baseType instanceof ArrayType)
      numElems = BigInteger.valueOf(((ArrayType)baseType).count);
    else if (baseType instanceof AddressSpaceType)
      numElems = ((AddressSpaceType)baseType).count;
    else
      numElems = BigInteger.valueOf(baseType.getBitSize());
    return MLIRType.getType(numElems.bitLength() - 1, false);
  }

  static MLIRValue getEntityValue(NamedEntity entity, ConstructionContext cc,
                                  MLIRType castType) {
    var value = cc.getValue(entity);
    if (value == null) {
      var ac = cc.getAnalysisContext();
      var architecturalStateType = MLIRType.mapType(ac.getDeclaredType(entity));
      var architecturalStateVal = cc.makeAnonymousValue(architecturalStateType);
      cc.emitLn("%s = coredsl.get @%s : %s", architecturalStateVal,
                entity.getName(), architecturalStateType);
      value = architecturalStateVal;
    }
    return cc.makeCast(value, castType);
  }

  static RangeResult analyze(Expression fromExpr, Expression toExpr,
                             CoreDslType baseType, ConstructionContext cc,
                             ExpressionSwitch exprSwitch) {
    assert fromExpr != null;

    // Longnail is more restrictive than the frontend: the index value must be
    // a) unsigned and b) not wider than necessary.
    var indexType = getIndexType(baseType);

    var res = new RangeResult();

    // Single element
    if (toExpr == null) {
      if (cc.isConstant(fromExpr)) {
        res.from = cc.getConstantValue(fromExpr, null);
        return res;
      }
      res.base = cc.makeCast(exprSwitch.doSwitch(fromExpr), indexType);
      return res;
    }

    // Constant range
    if (cc.isConstant(fromExpr) && cc.isConstant(toExpr)) {
      res.from = cc.getConstantValue(fromExpr, null);
      res.to = cc.getConstantValue(toExpr, null);
      return res;
    }

    // [x ± const : x]
    var entity = getEntity(toExpr);
    if (entity != null) {
      var offset = getOffset(fromExpr, entity, cc);
      if (offset != null) {
        res.base = getEntityValue(entity, cc, indexType);
        res.from = offset;
        res.to = BigInteger.ZERO;
        return res;
      }
    }

    // [x : x ± const]
    entity = getEntity(fromExpr);
    if (entity != null) {
      var offset = getOffset(toExpr, entity, cc);
      if (offset != null) {
        res.base = getEntityValue(entity, cc, indexType);
        res.from = BigInteger.ZERO;
        res.to = offset;
        return res;
      }
    }

    assert false : ("NYI: more complicated range expressions, such as " +
                    "X[MEM[0] : MEM[0] + 1]");
    return null;
  }
}

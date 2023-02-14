package de.tudarmstadt.esa.treenail.codegen;

import static de.tudarmstadt.esa.treenail.codegen.LongnailCodegen.N_SPACES;
import static de.tudarmstadt.esa.treenail.codegen.MLIRType.mapType;
import static java.lang.String.format;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.Streams;
import com.minres.coredsl.analysis.AnalysisContext;
import com.minres.coredsl.analysis.StorageClass;
import com.minres.coredsl.coreDsl.CompoundStatement;
import com.minres.coredsl.coreDsl.Declaration;
import com.minres.coredsl.coreDsl.DeclarationStatement;
import com.minres.coredsl.coreDsl.ExpressionInitializer;
import com.minres.coredsl.coreDsl.ExpressionStatement;
import com.minres.coredsl.coreDsl.ForLoop;
import com.minres.coredsl.coreDsl.IfStatement;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.coreDsl.util.CoreDslSwitch;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.emf.ecore.EObject;

class StatementSwitch extends CoreDslSwitch<Object> {
  private final AnalysisContext ac;
  private final ConstructionContext cc;
  private final ExpressionSwitch exprSwitch;

  StatementSwitch(ConstructionContext cc) {
    this.ac = cc.getAnalysisContext();
    this.cc = cc;
    exprSwitch = new ExpressionSwitch(cc);
  }

  @Override
  public Object caseCompoundStatement(CompoundStatement compoundStmt) {
    for (var stmt : compoundStmt.getStatements())
      doSwitch(stmt);

    return this;
  }

  @Override
  public Object caseDeclaration(Declaration decl) {
    assert decl.getQualifiers().isEmpty()
        : "NYI: Const/volatile for local variables";

    for (var dtor : decl.getDeclarators()) {
      assert ac.getStorageClass(dtor) == StorageClass.local;
      var type = ac.getDeclaredType(dtor);
      assert type.isIntegerType() : "NYI: Local arrays";
      var init = dtor.getInitializer();
      if (init == null) {
        // Spec: Unitialized variables have an undefined value. It simplifies IR
        // construction if we just assume them to be zero. Unnecessary const ops
        // will be canonicalized away later in MLIR.
        var zero = cc.makeConst(BigInteger.ZERO, mapType(type));
        cc.setValue(dtor, zero);
        continue;
      }

      assert init instanceof ExpressionInitializer : "NYI: List initializers";
      var value = exprSwitch.doSwitch(((ExpressionInitializer)init).getValue());
      var castValue = cc.makeCast(value, mapType(type));
      cc.setValue(dtor, castValue);
    }

    return this;
  }

  @Override
  public Object caseDeclarationStatement(DeclarationStatement declStmt) {
    doSwitch(declStmt.getDeclaration());
    return this;
  }

  @Override
  public Object caseExpressionStatement(ExpressionStatement exprStmt) {
    exprSwitch.doSwitch(exprStmt.getExpression());
    return this;
  }

  @Override
  public Object caseIfStatement(IfStatement ifStmt) {
    var cond = exprSwitch.doSwitch(ifStmt.getCondition());
    var cast = cc.makeSignlessCast(cond);

    var values = cc.getValues();
    var counter = cc.getCounter();

    var hasElse = ifStmt.getElseBody() != null;

    var thenCC = new ConstructionContext(
        new LinkedHashMap<NamedEntity, MLIRValue>(values),
        new AtomicInteger(counter), ac, new StringBuilder());

    var elseCC = new ConstructionContext(
        new LinkedHashMap<NamedEntity, MLIRValue>(values),
        new AtomicInteger(counter), ac, new StringBuilder());

    new StatementSwitch(thenCC).doSwitch(ifStmt.getThenBody());
    if (hasElse)
      new StatementSwitch(elseCC).doSwitch(ifStmt.getElseBody());

    var thenUpdated = thenCC.getUpdatedEntities();
    var elseUpdated = elseCC.getUpdatedEntities();
    if (thenUpdated.isEmpty() && elseUpdated.isEmpty()) {
      // In lieu of proper live analysis: no local entities were written in
      // either branch, so we can emit a simple no-result `scf.if`.
      cc.emitLn("scf.if %s {\n%s}%s", cast,
                thenCC.getStringBuilder().toString().indent(N_SPACES),
                hasElse ? format(" else {\n%s}",
                                 elseCC.getStringBuilder().toString().indent(
                                     N_SPACES))
                        : "");
      return this;
    }

    // The `scf.if` has results (again, as far as we can tell without proper
    // analysis), so the `else` clause is mandatory.
    var updated = new LinkedHashSet<NamedEntity>();
    updated.addAll(thenUpdated);
    updated.addAll(elseUpdated);

    var thenValues = thenCC.getValues();
    var elseValues = elseCC.getValues();

    var thenYieldValues = updated.stream().map(thenValues::get).toList();
    var elseYieldValues = updated.stream().map(elseValues::get).toList();

    var resultTypes = thenYieldValues.stream().map(v -> v.type).toList();
    var resultValues =
        resultTypes.stream().map(cc::makeAnonymousValue).toList();
    Streams.forEachPair(updated.stream(), resultValues.stream(),
                        (ent, val) -> { cc.setValue(ent, val); });

    thenCC.emitLn(
        "scf.yield %s : %s",
        thenYieldValues.stream().map(Object::toString).collect(joining(", ")),
        resultTypes.stream().map(Object::toString).collect(joining(", ")));
    elseCC.emitLn(
        "scf.yield %s : %s",
        elseYieldValues.stream().map(Object::toString).collect(joining(", ")),
        resultTypes.stream().map(Object::toString).collect(joining(", ")));

    cc.emitLn(
        "%s = scf.if %s -> (%s) {\n%s} else {\n%s}",
        resultValues.stream().map(Object::toString).collect(joining(", ")),
        cast, resultTypes.stream().map(Object::toString).collect(joining(", ")),
        thenCC.getStringBuilder().toString().indent(N_SPACES),
        elseCC.getStringBuilder().toString().indent(N_SPACES));

    return this;
  }

  @Override
  public Object caseForLoop(ForLoop loop) {
    var startDecl = loop.getStartDeclaration();
    var startExpr = loop.getStartExpression();
    var condExpr = loop.getCondition();
    var loopExprs = loop.getLoopExpressions();
    var bodyStmt = loop.getBody();

    // Elaborate/evaluate the init-part of the header in the current context.
    if (startDecl != null)
      doSwitch(startDecl);
    if (startExpr != null)
      exprSwitch.doSwitch(startExpr);

    // Simulate construction to find loop-carried values, in lieu of proper
    // analysis.
    var simCC = new ConstructionContext(
        new LinkedHashMap<NamedEntity, MLIRValue>(cc.getValues()),
        new AtomicInteger(cc.getCounter()), ac, new StringBuilder());
    new ExpressionSwitch(simCC).doSwitch(condExpr);
    new StatementSwitch(simCC).doSwitch(bodyStmt);
    loopExprs.forEach(new ExpressionSwitch(simCC)::doSwitch);

    var loopCarriedVars = simCC.getUpdatedEntities().stream().toList();
    simCC = null; // Throw away the dummy construction.

    // Real construction begins here. See
    // https://mlir.llvm.org/docs/Dialects/SCFDialect/#scfwhile-mlirscfwhileop
    // for the use of "before" and "after" terms.
    var beforeCC = new ConstructionContext(
        new LinkedHashMap<NamedEntity, MLIRValue>(cc.getValues()),
        new AtomicInteger(cc.getCounter()), ac, new StringBuilder());

    var afterCC = new ConstructionContext(
        new LinkedHashMap<NamedEntity, MLIRValue>(cc.getValues()),
        new AtomicInteger(cc.getCounter()), ac, new StringBuilder());

    var argTypes = new ArrayList<MLIRType>();
    var beforeArgs = new LinkedHashMap<NamedEntity, MLIRValue>();
    var afterArgs = new LinkedHashMap<NamedEntity, MLIRValue>();
    var results = new ArrayList<MLIRValue>();
    for (var v : loopCarriedVars) {
      var type = mapType(ac.getDeclaredType(v));
      argTypes.add(type);

      beforeCC.setValue(v, beforeCC.makeAnonymousValue(type));
      beforeArgs.put(v, beforeCC.getValue(v));

      afterCC.setValue(v, afterCC.makeAnonymousValue(type));
      afterArgs.put(v, afterCC.getValue(v));

      results.add(cc.makeAnonymousValue(type));
    }

    var argTypesStr =
        argTypes.stream().map(Object::toString).collect(joining(", "));

    var cond = new ExpressionSwitch(beforeCC).doSwitch(condExpr);
    var condCast = beforeCC.makeSignlessCast(cond);
    var beforeCondVals =
        loopCarriedVars.stream().map(beforeCC::getValue).toList();
    beforeCC.emitLn(
        "scf.condition(%s) %s : %s", condCast,
        beforeCondVals.stream().map(Object::toString).collect(joining(", ")),
        argTypesStr);

    var bodySwitch = new StatementSwitch(afterCC);
    bodySwitch.doSwitch(bodyStmt);

    var loopExprSwitch = new ExpressionSwitch(afterCC);
    loopExprs.forEach(loopExprSwitch::doSwitch);

    var afterYieldVals =
        loopCarriedVars.stream().map(afterCC::getValue).toList();
    afterCC.emitLn(
        "scf.yield %s : %s",
        afterYieldVals.stream().map(Object::toString).collect(joining(", ")),
        argTypesStr);

    var beforeArgsStr =
        loopCarriedVars.stream()
            .map(e -> format("%s = %s", beforeArgs.get(e), cc.getValue(e)))
            .collect(joining(", "));
    var afterArgsStr =
        loopCarriedVars.stream()
            .map(e -> format("%s: %s", afterArgs.get(e), afterArgs.get(e).type))
            .collect(joining(", "));
    var resultsStr =
        results.stream().map(Object::toString).collect(joining(", "));

    cc.emitLn("%s = scf.while (%s) : (%s) -> (%s) {\n%s} do {\n^bb0(%s):\n%s}",
              resultsStr, beforeArgsStr, argTypesStr, argTypesStr,
              beforeCC.getStringBuilder().toString().indent(N_SPACES),
              afterArgsStr,
              afterCC.getStringBuilder().toString().indent(N_SPACES));

    Streams.forEachPair(loopCarriedVars.stream(), results.stream(),
                        cc::setValue);

    return this;
  }

  @Override
  public Object defaultCase(EObject obj) {
    cc.emitLn("// unhandled: %s", obj);
    return this;
  }
}

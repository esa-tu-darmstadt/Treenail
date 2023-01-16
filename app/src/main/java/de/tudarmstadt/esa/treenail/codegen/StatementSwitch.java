package de.tudarmstadt.esa.treenail.codegen;

import static de.tudarmstadt.esa.treenail.codegen.LongnailCodegen.N_SPACES;
import static de.tudarmstadt.esa.treenail.codegen.MLIRType.mapType;
import static java.lang.String.format;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.Streams;
import com.minres.coredsl.analysis.StorageClass;
import com.minres.coredsl.coreDsl.CompoundStatement;
import com.minres.coredsl.coreDsl.DeclarationStatement;
import com.minres.coredsl.coreDsl.ExpressionInitializer;
import com.minres.coredsl.coreDsl.ExpressionStatement;
import com.minres.coredsl.coreDsl.IfStatement;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.coreDsl.util.CoreDslSwitch;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.emf.ecore.EObject;

class StatementSwitch extends CoreDslSwitch<Object> {
  private final ConstructionContext cc;
  private final ExpressionSwitch exprSwitch;

  StatementSwitch(ConstructionContext cc) {
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
  public Object caseDeclarationStatement(DeclarationStatement declStmt) {
    var decl = declStmt.getDeclaration();
    assert decl.getQualifiers().isEmpty()
        : "NYI: Const/volatile for local variables";

    for (var dtor : decl.getDeclarators()) {
      var nfo = cc.getInfo(dtor);
      assert nfo.getStorage() == StorageClass.local;
      assert nfo.getType().isIntegerType() : "NYI: Local arrays";
      var init = dtor.getInitializer();
      if (init == null) {
        // TODO: better handling for undefined values
        cc.setValue(dtor, null);
        continue;
      }

      assert init instanceof ExpressionInitializer : "NYI: List initializers";
      var value = exprSwitch.doSwitch(((ExpressionInitializer)init).getValue());
      var castValue = cc.makeCast(value, mapType(nfo.getType()));
      cc.setValue(dtor, castValue);
    }

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
    var cast = cc.makeAnonymousValue(MLIRType.getType(1, false));
    cc.emitLn("%s = coredsl.cast %s : %s to i1", cast, cond, cond.type);

    var values = cc.getValues();
    var counter = cc.getCounter();
    var ctx = cc.getElaborationContext();

    var hasElse = ifStmt.getElseBody() != null;

    var thenCC = new ConstructionContext(
        new LinkedHashMap<NamedEntity, MLIRValue>(values),
        new AtomicInteger(counter), ctx, new StringBuilder());

    var elseCC = new ConstructionContext(
        new LinkedHashMap<NamedEntity, MLIRValue>(values),
        new AtomicInteger(counter), ctx, new StringBuilder());

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
  public Object defaultCase(EObject obj) {
    cc.emitLn("// unhandled: %s", obj);
    return this;
  }
}

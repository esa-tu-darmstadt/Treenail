package de.tudarmstadt.esa.treenail.codegen;

import static de.tudarmstadt.esa.treenail.codegen.LongnailCodegen.N_SPACES;
import static de.tudarmstadt.esa.treenail.codegen.MLIRType.mapType;
import static java.lang.String.format;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;

import com.google.common.collect.Streams;
import com.minres.coredsl.analysis.AnalysisContext;
import com.minres.coredsl.analysis.StorageClass;
import com.minres.coredsl.coreDsl.BreakStatement;
import com.minres.coredsl.coreDsl.CaseSection;
import com.minres.coredsl.coreDsl.CompoundStatement;
import com.minres.coredsl.coreDsl.Declaration;
import com.minres.coredsl.coreDsl.DeclarationStatement;
import com.minres.coredsl.coreDsl.DefaultSection;
import com.minres.coredsl.coreDsl.ExpressionInitializer;
import com.minres.coredsl.coreDsl.ExpressionStatement;
import com.minres.coredsl.coreDsl.ForLoop;
import com.minres.coredsl.coreDsl.FunctionDefinition;
import com.minres.coredsl.coreDsl.IfStatement;
import com.minres.coredsl.coreDsl.IntegerConstant;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.coreDsl.ReturnStatement;
import com.minres.coredsl.coreDsl.SpawnStatement;
import com.minres.coredsl.coreDsl.SwitchStatement;
import com.minres.coredsl.coreDsl.util.CoreDslSwitch;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
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
  public Object caseReturnStatement(ReturnStatement retStmt) {
    var expr = retStmt.getValue();
    if (expr == null) {
      cc.emitLn("return");
      cc.setTerminatorWasEmitted();
      return this;
    }

    EObject funcDef = retStmt.eContainer();
    while (funcDef != null && !(funcDef instanceof FunctionDefinition))
      funcDef = funcDef.eContainer();
    assert funcDef != null : "Return statement outside of function?";

    var sig = ac.getFunctionSignature((FunctionDefinition)funcDef);
    var retTy = mapType(sig.getReturnType());
    var retVal = cc.makeCast(exprSwitch.doSwitch(expr), retTy);
    cc.emitLn("return %s : %s", retVal, retTy);
    cc.setTerminatorWasEmitted();
    return this;
  }

  record ConditionalResults(String typesString, String returnValsString) {}
  // Resolves updated entities and emits the corresponding yield instructions
  // into the given ConstructionContexts
  private static ConditionalResults
  emitYieldsForConditionals(ConstructionContext cc,
                            List<ConstructionContext> condCCs) {
    var updatedEntities = new LinkedHashSet<NamedEntity>();
    // Collect all updated entities
    for (var xCC : condCCs) {
      // TODO: toSet might give nondeterministic set
      var currUpdated = xCC.getUpdatedEntities()
                            .stream()
                            .filter(cc::hasValue)
                            .collect(Collectors.toSet());
      updatedEntities.addAll(currUpdated);
    }
    var ac = cc.getAnalysisContext();
    var returnTypes = updatedEntities.stream()
                          .map(ac::getDeclaredType)
                          .map(MLIRType::mapType)
                          .toList();
    final String returnTypesStr =
        returnTypes.stream().map(Object::toString).collect(joining(", "));
    // Emit yield instructions
    for (var xCC : condCCs) {
      var values = xCC.getValues();
      var yieldValues = updatedEntities.stream().map(values::get).toList();
      var yieldValuesStr =
          yieldValues.stream().map(Object::toString).collect(joining(", "));
      xCC.emitLn("scf.yield %s : %s", yieldValuesStr, returnTypesStr);
    }
    var resultValues =
        returnTypes.stream().map(cc::makeAnonymousValue).toList();
    Streams.forEachPair(updatedEntities.stream(), resultValues.stream(),
                        cc::setValue);
    var returnValsStr =
        resultValues.stream().map(Object::toString).collect(joining(", "));
    return new ConditionalResults(returnTypesStr, returnValsStr);
  }

  @Override
  public Object caseBreakStatement(BreakStatement breakStmt) {
    // TODO: this only works if the break statement is the final statement of
    // the block
    return this;
  }

  @Override
  public Object caseSwitchStatement(SwitchStatement switchStmt) {
    var condVal = new ExpressionSwitch(cc).doSwitch(switchStmt.getCondition());
    var sections = switchStmt.getSections();
    var sectionCCs = new ArrayList<ConstructionContext>();

    var values = cc.getValues();
    var counter = cc.getCounter();
    // TODO: fallthrough and breaks that are not the last instruction
    // - scf.index_switch does not have fallthrough
    // - without using a different extension such as e.g. cf, we would have to
    // copy code when fallthrough happens
    // - breaks that are not the last instruction are complicated
    //   - would need to record local variable state at the point of the break
    //   and later format the yield into the string
    //   - Idea: emit %s from break and later format in the yield
    for (var section : sections) {
      assert section.getBody().getLast() instanceof BreakStatement
          : "NYI: Fallthrough in switch statement";
      var sectionCC = new ConstructionContext(new LinkedHashMap<>(values),
                                              new AtomicInteger(counter), ac,
                                              new StringBuilder());
      // Generate code for body
      for (var stmt : section.getBody()) {
        // TODO: first should always be a labelled statement, this should be
        // skipped
        // TODO: what to do with return value?
        new StatementSwitch(sectionCC).doSwitch(stmt);
      }
      sectionCCs.add(sectionCC);
    }
    var res = emitYieldsForConditionals(cc, sectionCCs);
    // TODO: index_switch only works for certain types (<= ui32 I think)
    cc.emitLn("%s = scf.index_switch %s : index -> (%s) {",
              res.returnValsString, condVal, res.typesString);
    assert sectionCCs.size() == sections.size();
    boolean gotDefaultCase = false;
    for (int i = 0; i < sectionCCs.size(); ++i) {
      var xCC = sectionCCs.get(i);
      var sectionContent = xCC.getStringBuilder().toString().indent(N_SPACES);
      var section = sections.get(i);
      String sectionCode;
      if (section instanceof CaseSection caseSection) {
        var condition = caseSection.getCondition();
        assert condition instanceof IntegerConstant
            : "NYI non integer constant switch statement values";
        sectionCode =
            format("case %s {\n%s}", ((IntegerConstant)condition).getValue(),
                   sectionContent);
      } else {
        assert section instanceof DefaultSection
            : "SwitchSection other than CaseSection and DefaultSection: " +
              section.getClass().getName();
        assert !gotDefaultCase : "Duplicate default case";
        sectionCode = format("default {\n%s}", sectionContent);
        gotDefaultCase = true;
      }
      cc.emitLn("%s", sectionCode.indent(N_SPACES).stripTrailing());
    }
    // TODO: index_switch always needs a default case
    assert gotDefaultCase : "NYI: switch statement without default case";
    cc.emitLn("}");
    return this;
  }

  @Override
  public Object caseIfStatement(IfStatement ifStmt) {
    var cond = exprSwitch.doSwitch(ifStmt.getCondition());
    var cast = cc.makeI1Cast(cond);

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

    // Check if the entities are present in `cc`. If not, they're local
    // variables that cannot be live outside the branch.
    var thenUpdated = thenCC.getUpdatedEntities()
                          .stream()
                          .filter(cc::hasValue)
                          .collect(toSet());
    var elseUpdated = elseCC.getUpdatedEntities()
                          .stream()
                          .filter(cc::hasValue)
                          .collect(toSet());

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

  private List<NamedEntity> getLoopCarriedVariables(ForLoop loop) {
    // Simulate construction to find loop-carried values, in lieu of proper
    // analysis.
    var simCC = new ConstructionContext(
        new LinkedHashMap<NamedEntity, MLIRValue>(cc.getValues()),
        new AtomicInteger(cc.getCounter()), ac, new StringBuilder());
    var simExprSwitch = new ExpressionSwitch(simCC);
    var simStmtSwitch = new StatementSwitch(simCC);

    var startDecl = loop.getStartDeclaration();
    var startExpr = loop.getStartExpression();
    if (startDecl != null)
      simStmtSwitch.doSwitch(startDecl);
    if (startExpr != null)
      simExprSwitch.doSwitch(startExpr);
    simExprSwitch.doSwitch(loop.getCondition());
    simStmtSwitch.doSwitch(loop.getBody());
    loop.getLoopExpressions().forEach(new ExpressionSwitch(simCC)::doSwitch);

    var res = new LinkedList<>(simCC.getUpdatedEntities());
    // Filter out variables declared inside the loop.
    res.removeIf(Predicate.not(cc::hasValue));
    return res;
  }

  private boolean emitScfFor(ForLoop loop) {
    // Check whether this loop can be represented as an `scf.for` operation. If
    // not, fail early; the construction will fall-back to a generic
    // `scf.while` (which may be unsupported by Longnail).
    var initAna = ForLoopAnalyzer.analyzeInitialization(loop);
    var condAna = ForLoopAnalyzer.analyzeCondition(loop, cc);
    var actionAna = ForLoopAnalyzer.analyzeAction(loop);
    if (initAna == null || condAna == null || actionAna == null ||
        initAna.variable != condAna.variable ||
        condAna.variable != actionAna.variable)
      return false;

    if (!ForLoopAnalyzer.FOR_COMPATIBLE_CMP.contains(condAna.relation))
      return false;

    boolean mustNegateItVar = false;
    switch (condAna.relation) {
    case "<":
      // Nothing to do, this is the intended way
      break;
    case "<=":
      // a <= b <-> a < b + 1
      condAna.bound = condAna.bound.add(BigInteger.ONE);
      break;

    case ">=":
      // a >= b <-> a > b - 1
      condAna.bound = condAna.bound.subtract(BigInteger.ONE);
      // Fallthrough to convert a > b to a < b
    case ">":
      mustNegateItVar = true;
      initAna.value = initAna.value.negate();
      actionAna.step = actionAna.step.negate();
      condAna.bound = condAna.bound.negate();
      break;
    default:
      assert false : "emitScfFor: NYI relation";
      break;
    }

    // scf.for demands that the step value is positive!
    if (actionAna.step.signum() < 0)
      return false;

    // The iterator is special in `scf.for`; separate it from the remaining
    // loop-carried variables.
    var iterVar = initAna.variable;
    var iterArgVars = getLoopCarriedVariables(loop);
    iterArgVars.remove(iterVar);

    var expectedIterType = mapType(ac.getDeclaredType(iterVar));

    // Find minimal common type for initAna.value, actionAna.step, condAna.bound
    var minTypeInit = MLIRType.determineType(initAna.value);
    var minTypeStep = MLIRType.determineType(actionAna.step);
    var minTypeBound = MLIRType.determineType(condAna.bound);

    var isActualSigned =
        minTypeInit.isSigned || minTypeStep.isSigned || minTypeBound.isSigned;
    var isUnsignedCmp = !isActualSigned;
    Function<MLIRType, Integer> getBitWidth =
        x -> x.width + (isActualSigned != x.isSigned ? 1 : 0);
    var minBitWidth = Math.max(getBitWidth.apply(minTypeInit),
                               Math.max(getBitWidth.apply(minTypeStep),
                                        getBitWidth.apply(minTypeBound))) +
                      1;
    var actualIterType = MLIRType.getType(minBitWidth, isActualSigned);

    // For now, only loops with constant bounds/trip counts are supported.
    var from = cc.makeHWConst(initAna.value, actualIterType.width);
    var to = cc.makeHWConst(condAna.bound, actualIterType.width);
    var step = cc.makeHWConst(actionAna.step, actualIterType.width);

    // This nested construction will be used for the loop body.
    var forCC = new ConstructionContext(
        new LinkedHashMap<NamedEntity, MLIRValue>(cc.getValues()),
        new AtomicInteger(cc.getCounter()), ac, new StringBuilder());

    // Make the iterator available as an ui/si value in the body.
    var iterIndex = forCC.makeAnonymousValue(MLIRType.DUMMY);
    var iterMlirVal = iterIndex;
    if (mustNegateItVar) {
      var zeroConst = forCC.makeHWConst(BigInteger.ZERO, actualIterType.width);
      var negatedIdx = forCC.makeAnonymousValue(MLIRType.DUMMY);
      forCC.emitLn("%s = comb.sub %s, %s : i%d", negatedIdx, zeroConst,
                   iterIndex, actualIterType.width);
      iterMlirVal = negatedIdx;
    }
    var iterRawVal = forCC.makeHWConstCast(
        iterMlirVal, actualIterType.width,
        MLIRType.getType(actualIterType.width, expectedIterType.isSigned));
    iterMlirVal = forCC.makeCast(iterRawVal, expectedIterType);
    forCC.setValue(iterVar, iterMlirVal);

    // Make other iterArgs available in the body, and create result values.
    var iterArgTypes = new LinkedList<MLIRType>();
    var iterArgs = new LinkedHashMap<NamedEntity, MLIRValue>();
    var results = new LinkedList<MLIRValue>();
    for (var v : iterArgVars) {
      var type = mapType(ac.getDeclaredType(v));
      iterArgTypes.add(type);

      forCC.setValue(v, forCC.makeAnonymousValue(type));
      iterArgs.put(v, forCC.getValue(v));

      results.add(cc.makeAnonymousValue(type));
    }

    // Recurse into loop body.
    new StatementSwitch(forCC).doSwitch(loop.getBody());

    // Handle simple case first: no `iter_args`/results.
    if (iterArgs.isEmpty()) {
      forCC.emitLn("scf.yield");
      cc.emitLn("scf.for %s%s = %s to %s step %s : i%d {\n%s}",
                isUnsignedCmp ? "unsigned " : "", iterIndex, from, to, step,
                actualIterType.width,
                forCC.getStringBuilder().toString().indent(N_SPACES));
      return true;
    }

    // General case: we have `iter_args` and results.
    var iterArgTypesStr =
        iterArgTypes.stream().map(Object::toString).collect(joining(", "));

    // Emit yield in the nested region.
    var yieldVals = iterArgVars.stream().map(forCC::getValue).toList();
    forCC.emitLn(
        "scf.yield %s : %s",
        yieldVals.stream().map(Object::toString).collect(joining(", ")),
        iterArgTypesStr);

    // Collect initial values for the `iter_args`.
    var iterArgsStr =
        iterArgVars.stream()
            .map(e -> format("%s = %s", iterArgs.get(e), cc.getValue(e)))
            .collect(joining(", "));
    var resultsStr =
        results.stream().map(Object::toString).collect(joining(", "));

    cc.emitLn("%s = scf.for %s%s = %s to %s step %s iter_args(%s) -> (%s) : "
                  + "i%d {\n%s}",
              resultsStr, isUnsignedCmp ? "unsigned " : "", iterIndex, from, to,
              step, iterArgsStr, iterArgTypesStr, actualIterType.width,
              forCC.getStringBuilder().toString().indent(N_SPACES));

    // Update the surrounding construction's value table.
    Streams.forEachPair(iterArgVars.stream(), results.stream(), cc::setValue);

    return true;
  }

  private void emitScfWhile(ForLoop loop) {
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

    // Find loop-carried values.
    var loopCarriedVars = getLoopCarriedVariables(loop);

    // Real construction begins here. See
    // https://mlir.llvm.org/docs/Dialects/SCFDialect/#scfwhile-mlirscfwhileop
    // for the use of "before" and "after" terms.
    var beforeCC = new ConstructionContext(
        new LinkedHashMap<NamedEntity, MLIRValue>(cc.getValues()),
        new AtomicInteger(cc.getCounter()), ac, new StringBuilder());

    var afterCC = new ConstructionContext(
        new LinkedHashMap<NamedEntity, MLIRValue>(cc.getValues()),
        new AtomicInteger(cc.getCounter()), ac, new StringBuilder());

    var argTypes = new LinkedList<MLIRType>();
    var beforeArgs = new LinkedHashMap<NamedEntity, MLIRValue>();
    var afterArgs = new LinkedHashMap<NamedEntity, MLIRValue>();
    var results = new LinkedList<MLIRValue>();
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
    var condCast = beforeCC.makeI1Cast(cond);
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
  }

  @Override
  public Object caseForLoop(ForLoop loop) {
    if (!emitScfFor(loop))
      emitScfWhile(loop);
    return this;
  }

  @Override
  public Object caseSpawnStatement(SpawnStatement spawn) {
    var spawnCC = new ConstructionContext(
        new LinkedHashMap<NamedEntity, MLIRValue>(cc.getValues()),
        new AtomicInteger(cc.getCounter()), ac, new StringBuilder());
    new StatementSwitch(spawnCC).doSwitch(spawn.getBody());
    spawnCC.emitLn("coredsl.end");
    cc.emitLn("coredsl.spawn {\n%s}",
              spawnCC.getStringBuilder().toString().indent(N_SPACES));
    cc.setTerminatorWasEmitted();
    return this;
  }

  @Override
  public Object defaultCase(EObject obj) {
    cc.emitLn("// unhandled: %s", obj);
    return this;
  }
}

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
  // For detecting break statements that are in locations other than the end of
  // a SwitchSection, as they are currently unsupported
  private BreakStatement switchEndBreak = null;

  StatementSwitch(ConstructionContext cc) {
    this.ac = cc.getAnalysisContext();
    this.cc = cc;
    exprSwitch = new ExpressionSwitch(cc);
  }

  StatementSwitch(ConstructionContext cc, BreakStatement breakStatement) {
    this(cc);
    this.switchEndBreak = breakStatement;
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
    assert switchEndBreak != null : "NYI: Break statement in loop";
    assert breakStmt == switchEndBreak
        : "NYI: Switch statements breaks in position other than the end";
    return this;
  }

  @Override
  public Object caseSwitchStatement(SwitchStatement switchStmt) {
    var condVal = new ExpressionSwitch(cc).doSwitch(switchStmt.getCondition());
    var sections = switchStmt.getSections();
    var sectionCCs = new ArrayList<ConstructionContext>();

    var values = cc.getValues();
    var counter = cc.getCounter();
    boolean gotDefaultCase = false;
    /*
    TODO: fallthrough and breaks in the middle of statements
    - scf.index_switch does not have fallthrough
    - would need cf dialect to implement fallthrough
    - Problem: breaks in arbitrary positions could still be difficult
      - using cf.br from inside scf.if is not allowed
        - if (cond) break would not work
        - would need to reimplement if using cf in that case
      - Otherwise, could emit %s for all breaks and then format the cf.br
      instructions in as a final step
        - Each break would have to record variable state when visited, so the
        right values can be used in the cf.br later
    Example code if using cf extension:
    CoreDSL:
    switch (a) {
      case 1:
      case 2:
        x = 10;
      case 3:
        y = 5;
        break;
      case 4:
        x = 5;
        z = 10;
        break;
      default:
        break;
    }
    MLIR:
    cf.switch %a : i32, [
      ; NOTE: to make the implementation simpler, we could have all bbs take
      ; all modified variables as inputs
      1: ^case_1(),
      2: ^case_2(),
      3: ^case_3(%x, i32),
      4: ^case_4(),
      default: ^default()
    ]
    ; NOTE: opening new ConstructionContexts (and thereby duplicating variable
    ; names) for each BB will work here, as MLIR BBs can only read variables
    ; implicitly from its dominators. As cases in switch statements cannot
    ; dominate other cases, we won't run into duplicate variable definition
    ; errors
    ; All blocks here are dominated by the block before the switch, so they
    ; can read all variables of that block
    ^case_1():
      cf.br ^case_2()
    ^case_2():
      %new_x = 10
      cf.br ^case_3(%new_x : i32)
    ^case_3(%c3_x : i32):
      %new_y = 5
      cf.br ^end_bb(%c3_x, %new_y)
    ^case_4():
      %new_x = 5
      %new_z = 10
      cf.br ^end_bb(%new_x, %y, %new_z)
    ^default():
      cf.br ^end_bb(%x, %y, %z)
    ^end_bb(%res_x, %res_y, %res_z):
      ; Now x = %res_x, y = %res_y, z = %res_z
     */
    for (var section : sections) {
      var lastStatement = section.getBody().getLast();
      assert lastStatement instanceof BreakStatement
          : "NYI: Fallthrough in switch statement";
      var endBreak = (BreakStatement)lastStatement;

      var sectionCC = new ConstructionContext(new LinkedHashMap<>(values),
                                              new AtomicInteger(counter), ac,
                                              new StringBuilder());
      if (section instanceof DefaultSection) {
        gotDefaultCase = true;
      }
      // Generate code for body
      for (var stmt : section.getBody()) {
        new StatementSwitch(sectionCC, endBreak).doSwitch(stmt);
      }
      sectionCCs.add(sectionCC);
    }
    if (!gotDefaultCase) {
      // If there is no default case, add an empty one, as index_switch always
      // needs a default case
      var defaultCC = new ConstructionContext(new LinkedHashMap<>(values),
                                              new AtomicInteger(counter), ac,
                                              new StringBuilder());
      sectionCCs.add(defaultCC);
    }
    var res = emitYieldsForConditionals(cc, sectionCCs);
    // TODO: index_switch only works for certain types (<= ui32 I think)
    // - might need to fall back to if statements if that is the case
    cc.emitLn("%s = scf.index_switch %s -> %s",
              res.returnValsString, condVal, res.typesString);
    assert sectionCCs.size() == sections.size() ||
        sectionCCs.size() == sections.size() + 1;
    for (int i = 0; i < sectionCCs.size(); ++i) {
      var xCC = sectionCCs.get(i);
      var sectionContent = xCC.getStringBuilder().toString().indent(N_SPACES);
      // If we added an artificial default case, we will be one past the end of
      // sections
      var section = i < sections.size() ? sections.get(i) : null;
      String sectionCode;
      if (section instanceof CaseSection caseSection) {
        var condition = caseSection.getCondition();
        // TODO: architecture parameters are allowed here as well
        assert condition instanceof IntegerConstant
            : "NYI non integer constant switch statement values";
        sectionCode =
            format("case %s {\n%s}", ((IntegerConstant)condition).getValue(),
                   sectionContent);
      } else {
        assert section == null || section instanceof DefaultSection
            : "SwitchSection other than CaseSection or DefaultSection: " +
              section.getClass().getName();
        sectionCode = format("default {\n%s}", sectionContent);
      }
      cc.emitLn("%s", sectionCode.indent(N_SPACES).stripTrailing());
    }
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

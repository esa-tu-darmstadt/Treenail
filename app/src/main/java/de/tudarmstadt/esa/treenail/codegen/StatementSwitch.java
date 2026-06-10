package de.tudarmstadt.esa.treenail.codegen;

import static de.tudarmstadt.esa.treenail.codegen.LongnailCodegen.N_SPACES;
import static de.tudarmstadt.esa.treenail.codegen.MLIRIntType.mapType;
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

  private record SwitchFinalBranchesRes(
      LinkedHashSet<NamedEntity> updatedEntities, List<MLIRIntType> returnTypes,
      String returnTypesString,
      // The values representing the updated entities after the switch is done
      List<MLIRValue> resultValues) {}

  // Resolves updated entities and emits the corresponding yield instructions
  // into the given ConstructionContexts
  // Returns the inputs to the final basic block as a string
  private static SwitchFinalBranchesRes
  emitSwitchFinalBranches(ConstructionContext cc,
                          List<ConstructionContext> condCCs,
                          String finalBBName) {
    assert !condCCs.isEmpty();
    var updatedEntities = new LinkedHashSet<NamedEntity>();
    // Collect all updated entities
    for (var xCC : condCCs) {
      var currUpdated =
          xCC.getUpdatedEntities()
              .stream()
              .filter(cc::hasValue)
              .collect(Collectors.toCollection(LinkedHashSet::new));
      updatedEntities.addAll(currUpdated);
    }
    var ac = cc.getAnalysisContext();
    var returnTypes = updatedEntities.stream()
                          .map(ac::getDeclaredType)
                          .map(MLIRIntType::mapType)
                          .toList();
    var returnTypesStr =
        returnTypes.stream().map(Object::toString).collect(joining(", "));
    // Emit branches to final BB
    for (var xCC : condCCs) {
      var values = xCC.getValues();
      var yieldValues = updatedEntities.stream().map(values::get).toList();
      var yieldValuesStr =
          yieldValues.stream().map(Object::toString).collect(joining(", "));
      xCC.emitLn("cf.br %s(%s : %s)", finalBBName, yieldValuesStr,
                 returnTypesStr);
    }
    // Input values to the final cc which will immediately be yielded
    var resultValues = returnTypes.stream()
                           .map(condCCs.getLast()::makeAnonymousValue)
                           .toList();
    return new SwitchFinalBranchesRes(updatedEntities, returnTypes,
                                      returnTypesStr, resultValues);
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
    /*
    TODO: fallthrough and breaks that are not at the end of case regions
    - Fallthrough will require the BBs to take the values that may be changed
      in a previous case block as an argument
    - Problem: breaks in arbitrary positions could still be difficult
      - using cf.br from inside scf.if is not allowed
        - 'if (cond) break;' would not work
        - would need to reimplement if statements using cf in that case
          - Either implement all ifs using cf, or only when inside of a switch
      - Each break needs to know all possible modified values
        -> can only emit the cf.br after emitting all BBs
        -> need to record the current updatedValues for each break
    Example code:
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
    cf.switch %a : ui32, [
      default: ^default
      1: ^case_1,
      2: ^case_2,
      3: ^case_3(%x, ui32),
      4: ^case_4
    ]
    ^case_1():
      cf.br ^case_2()
    ^case_2():
      %new_x = 10
      cf.br ^case_3(%new_x : ui32)
    ^case_3(%c3_x : i32):
      %new_y = 5
      cf.br ^end_bb(%c3_x, %new_y)
    ^case_4():
      %new_x = 5
      %new_z = 10
      cf.br ^end_bb(%new_x, %y, %new_z)
    ^default():
      cf.br ^end_bb(%x, %y, %z : ui32, ui32, ui32)
    ^end_bb(%res_x : ui32, %res_y : ui32, %res_z : ui32):
      scf.yield %res_x, %res_y, %res_z : ui32, ui32, ui32
     */
    final var condVal =
        new ExpressionSwitch(cc).doSwitch(switchStmt.getCondition());
    var sections = switchStmt.getSections();
    if (sections.isEmpty()) {
      // For an empty switch statement, there is nothing to do
      return this;
    }
    assert condVal.type instanceof MLIRIntType;
    var condValIntType = (MLIRIntType)condVal.type;
    // The case values need to fit into a signed n bit integer, so if we have
    // an unsigned value, the max value of that type may be a case value,
    // which is not representable as n bit signed integer
    final int condWidth = condValIntType.isSigned ? condValIntType.width
                                                  : condValIntType.width + 1;
    // cf.switch wants signless values
    final var condValSignless = cc.makeSignlessCast(condVal, condWidth);
    var sectionCCs = new ArrayList<ConstructionContext>();
    var sectionBBNames = new ArrayList<String>();
    var sectionValStrings = new ArrayList<String>();
    // We don't need unique names for the basic blocks because each switch
    // statement is within an scf.execute_region call and basic blocks are
    // only visible in the same region
    final var defaultBBName = "^default";
    boolean gotDefaultCase = false;
    for (var section : sections) {
      String bbName;
      String valueString;
      if (section instanceof CaseSection caseSection) {
        var val = ac.getExpressionValue(caseSection.getCondition());
        assert val.isValid() : "Case value not constant expression";
        valueString = val.toString();
        bbName = "^case_" + valueString;
      } else {
        assert section instanceof DefaultSection;
        valueString = null;
        gotDefaultCase = true;
        bbName = defaultBBName;
      }
      sectionBBNames.add(bbName);
      sectionValStrings.add(valueString);
    }
    final var finalBBName = "^switch_end";
    var lastCC = cc;
    // Use the value map from cc (NOT lastCC) throughout the loop, but the
    // counters from lastCC in order to not have conflicting SSA values
    // while still using the value assignments from before the switch
    // Otherwise, the value changes from one case would carry over to the next
    final var values = cc.getValues();
    for (var section : sections) {
      var lastStatement = section.getBody().getLast();
      assert lastStatement instanceof BreakStatement
          : "NYI: Fallthrough in switch statement";
      var endBreak = (BreakStatement)lastStatement;
      var valueCounter = lastCC.getValueCounter();
      var sectionCC = new ConstructionContext(new LinkedHashMap<>(values),
                                              new AtomicInteger(valueCounter),
                                              ac, new StringBuilder());
      for (var stmt : section.getBody()) {
        new StatementSwitch(sectionCC, endBreak).doSwitch(stmt);
      }
      sectionCCs.add(sectionCC);
      lastCC = sectionCC;
    }
    var finalBranchesRes = emitSwitchFinalBranches(cc, sectionCCs, finalBBName);
    var returnTypes = finalBranchesRes.returnTypes;
    var updatedEntities = finalBranchesRes.updatedEntities;
    String returnTypeString = finalBranchesRes.returnTypesString;
    var oldReturnValues = updatedEntities.stream().map(cc::getValue).toList();
    var returnValues =
        returnTypes.stream().map(cc::makeAnonymousValue).toList();
    Streams.forEachPair(updatedEntities.stream(), returnValues.stream(),
                        cc::setValue);
    String returnValuesString =
        returnValues.stream().map(Object::toString).collect(joining(", "));
    // We need to use scf.execute_region, as the child regions of scf
    // operations require the region to only consist of one block.
    // This is not required for top-level switch statements, but it's easier to
    // do this in a uniform way
    cc.emitLn("%s = scf.execute_region -> (%s) {", returnValuesString,
              returnTypeString);
    cc.emitLn("  cf.switch %s : i%d, [", condValSignless, condWidth);
    // The default case must be the first in the list
    if (gotDefaultCase) {
      cc.emit("    default: %s", defaultBBName);
    } else {
      String finalBBInputs =
          oldReturnValues.stream().map(Object::toString).collect(joining(", "));
      cc.emit("    default: %s(%s : %s)", finalBBName, finalBBInputs,
              returnTypeString);
    }
    assert sectionBBNames.size() == sectionValStrings.size();
    for (int i = 0; i < sectionValStrings.size(); ++i) {
      String valueString = sectionValStrings.get(i);
      // Skip default block because it always goes first
      if (valueString == null)
        continue;
      String bbName = sectionBBNames.get(i);
      cc.emit(",\n    %s: %s", valueString, bbName);
    }
    cc.emitLn("\n  ]");
    assert sectionCCs.size() == sections.size();
    assert (sectionCCs.size() == sectionBBNames.size());
    for (int i = 0; i < sectionCCs.size(); ++i) {
      var xCC = sectionCCs.get(i);
      var sectionContent =
          xCC.getStringBuilder().toString().indent(N_SPACES * 2);
      String bbName = sectionBBNames.get(i);
      cc.emitLn("  %s():\n%s", bbName, sectionContent.stripTrailing());
    }
    final var yieldValues = finalBranchesRes.resultValues;
    String typedYieldedValueString =
        yieldValues.stream()
            .map((MLIRValue val) -> val.toString() + ": " + val.type.toString())
            .collect(joining(", "));
    cc.emitLn("  %s(%s):", finalBBName, typedYieldedValueString);
    String yieldedValueString =
        yieldValues.stream().map(Object::toString).collect(joining(", "));
    cc.emitLn("    scf.yield %s : %s", yieldedValueString, returnTypeString);
    cc.emitLn("}");
    return this;
  }

  @Override
  public Object caseIfStatement(IfStatement ifStmt) {
    var cond = exprSwitch.doSwitch(ifStmt.getCondition());
    var cast = cc.makeI1Cast(cond);

    var hasElse = ifStmt.getElseBody() != null;

    var thenCC = cc.createDerivedCC();

    var elseCC = cc.createDerivedCC();

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
    var simCC = cc.createDerivedCC();
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

    // Temporary construction context, as any changes to runtime variables will
    // need to be written into a ConstructionContext, but can only be written
    // after we have confirmed that this can be represented by scf.for
    ConstructionContext tmpCC = cc.createDerivedCC();
    var initAna = ForLoopAnalyzer.analyzeInitialization(loop, tmpCC, ac);
    var condAna = ForLoopAnalyzer.analyzeCondition(loop, tmpCC, ac);
    var actionAna = ForLoopAnalyzer.analyzeAction(loop, tmpCC, ac);
    if (initAna == null || condAna == null || actionAna == null ||
        initAna.variable != condAna.variable ||
        condAna.variable != actionAna.variable)
      return false;

    if (!ForLoopAnalyzer.FOR_COMPATIBLE_CMP.contains(condAna.relation))
      return false;

    ForLoopAnalyzer.ConstOrRuntimeValue bound = condAna.bound;
    ForLoopAnalyzer.ConstOrRuntimeValue stepVal = actionAna.step;
    ForLoopAnalyzer.ConstOrRuntimeValue initValue = initAna.value;
    boolean mustNegateItVar = false;
    switch (condAna.relation) {
    case "<":
      // Nothing to do, this is the intended way
      break;
    case "<=":
      // a <= b <-> a < b + 1
      bound.addOne();
      break;

    case ">=":
      // a >= b <-> a > b - 1
      bound.subOne();
      // Fallthrough to convert a > b to a < b
    case ">":
      mustNegateItVar = true;
      initValue.negate();
      stepVal.negate();
      bound.negate();
      break;
    default:
      assert false : "emitScfFor: NYI relation";
      break;
    }

    // scf.for demands that the step value is positive!
    if (stepVal.mayBeNegative()) {
      // Nothing will be written, as all the operations on runtime variables
      // were written into tmpCC, not cc
      return false;
    }

    // The iterator is special in `scf.for`; separate it from the remaining
    // loop-carried variables.
    var iterVar = initAna.variable;
    var iterArgVars = getLoopCarriedVariables(loop);
    iterArgVars.remove(iterVar);

    var expectedIterType = mapType(ac.getDeclaredType(iterVar));

    // Find minimal common type for initAna.value, actionAna.step, condAna.bound
    var minTypeInit = initValue.getType();
    var minTypeStep = stepVal.getType();
    var minTypeBound = bound.getType();

    var isActualSigned =
        minTypeInit.isSigned || minTypeStep.isSigned || minTypeBound.isSigned;
    var isUnsignedCmp = !isActualSigned;
    Function<MLIRIntType, Integer> getBitWidth =
        x -> x.width + (isActualSigned != x.isSigned ? 1 : 0);
    var minBitWidth = Math.max(getBitWidth.apply(minTypeInit),
                               Math.max(getBitWidth.apply(minTypeStep),
                                        getBitWidth.apply(minTypeBound)));
    var actualIterType = MLIRIntType.getType(minBitWidth, isActualSigned);

    // For now, only loops with constant bounds/trip counts are supported.
    var from = initValue.getAsMLIRValue(actualIterType);
    var to = bound.getAsMLIRValue(actualIterType);
    var step = stepVal.getAsMLIRValue(actualIterType);

    // Append tmpCC, because getAsMLIRValue() writes into tmpCC
    cc.appendDerivedCC(tmpCC);

    // This nested construction will be used for the loop body.
    var forCC = cc.createDerivedCC();

    // Make the iterator available as an ui/si value in the body.
    var iterIndex = forCC.makeAnonymousValue(
        MLIRSignlessIntType.getType(actualIterType.width));
    var iterMlirVal = iterIndex;
    if (mustNegateItVar) {
      var zeroConst = forCC.makeHWConst(BigInteger.ZERO, actualIterType.width);
      var negatedIdx = forCC.makeAnonymousValue(iterIndex.type);
      forCC.emitLn("%s = comb.sub %s, %s : %s", negatedIdx, zeroConst,
                   iterIndex, iterIndex.type);
      iterMlirVal = negatedIdx;
    }
    var iterRawVal = forCC.makeHWConstCast(
        iterMlirVal, actualIterType.width,
        MLIRIntType.getType(actualIterType.width, expectedIterType.isSigned));
    iterMlirVal = forCC.makeCast(iterRawVal, expectedIterType);
    forCC.setValue(iterVar, iterMlirVal);

    // Make other iterArgs available in the body, and create result values.
    var iterArgTypes = new LinkedList<MLIRIntType>();
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
    var beforeCC = cc.createDerivedCC();
    var afterCC = cc.createDerivedCC();

    var argTypes = new LinkedList<MLIRIntType>();
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
    var spawnCC = cc.createDerivedCC();
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

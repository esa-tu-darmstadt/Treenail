package de.tudarmstadt.esa.treenail.codegen;

import static de.tudarmstadt.esa.treenail.codegen.ConstructionContext.ensureBigInteger;
import static de.tudarmstadt.esa.treenail.codegen.MLIRType.mapType;
import static java.lang.String.format;
import static java.util.stream.Collectors.joining;

import com.minres.coredsl.analysis.AnalysisContext;
import com.minres.coredsl.analysis.ConstantValue.StatusCode;
import com.minres.coredsl.analysis.CoreDslAnalyzer;
import com.minres.coredsl.coreDsl.AlwaysBlock;
import com.minres.coredsl.coreDsl.Attribute;
import com.minres.coredsl.coreDsl.Declaration;
import com.minres.coredsl.coreDsl.DeclarationStatement;
import com.minres.coredsl.coreDsl.Declarator;
import com.minres.coredsl.coreDsl.DescriptionContent;
import com.minres.coredsl.coreDsl.Encoding;
import com.minres.coredsl.coreDsl.EntityReference;
import com.minres.coredsl.coreDsl.ExpressionInitializer;
import com.minres.coredsl.coreDsl.FunctionDefinition;
import com.minres.coredsl.coreDsl.ISA;
import com.minres.coredsl.coreDsl.IndexAccessExpression;
import com.minres.coredsl.coreDsl.Instruction;
import com.minres.coredsl.coreDsl.ListInitializer;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.coreDsl.Statement;
import com.minres.coredsl.coreDsl.TypeQualifier;
import com.minres.coredsl.type.AddressSpaceType;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.xtext.validation.ValidationMessageAcceptor;

public class LongnailCodegen implements ValidationMessageAcceptor {
  public static final int N_SPACES = 2;

  public String emit(DescriptionContent content) {
    var defs = content.getDefinitions();
    assert defs.size() == 1 : ("NYI: Multiple instruction sets/core "
                               + "definitions");

    var isa = defs.get(0);
    var anaRes = CoreDslAnalyzer.analyze(content, this);

    return emitISA(isa, anaRes.results.get(isa));
  }

  private static boolean hasAttr(List<Attribute> attrs, String attrName) {
    for (var a : attrs)
      if (a.getAttributeName().equals(attrName))
        return true;

    return false;
  }

  private static boolean isEnabled(List<Attribute> attrs, AnalysisContext ctx) {
    for (var a : attrs) {
      if (a.getAttributeName().equals("enable")) {
        assert a.getParameters().size() == 1;
        var expr = a.getParameters().get(0);
        if (ctx.isExpressionValueSet(expr)) {
          var res = ctx.getExpressionValue(expr);
          assert res.isValid() : "Non-constant attribute enable expression";
          return res.getValue().compareTo(BigInteger.ZERO) > 0;
        } else {
          // TODO is there a warning print util?
          System.out.println("WARNING: could not evaluate instruction enable "
                             + "attribute expression. Assuming true!");
          // TODO CoreDslConstantExpressionEvaluator.evaluate(ctx, expr) can not
          // evaluate == expression, annoying
          return true;
        }
      }
    }

    return true;
  }

  private String emitISA(ISA isa, AnalysisContext ctx) {
    var sb = new StringBuilder();

    sb.append(format("module @%s {\n", isa.getName()));
    for (var stmt : isa.getArchStateBody()) {
      assert stmt instanceof DeclarationStatement
          : "NYI: Support for parameter assignments etc.";
      var declStmt = (DeclarationStatement)stmt;
      var elem = emitArchitecturalStateElement(declStmt.getDeclaration(), ctx);
      if (elem != null)
        sb.append(elem.indent(N_SPACES));
    }

    for (var func : isa.getFunctions())
      sb.append(emitFunction(func, ctx).indent(N_SPACES));

    for (var inst : isa.getInstructions()) {
      // emit only if not disabled via attributes
      if (isEnabled(inst.getAttributes(), ctx))
        sb.append(emitInstruction(inst, ctx).indent(N_SPACES));
    }

    for (var always : isa.getAlwaysBlocks()) {
      // emit only if not disabled via attributes
      if (isEnabled(always.getAttributes(), ctx))
        sb.append(emitAlwaysBlock(always, ctx).indent(N_SPACES));
    }

    sb.append("}\n");
    return sb.toString();
  }

  private String emitConstParam(Declarator dtor, AnalysisContext ctx) {
    var name = dtor.getName();
    var type = ctx.getDeclaredType(dtor);
    var init = dtor.getInitializer();

    assert type.isIntegerType() : "NYI: non integer type const parameters";
    assert init != null;
    assert init instanceof ExpressionInitializer;
    var exprInit = (ExpressionInitializer)init;
    var cv = ctx.getExpressionValue(exprInit.getValue());
    assert cv.getStatus() == StatusCode.success : "Non-constant initializer";
    var constType = mapType(type);
    // Instead of a hwarith.constant we will emit a local const register, which
    // will be optimized away but allows being accessed even in isolated from
    // above regions (esp. func.func)
    return emitRegister(dtor, /*isConst=*/true, ctx);
  }

  private String emitRegister(Declarator dtor, boolean isConst,
                              AnalysisContext ctx) {
    var name = dtor.getName();
    var type = ctx.getDeclaredType(dtor);
    var init = dtor.getInitializer();

    var protoStr = "local";
    var flagsStr = isConst ? " const" : "";
    var initStr = "";

    if (type.isIntegerType()) {
      var targetType = mapType(type);
      if (hasAttr(dtor.getAttributes(), "is_pc"))
        protoStr = "core_pc";
      if (init != null) {
        assert init instanceof ExpressionInitializer;
        var exprInit = (ExpressionInitializer)init;
        var cv = ctx.getExpressionValue(exprInit.getValue());
        assert cv.getStatus() == StatusCode.success
            : "Non-constant initializer";
        initStr = " = " + ensureBigInteger(cv.value, targetType);
      }
      return format("coredsl.register %s%s @%s%s : %s\n", protoStr, flagsStr,
                    name, initStr, targetType);
    }

    assert type.isAddressSpaceType();
    var asType = (AddressSpaceType)type;
    assert asType.elementType.isIntegerType()
        : "NYI: Multi-dimensional registers";

    var width = asType.elementType.getBitSize();
    // assuming register are generally small
    var numElements = asType.count.intValueExact();
    if (hasAttr(dtor.getAttributes(), "is_main_reg"))
      protoStr = "core_x";

    var elementType = mapType(asType.elementType);
    if (init != null) {
      assert init instanceof ListInitializer;
      var listInit = (ListInitializer)init;
      initStr = listInit.getInitializers()
                    .stream()
                    .map(i -> {
                      var ei = (ExpressionInitializer)i;
                      var cv = ctx.getExpressionValue(ei.getValue());
                      assert cv.getStatus() == StatusCode.success
                          : "Non-constant initializer";
                      return ensureBigInteger(cv.getValue(), elementType);
                    })
                    .map(Object::toString)
                    .collect(joining(", ", " = [", "]"));
    }
    return format("coredsl.register %s%s @%s[%d]%s : %s\n", protoStr, flagsStr,
                  name, numElements, initStr, elementType);
  }

  private String emitAddressSpace(Declarator dtor, AnalysisContext ctx) {
    assert dtor.getInitializer() == null
        : "Address spaces cannot have initizers";

    var name = dtor.getName();
    var type = ctx.getDeclaredType(dtor);

    assert type.isAddressSpaceType() : "NYI: Single-element address 'spaces'";
    var asType = (AddressSpaceType)type;
    assert asType.elementType.isIntegerType()
        : "NYI: Multi-dimensional address spaces";

    var width = asType.elementType.getBitSize();
    var numElements = asType.count;
    var proto = "";
    var addressWidth = -1;
    if (hasAttr(dtor.getAttributes(), "is_main_mem")) {
      proto = "core_mem";
      // Compute the addressWidth via clog2
      // numElements.bitLength() computes: ceil(log2(numElements < 0 ?
      // -numElements : numElements+1))
      // -> we need to subtract 1 first
      addressWidth = numElements.subtract(BigInteger.ONE).bitLength();
    } else if ("CSR".equals(name) && width == 32 &&
               numElements.equals(BigInteger.TWO.pow(12))) {
      // TODO: inspect attributes instead of pattern matching for CSR, however
      // there is currently no attribute defined in CoreDSL to mark CSR
      // registers
      proto = "core_csr";
      addressWidth = 12;
    }
    assert !proto.isEmpty() : "NYI: Custom address spaces";

    return format("coredsl.addrspace %s @%s : (ui%d) -> %s\n", proto, name,
                  addressWidth, mapType(asType.elementType));
  }

  private String emitAlias(Declarator dtor, AnalysisContext ctx) {
    var name = dtor.getName();
    var init = dtor.getInitializer();
    assert init != null && init instanceof ExpressionInitializer;
    var expr = ((ExpressionInitializer)init).getValue();

    if (expr instanceof EntityReference) {
      var refName = ((EntityReference)expr).getTarget().getName();
      return format("coredsl.alias @%s = @%s\n", name, refName);
    }

    assert expr instanceof IndexAccessExpression;
    var indexExpr = (IndexAccessExpression)expr;
    var target = indexExpr.getTarget();
    assert target instanceof EntityReference;
    var refName = ((EntityReference)target).getTarget().getName();

    var index =
        ensureBigInteger(
            ctx.getExpressionValue(indexExpr.getIndex()).getValue(), null)
            .toString();
    if (indexExpr.getEndIndex() != null)
      index +=
          ":" +
          ensureBigInteger(
              ctx.getExpressionValue(indexExpr.getEndIndex()).getValue(), null);

    return format("coredsl.alias @%s = @%s[%s]\n", name, refName, index);
  }

  private String emitArchitecturalStateElement(Declaration decl,
                                               AnalysisContext ctx) {
    assert decl.getDeclarators().size() == 1 : "NYI: Multiple declarators";

    var qual = decl.getQualifiers();
    var isConst = qual.contains(TypeQualifier.CONST);
    var isVolatile = qual.contains(TypeQualifier.VOLATILE);
    assert !isVolatile : "NYI: Volatile architectural state";

    var dtor = decl.getDeclarators().get(0);
    switch (ctx.getStorageClass(dtor)) {
    case param:
      if (isConst) {
        // Const parameters can be emitted since their value is already
        // elaborated
        return emitConstParam(dtor, ctx);
      }
      return null; // Ignore, we're only dealing with the elaborated values.
    case register:
      return emitRegister(dtor, isConst, ctx);
    case extern:
      assert !isConst : "NYI: `const` address spaces";
      return emitAddressSpace(dtor, ctx);
    case alias:
      return emitAlias(dtor, ctx);
    default:
      assert false : "NYI: Architectural state declaration: " + decl;
      return null;
    }
  }

  private String emitFunction(FunctionDefinition func, AnalysisContext ctx) {
    var sb = new StringBuilder();

    Map<NamedEntity, MLIRValue> values = new LinkedHashMap<>();
    Function<Declaration, String> emitParam = (d) -> {
      var dtor = d.getDeclarators().get(0);
      var type = mapType(ctx.getDeclaredType(dtor));
      var value = new MLIRValue(dtor.getName(), type);
      values.put(dtor, value);
      return format("%s : %s", value, type);
    };
    var parameters =
        func.getParameters().stream().map(emitParam).collect(joining(", "));
    var anaReturnType = ctx.getFunctionSignature(func).getReturnType();
    var returnType =
        anaReturnType.isVoid() ? "" : format(" -> %s", mapType(anaReturnType));
    var body = func.getBody();
    var isExternal = body == null;

    var funcSignature =
        format("func.func %s@%s(%s)%s", isExternal ? "private " : "",
               func.getName(), parameters, returnType);

    if (isExternal) {
      // We have a blackbox function here, only emit a function declaration
      sb.append(funcSignature + "\n");
      return sb.toString();
    }

    var behavior = emitBehavior(body, ctx, values);

    // TODO: ensure that the `return` operation is emitted even for empty
    // CoreDSL functions.
    sb.append(funcSignature + " {\n")
        .append(behavior.indent(N_SPACES))
        .append("}\n");

    return sb.toString();
  }

  private String emitInstruction(Instruction inst, AnalysisContext ctx) {
    var sb = new StringBuilder();

    Map<NamedEntity, MLIRValue> values = new LinkedHashMap<>();
    List<String> splitValueDefStmts = new LinkedList<>();
    var encoding = emitEncoding(inst.getEncoding(), values, splitValueDefStmts);
    var behavior = emitBehavior(inst.getBehavior(), ctx, values);

    sb.append(
        format("coredsl.instruction @%s(%s) {\n", inst.getName(), encoding));
    for (var s : splitValueDefStmts) {
      sb.append(s.indent(N_SPACES));
    }
    sb.append(behavior.indent(N_SPACES)).append("}\n");

    return sb.toString();
  }

  public String emitEncoding(Encoding encoding,
                             Map<NamedEntity, MLIRValue> values,
                             List<String> splitValueDefStmts) {
    var encodingFieldSwitch = new EncodingFieldSwitch(values);

    var enc = encoding.getFields()
                  .stream()
                  .map(encodingFieldSwitch::doSwitch)
                  .collect(joining(", "));

    encodingFieldSwitch.combineSplitValues(splitValueDefStmts);

    return enc;
  }

  public String emitAlwaysBlock(AlwaysBlock always, AnalysisContext ctx) {
    var sb = new StringBuilder();

    Map<NamedEntity, MLIRValue> values = new LinkedHashMap<>();
    var behavior = emitBehavior(always.getBehavior(), ctx, values);

    sb.append(format("coredsl.always @%s {\n", always.getName()))
        .append(behavior.indent(N_SPACES))
        .append("}\n");

    return sb.toString();
  }

  public String emitBehavior(Statement behavior, AnalysisContext ctx,
                             Map<NamedEntity, MLIRValue> values) {
    var sb = new StringBuilder();
    var cc = new ConstructionContext(values, new AtomicInteger(0), ctx, sb);
    new StatementSwitch(cc).doSwitch(behavior);
    if (!cc.getTerminatorWasEmitted())
      cc.emitLn("coredsl.end");
    return sb.toString();
  }

  @Override
  public void acceptError(String message, EObject object,
                          EStructuralFeature feature, int index, String code,
                          String... issueData) {
    System.err.println("[ERR] " + message + " " + object);
  }

  @Override
  public void acceptError(String message, EObject object, int offset,
                          int length, String code, String... issueData) {
    System.err.println("[ERR] " + message + " " + object);
  }

  @Override
  public void acceptInfo(String message, EObject object,
                         EStructuralFeature feature, int index, String code,
                         String... issueData) {
    // System.err.println("[INFO] " + message + " " + object);
  }

  @Override
  public void acceptInfo(String message, EObject object, int offset, int length,
                         String code, String... issueData) {
    // System.err.println("[INFO] " + message + " " + object);
  }

  @Override
  public void acceptWarning(String message, EObject object,
                            EStructuralFeature feature, int index, String code,
                            String... issueData) {
    System.err.println("[WARN] " + message + " " + object);
  }

  @Override
  public void acceptWarning(String message, EObject object, int offset,
                            int length, String code, String... issueData) {
    System.err.println("[WARN] " + message);
  }
}

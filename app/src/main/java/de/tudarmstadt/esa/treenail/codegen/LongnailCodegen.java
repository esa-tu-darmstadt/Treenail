package de.tudarmstadt.esa.treenail.codegen;

import static de.tudarmstadt.esa.treenail.codegen.ConstructionContext.ensureBigInteger;
import static de.tudarmstadt.esa.treenail.codegen.MLIRType.mapType;
import static java.lang.String.format;
import static java.util.stream.Collectors.joining;

import com.minres.coredsl.analysis.AnalysisContext;
import com.minres.coredsl.analysis.ConstantValue.StatusCode;
import com.minres.coredsl.analysis.CoreDslAnalyzer;
import com.minres.coredsl.analysis.CoreDslConstantExpressionEvaluator;
import com.minres.coredsl.coreDsl.AlwaysBlock;
import com.minres.coredsl.coreDsl.Attribute;
import com.minres.coredsl.coreDsl.Declaration;
import com.minres.coredsl.coreDsl.DeclarationStatement;
import com.minres.coredsl.coreDsl.Declarator;
import com.minres.coredsl.coreDsl.DescriptionContent;
import com.minres.coredsl.coreDsl.Encoding;
import com.minres.coredsl.coreDsl.EntityReference;
import com.minres.coredsl.coreDsl.Expression;
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
import com.minres.coredsl.type.ArrayType;
import com.minres.coredsl.type.CoreDslType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.validation.ValidationMessageAcceptor;

public class LongnailCodegen implements ValidationMessageAcceptor {
  public static final int N_SPACES = 2;

  public String emit(DescriptionContent content) {
    var defs = content.getDefinitions();
    var anaRes = CoreDslAnalyzer.analyze(content, this);

    String isaCode = "";
    for (var isa : defs) {
      isaCode += emitISA(isa, anaRes.results.get(isa));
    }

    return isaCode;
  }

  private static boolean hasAttr(List<Attribute> attrs, String attrName) {
    for (var a : attrs)
      if (a.getAttributeName().equals(attrName))
        return true;

    return false;
  }

  /**
   * Serializes CoreDSL {@code [[...]]} attributes into MLIR discardable
   * attribute-dict entries named {@code coredsl.attr.<name>} so they survive
   * into the emitted IR for downstream passes. {@code [[name]]} becomes a
   * unit attribute, {@code [[name=expr]]} / {@code [[name(a, b)]]} become
   * (arrays of) integer attributes when the parameters are compile-time
   * constants that fit i64, and fall back to the parameter's source text as
   * a string attribute otherwise. {@code enable} is fully consumed by the
   * frontend (it gates emission of the entity) and is not forwarded.
   * Duplicate names keep the first occurrence, so entity-level attributes
   * shadow ISA-level common attributes when callers pass the entity list
   * first.
   */
  @SafeVarargs
  private static List<String> coreDslAttrEntries(AnalysisContext ctx,
                                                 List<Attribute>... attrLists) {
    var entries = new ArrayList<String>();
    var seen = new HashSet<String>();
    for (var attrs : attrLists) {
      for (var a : attrs) {
        var name = a.getAttributeName();
        if (name.equals("enable") || !seen.add(name))
          continue;
        var params = a.getParameters();
        if (params.isEmpty()) {
          entries.add("coredsl.attr." + name);
          continue;
        }
        var vals = params.stream()
                       .map(p -> attrParamValue(ctx, p))
                       .collect(joining(", "));
        entries.add(format("coredsl.attr.%s = %s", name,
                           params.size() == 1 ? vals : "[" + vals + "]"));
      }
    }
    return entries;
  }

  private static String attrParamValue(AnalysisContext ctx, Expression expr) {
    var res = ctx.isExpressionValueSet(expr)
                  ? ctx.getExpressionValue(expr)
                  : CoreDslConstantExpressionEvaluator.evaluate(ctx, expr);
    // A bare integer literal parses as an i64 IntegerAttr — reject values
    // that would not fit.
    if (res.isValid() && res.getValue().bitLength() <= 63)
      return res.getValue().toString();
    var node = NodeModelUtils.getNode(expr);
    var text = node != null ? NodeModelUtils.getTokenText(node) : "";
    return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }

  /** Formats attr-dict entries as `<prefix>{e1, e2}`, or "" if empty. */
  private static String attrDictOrEmpty(String prefix, List<String> entries) {
    if (entries.isEmpty())
      return "";
    return prefix + "{" + String.join(", ", entries) + "}";
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

    sb.append(format("coredsl.isax \"%s\" {\n", isa.getName()));
    for (var stmt : isa.getArchStateBody()) {
      if (!(stmt instanceof DeclarationStatement)) {
        System.out.println(
            "NYI: Support for parameter assignments etc. Ignoring...");
        continue;
      }
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
        sb.append(
            emitInstruction(inst, ctx, isa.getCommonInstructionAttributes())
                .indent(N_SPACES));
    }

    for (var always : isa.getAlwaysBlocks()) {
      // emit only if not disabled via attributes
      if (isEnabled(always.getAttributes(), ctx))
        sb.append(
            emitAlwaysBlock(always, ctx, isa.getCommonAlwaysBlockAttributes())
                .indent(N_SPACES));
    }

    sb.append("}\n");
    return sb.toString();
  }

  private String emitConstParam(Declarator dtor, boolean isVolatile,
                                AnalysisContext ctx) {
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
    return emitRegister(dtor, /*isConst=*/true, isVolatile, ctx);
  }

  private String emitRegister(Declarator dtor, boolean isConst,
                              boolean isVolatile, AnalysisContext ctx) {
    var name = dtor.getName();
    var type = ctx.getDeclaredType(dtor);
    var init = dtor.getInitializer();

    var protoStr = "local";
    var constStr = isConst ? " const" : "";
    var volatileStr = isVolatile ? " volatile" : "";
    var initStr = "";
    // Declarator attributes travel as discardable attrs after the type.
    var attrStr =
        attrDictOrEmpty(" ", coreDslAttrEntries(ctx, dtor.getAttributes()));

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
      return format("coredsl.register %s%s%s @%s%s : %s%s\n", protoStr,
                    constStr, volatileStr, name, initStr, targetType, attrStr);
    }

    assert type.isArrayType();
    // Array type
    var arType = (ArrayType)type;
    var elementType = arType.elementType;
    var numElements = arType.count;
    var width = elementType.getBitSize();

    assert elementType.isIntegerType() : "NYI: Multi-dimensional registers";

    if (hasAttr(dtor.getAttributes(), "is_main_reg"))
      protoStr = "core_x";

    var mappedElementType = mapType(elementType);
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
                      return ensureBigInteger(cv.getValue(), mappedElementType);
                    })
                    .map(Object::toString)
                    .collect(joining(", ", " = [", "]"));
    }
    return format("coredsl.register %s%s%s @%s[%d]%s : %s%s\n", protoStr,
                  constStr, volatileStr, name, numElements, initStr,
                  mappedElementType, attrStr);
  }

  private String emitAddressSpace(Declarator dtor, boolean isConst,
                                  boolean isVolatile, AnalysisContext ctx) {
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

    final String constString = isConst ? " const" : "";
    final String volatileString = isVolatile ? " volatile" : "";
    final String attrStr =
        attrDictOrEmpty(" ", coreDslAttrEntries(ctx, dtor.getAttributes()));
    return format("coredsl.addrspace %s%s%s @%s : (ui%d) -> %s%s\n", proto,
                  constString, volatileString, name, addressWidth,
                  mapType(asType.elementType), attrStr);
  }

  private String emitAlias(Declarator dtor, boolean isConst, boolean isVolatile,
                           AnalysisContext ctx) {
    var name = dtor.getName();
    var init = dtor.getInitializer();
    assert init != null && init instanceof ExpressionInitializer;
    var expr = ((ExpressionInitializer)init).getValue();
    var attrStr =
        attrDictOrEmpty(" ", coreDslAttrEntries(ctx, dtor.getAttributes()));

    if (expr instanceof EntityReference) {
      var refName = ((EntityReference)expr).getTarget().getName();
      return format("coredsl.alias @%s = @%s%s\n", name, refName, attrStr);
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

    final String constString = isConst ? " const" : "";
    final String volatileString = isVolatile ? " volatile" : "";
    return format("coredsl.alias%s%s @%s = @%s[%s]%s\n", constString,
                  volatileString, name, refName, index, attrStr);
  }

  private String emitArchitecturalStateElement(Declaration decl,
                                               AnalysisContext ctx) {
    var qual = decl.getQualifiers();
    var isConst = qual.contains(TypeQualifier.CONST);
    var isVolatile = qual.contains(TypeQualifier.VOLATILE);

    var sb = new StringBuilder();
    for (var dtor : decl.getDeclarators()) {
      switch (ctx.getStorageClass(dtor)) {
      case param:
        if (isConst) {
          // Const parameters can be emitted since their value is already
          // elaborated
          sb.append(emitConstParam(dtor, isVolatile, ctx));
        }
        break; // Ignore, we're only dealing with the elaborated values.
      case register:
        sb.append(emitRegister(dtor, isConst, isVolatile, ctx));
        break;
      case extern:
        sb.append(emitAddressSpace(dtor, isConst, isVolatile, ctx));
        break;
      case alias:
        sb.append(emitAlias(dtor, isConst, isVolatile, ctx));
        break;
      default:
        assert false : "NYI: Architectural state declaration: " + decl;
        return null;
      }
    }
    if (sb.isEmpty()) {
      // Return null, because otherwise, an empty string with indents is
      // appended to the resulting MLIR
      return null;
    }
    return sb.toString();
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

    // Forward the function's CoreDSL attributes as discardable attributes
    // (func.func places them in a trailing `attributes {...}` clause).
    var attrStr = attrDictOrEmpty(" attributes ",
                                  coreDslAttrEntries(ctx, func.getAttributes()));

    if (isExternal) {
      // We have a blackbox function here, only emit a function declaration
      sb.append(funcSignature + attrStr + "\n");
      return sb.toString();
    }

    var behavior = emitBehavior(body, ctx, values, "return");

    sb.append(funcSignature + attrStr + " {\n")
        .append(behavior.indent(N_SPACES))
        .append("}\n");

    return sb.toString();
  }

  private String emitInstruction(Instruction inst, AnalysisContext ctx,
                                 List<Attribute> commonAttrs) {
    var sb = new StringBuilder();

    Map<NamedEntity, MLIRValue> values = new LinkedHashMap<>();
    List<String> splitValueDefStmts = new LinkedList<>();
    // Instruction attributes shadow the ISA-level common instruction
    // attributes; both land in the instruction's attribute dict next to
    // lil.enc_immediates.
    var attrEntries = coreDslAttrEntries(ctx, inst.getAttributes(), commonAttrs);
    var encoding =
        emitEncoding(inst.getEncoding(), values, splitValueDefStmts, attrEntries);
    var behavior = emitBehavior(inst.getBehavior(), ctx, values);

    sb.append(
        format("coredsl.instruction @%s%s {\n", inst.getName(), encoding));
    for (var s : splitValueDefStmts) {
      sb.append(s.indent(N_SPACES));
    }
    sb.append(behavior.indent(N_SPACES)).append("}\n");

    return sb.toString();
  }

  public String emitEncoding(Encoding encoding,
                             Map<NamedEntity, MLIRValue> values,
                             List<String> splitValueDefStmts,
                             List<String> extraAttrEntries) {
    var encodingFieldSwitch = new EncodingFieldSwitch(values);

    var enc = '(' +
              encoding.getFields()
                  .stream()
                  .map(encodingFieldSwitch::doSwitch)
                  .collect(joining(", ")) +
              ')';

    var attrEntries = new ArrayList<String>();
    attrEntries.add(encodingFieldSwitch.combineSplitValues(splitValueDefStmts));
    attrEntries.addAll(extraAttrEntries);

    return " {" + String.join(", ", attrEntries) + "} " + enc;
  }

  public String emitAlwaysBlock(AlwaysBlock always, AnalysisContext ctx,
                                List<Attribute> commonAttrs) {
    var sb = new StringBuilder();

    Map<NamedEntity, MLIRValue> values = new LinkedHashMap<>();
    var behavior = emitBehavior(always.getBehavior(), ctx, values);

    // coredsl.always prints its attribute dict after the body region.
    var attrStr = attrDictOrEmpty(
        " ", coreDslAttrEntries(ctx, always.getAttributes(), commonAttrs));

    sb.append(format("coredsl.always @%s {\n", always.getName()))
        .append(behavior.indent(N_SPACES))
        .append("}" + attrStr + "\n");

    return sb.toString();
  }

  public String emitBehavior(Statement behavior, AnalysisContext ctx,
                             Map<NamedEntity, MLIRValue> values) {
    return emitBehavior(behavior, ctx, values, "coredsl.end");
  }
  public String emitBehavior(Statement behavior, AnalysisContext ctx,
                             Map<NamedEntity, MLIRValue> values,
                             String fallbackTerminator) {
    var sb = new StringBuilder();
    var cc = new ConstructionContext(values, new AtomicInteger(0), ctx, sb);
    new StatementSwitch(cc).doSwitch(behavior);
    if (!cc.getTerminatorWasEmitted())
      cc.emitLn(fallbackTerminator);
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

package de.tudarmstadt.esa.treenail.codegen;

import static de.tudarmstadt.esa.treenail.codegen.ConstructionContext.ensureBigInteger;
import static de.tudarmstadt.esa.treenail.codegen.MLIRType.mapType;
import static java.lang.String.format;
import static java.util.stream.Collectors.joining;

import com.minres.coredsl.analysis.AnalysisContext;
import com.minres.coredsl.analysis.ConstantValue.StatusCode;
import com.minres.coredsl.analysis.CoreDslAnalyzer;
import com.minres.coredsl.coreDsl.AlwaysBlock;
import com.minres.coredsl.coreDsl.Declaration;
import com.minres.coredsl.coreDsl.DeclarationStatement;
import com.minres.coredsl.coreDsl.Declarator;
import com.minres.coredsl.coreDsl.DescriptionContent;
import com.minres.coredsl.coreDsl.Encoding;
import com.minres.coredsl.coreDsl.ExpressionInitializer;
import com.minres.coredsl.coreDsl.FunctionDefinition;
import com.minres.coredsl.coreDsl.ISA;
import com.minres.coredsl.coreDsl.Instruction;
import com.minres.coredsl.coreDsl.ListInitializer;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.coreDsl.Statement;
import com.minres.coredsl.coreDsl.TypeQualifier;
import com.minres.coredsl.type.AddressSpaceType;
import java.math.BigInteger;
import java.util.LinkedHashMap;
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
    assert defs.size() == 1 : "NYI: Multiple instruction sets/core definitions";

    var isa = defs.get(0);
    var anaRes = CoreDslAnalyzer.analyze(content, this);

    return emitISA(isa, anaRes.results.get(isa));
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

    for (var inst : isa.getInstructions())
      sb.append(emitInstruction(inst, ctx).indent(N_SPACES));

    for (var always : isa.getAlwaysBlocks())
      sb.append(emitAlwaysBlock(always, ctx).indent(N_SPACES));

    sb.append("}\n");
    return sb.toString();
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
      // TODO: inspect attributes instead
      if ("PC".equals(name) && type.getBitSize() == 32)
        protoStr = "core_pc";
      if (init != null) {
        assert init instanceof ExpressionInitializer;
        var exprInit = (ExpressionInitializer)init;
        var cv = ctx.getExpressionValue(exprInit.getValue());
        assert cv.getStatus() == StatusCode.success
            : "Non-constant initializer";
        initStr = " = " + ensureBigInteger(cv.value);
      }
      return format("coredsl.register %s%s @%s%s : %s\n", protoStr, flagsStr,
                    name, initStr, mapType(type));
    }

    assert type.isAddressSpaceType();
    var asType = (AddressSpaceType)type;
    assert asType.elementType.isIntegerType()
        : "NYI: Multi-dimensional registers";

    var width = asType.elementType.getBitSize();
    // assuming register are generally small
    var numElements = asType.count.intValueExact();
    // TODO: inspect attributes instead
    if ("X".equals(name) && width == 32 && numElements == 32)
      protoStr = "core_x";

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
                      return ensureBigInteger(cv.getValue());
                    })
                    .map(Object::toString)
                    .collect(joining(", ", " = [", "]"));
    }
    return format("coredsl.register %s%s @%s[%d]%s : %s\n", protoStr, flagsStr,
                  name, numElements, initStr, mapType(asType.elementType));
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
    // TODO: inspect attributes instead
    if ("MEM".equals(name) && width == 8 &&
        numElements.equals(BigInteger.TWO.pow(32))) {
      proto = "core_mem";
      addressWidth = 32;
    } else if ("CSR".equals(name) && width == 32 &&
               numElements.equals(BigInteger.TWO.pow(12))) {
      proto = "core_csr";
      addressWidth = 12;
    }
    assert !proto.isEmpty() : "NYI: Custom address spaces";

    return format("coredsl.addrspace %s @%s : (ui%d) -> %s\n", proto, name,
                  addressWidth, mapType(asType.elementType));
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
      return null; // Ignore, we're only dealing with the elaborated values.
    case register:
      return emitRegister(dtor, isConst, ctx);
    case extern:
      assert !isConst : "NYI: `const` address spaces";
      return emitAddressSpace(dtor, ctx);
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
    var behavior = emitBehavior(func.getBody(), ctx, values);

    var anaReturnType = ctx.getFunctionSignature(func).getReturnType();
    var returnType = anaReturnType.isVoid()
                         ? " "
                         : format(" -> %s ", mapType(anaReturnType));

    // TODO: ensure that the `return` operation is emitted even for empty
    // CoreDSL functions.
    sb.append(format("func.func @%s(%s)%s{\n", func.getName(), parameters,
                     returnType))
        .append(behavior.indent(N_SPACES))
        .append("}\n");

    return sb.toString();
  }

  private String emitInstruction(Instruction inst, AnalysisContext ctx) {
    var sb = new StringBuilder();

    Map<NamedEntity, MLIRValue> values = new LinkedHashMap<>();
    var encoding = emitEncoding(inst.getEncoding(), ctx, values);
    var behavior = emitBehavior(inst.getBehavior(), ctx, values);

    sb.append(
          format("coredsl.instruction @%s(%s) {\n", inst.getName(), encoding))
        .append(behavior.indent(N_SPACES))
        .append("coredsl.end\n".indent(N_SPACES))
        .append("}\n");

    return sb.toString();
  }

  public String emitEncoding(Encoding encoding, AnalysisContext ctx,
                             Map<NamedEntity, MLIRValue> values) {
    var encodingFieldSwitch = new EncodingFieldSwitch(ctx, values);

    return encoding.getFields()
        .stream()
        .map(encodingFieldSwitch::doSwitch)
        .collect(joining(", "));
  }

  public String emitAlwaysBlock(AlwaysBlock always, AnalysisContext ctx) {
    var sb = new StringBuilder();

    Map<NamedEntity, MLIRValue> values = new LinkedHashMap<>();
    var behavior = emitBehavior(always.getBehavior(), ctx, values);

    sb.append(format("coredsl.always @%s {\n", always.getName()))
        .append(behavior.indent(N_SPACES))
        .append("coredsl.end\n".indent(N_SPACES))
        .append("}\n");

    return sb.toString();
  }

  public String emitBehavior(Statement behavior, AnalysisContext ctx,
                             Map<NamedEntity, MLIRValue> values) {
    var sb = new StringBuilder();
    new StatementSwitch(
        new ConstructionContext(values, new AtomicInteger(0), ctx, sb))
        .doSwitch(behavior);
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

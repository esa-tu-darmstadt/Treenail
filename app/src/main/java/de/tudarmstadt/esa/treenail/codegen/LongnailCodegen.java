package de.tudarmstadt.esa.treenail.codegen;

import static de.tudarmstadt.esa.treenail.codegen.MLIRType.mapType;
import static java.lang.String.format;

import com.minres.coredsl.analysis.CoreDslAnalyzer;
import com.minres.coredsl.analysis.ElaborationContext;
import com.minres.coredsl.analysis.StorageClass;
import com.minres.coredsl.coreDsl.Declaration;
import com.minres.coredsl.coreDsl.DeclarationStatement;
import com.minres.coredsl.coreDsl.Declarator;
import com.minres.coredsl.coreDsl.DescriptionContent;
import com.minres.coredsl.coreDsl.Encoding;
import com.minres.coredsl.coreDsl.ISA;
import com.minres.coredsl.coreDsl.Instruction;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.coreDsl.Statement;
import com.minres.coredsl.coreDsl.StorageClassSpecifier;
import com.minres.coredsl.type.ArrayType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.xtext.validation.ValidationMessageAcceptor;

public class LongnailCodegen implements ValidationMessageAcceptor {
  public static final int N_SPACES = 2;

  public String emit(DescriptionContent content) {
    var defs = content.getDefinitions();
    assert defs.size() == 1 : "NYI: Multiple instruction sets/core definitions";

    var isa = defs.get(0);
    var anaCtx = CoreDslAnalyzer.analyze(content, this);

    return emitISA(isa, anaCtx.elaborationResults.get(isa));
  }

  private String emitISA(ISA isa, ElaborationContext ctx) {
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

    // TODO: Functions, Always blocks

    for (var inst : isa.getInstructions())
      sb.append(emitInstruction(inst, ctx).indent(N_SPACES));

    sb.append("}\n");
    return sb.toString();
  }

  private String emitRegister(Declarator dtor, ElaborationContext ctx) {
    assert dtor.getInitializer() == null : "NYI: Register initializers";

    var name = dtor.getName();
    var type = ctx.getNodeInfo(dtor).getType();

    if (type.isIntegerType()) {
      var proto = "local";
      // TODO: inspect attributes instead
      if ("PC".equals(name) && type.getBitSize() == 32)
        proto = "core_pc";

      return format("coredsl.register %s @%s : %s\n", proto, name,
                    mapType(type));
    }

    assert type.isArrayType();
    var arrayType = (ArrayType)type;
    assert arrayType.elementType.isIntegerType()
        : "NYI: Multi-dimensional registers";

    var width = arrayType.elementType.getBitSize();
    var numElements = arrayType.count;
    var proto = "local";
    // TODO: inspect attributes instead
    if ("X".equals(name) && width == 32 && numElements == 32)
      proto = "core_x";

    return format("coredsl.register %s @%s[%d] : %s\n", proto, name,
                  numElements, mapType(arrayType.elementType));
  }

  private String emitAddressSpace(Declarator dtor, ElaborationContext ctx) {
    assert dtor.getInitializer() == null
        : "Address spaces cannot have initizers";

    var name = dtor.getName();
    var type = ctx.getNodeInfo(dtor).getType();

    assert type.isArrayType() : "NYI: Single-element address 'spaces'";
    var arrayType = (ArrayType)type;
    assert arrayType.elementType.isIntegerType()
        : "NYI: Multi-dimensional address spaces";

    var width = arrayType.elementType.getBitSize();
    var numElements = arrayType.count;
    var proto = "";
    var addressWidth = -1;
    // TODO: inspect attributes instead, and deal with the actual size (which
    // may overflow a Java `int`).
    if ("MEM".equals(name) && width == 8) {
      proto = "core_mem";
      addressWidth = 32;
    } else if ("CSR".equals(name) && width == 32 && numElements == 4096) {
      proto = "core_csr";
      addressWidth = 12;
    }
    assert !proto.isEmpty() : "NYI: Custom address spaces";

    return format("coredsl.addrspace %s @%s : (ui%d) -> %s\n", proto, name,
                  addressWidth, mapType(arrayType.elementType));
  }

  private String emitArchitecturalStateElement(Declaration decl,
                                               ElaborationContext ctx) {
    assert decl.getQualifiers().isEmpty() : "NYI: Const/volatile";
    assert decl.getDeclarators().size() == 1 : "NYI: Multiple declarators";

    var dtor = decl.getDeclarators().get(0);
    switch (ctx.getNodeInfo(dtor).getStorage()) {
    case param:
      return null; // Ignore, we're only dealing with the elaborated values.
    case register:
      return emitRegister(dtor, ctx);
    case extern:
      return emitAddressSpace(dtor, ctx);
    default:
      assert false : "NYI: Architectural state declaration: " + decl;
      return null;
    }
  }

  private String emitInstruction(Instruction inst, ElaborationContext ctx) {
    var sb = new StringBuilder();

    Map<NamedEntity, MLIRValue> values = new LinkedHashMap<>();
    var encoding = emitEncoding(inst.getEncoding(), values);
    var behavior = emitBehavior(inst.getBehavior(), ctx, values);

    sb.append(
          format("coredsl.instruction @%s(%s) {\n", inst.getName(), encoding))
        .append(behavior.indent(N_SPACES))
        .append("}\n");

    return sb.toString();
  }

  public String emitEncoding(Encoding encoding,
                             Map<NamedEntity, MLIRValue> values) {
    var encodingFieldSwitch = new EncodingFieldSwitch(values);

    var fields = encoding.getFields()
                     .stream()
                     .map(encodingFieldSwitch::doSwitch)
                     .collect(Collectors.toList());
    return String.join(", ", fields);
  }

  public String emitBehavior(Statement behavior, ElaborationContext ctx,
                             Map<NamedEntity, MLIRValue> values) {
    var sb = new StringBuilder();
    new StatementSwitch(
        new ConstructionContext(values, new AtomicInteger(0), ctx, sb))
        .doSwitch(behavior);
    // TODO: `coredsl.spawn` might become an alternate terminator in the future.
    sb.append("coredsl.end").append('\n');
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

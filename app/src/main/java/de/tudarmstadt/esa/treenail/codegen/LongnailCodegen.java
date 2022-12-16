package de.tudarmstadt.esa.treenail.codegen;

import static java.lang.String.format;

import com.minres.coredsl.coreDsl.Declaration;
import com.minres.coredsl.coreDsl.DeclarationStatement;
import com.minres.coredsl.coreDsl.DescriptionContent;
import com.minres.coredsl.coreDsl.Encoding;
import com.minres.coredsl.coreDsl.ISA;
import com.minres.coredsl.coreDsl.Instruction;
import com.minres.coredsl.coreDsl.IntegerConstant;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.coreDsl.Statement;
import com.minres.coredsl.coreDsl.StorageClassSpecifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class LongnailCodegen {
  private static final int N_SPACES = 2;

  public String emit(DescriptionContent content) {
    var defs = content.getDefinitions();
    assert defs.size() == 1 : "NYI: Multiple instruction sets/core definitions";
    return emitISA(defs.get(0));
  }

  private String emitISA(ISA isa) {
    var sb = new StringBuilder();

    sb.append(format("module @%s {\n", isa.getName()));
    for (var stmt : isa.getArchStateBody()) {
      assert stmt instanceof DeclarationStatement
          : "NYI: Support for parameter assignments etc.";
      var declStmt = (DeclarationStatement)stmt;
      sb.append(emitArchitecturalStateElement(declStmt.getDeclaration())
                    .indent(N_SPACES));
    }

    // TODO: Functions, Always blocks

    for (var inst : isa.getInstructions())
      sb.append(emitInstruction(inst).indent(N_SPACES));

    sb.append("}\n");
    return sb.toString();
  }

  private String emitArchitecturalStateElement(Declaration decl) {
    assert decl.getQualifiers().isEmpty() : "NYI: Const/volatile";
    assert decl.getDeclarators().size() == 1 : "NYI: Multiple declarators";

    var type = new TypeSwitch().doSwitch(decl.getType());
    boolean isRegister =
        decl.getStorage().contains(StorageClassSpecifier.REGISTER);
    assert isRegister : "NYI: Architectural state other than registers";

    var dtor = decl.getDeclarators().get(0);
    assert dtor.getInitializer() == null : "NYI: Register initializers";

    var dims = dtor.getDimensions();
    if (dims.isEmpty())
      return format("coredsl.register @%s : %s\n", dtor.getName(), type);

    assert dims.size() == 1 : "NYI: Multi-dimensional registeres";
    var sizeExpr = dims.get(0);
    assert sizeExpr instanceof IntegerConstant : "NYI: Non-literal sizes";

    var name = dtor.getName();
    var size = ((IntegerConstant)sizeExpr).getValue().intValue();
    var proto = "";
    if ("X".equals(name) && type.width == 32 && size == 32)
      proto = "core_x";
    // TODO: more robust way to detect GPRs

    return format("coredsl.register %s @%s[%d] : %s\n", proto, name, size,
                  type);
  }

  private String emitInstruction(Instruction inst) {
    var sb = new StringBuilder();

    Map<NamedEntity, MLIRValue> values = new LinkedHashMap<>();
    var encoding = emitEncoding(inst.getEncoding(), values);
    var behavior = emitBehavior(inst.getBehavior(), values);

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

  public String emitBehavior(Statement behavior,
                             Map<NamedEntity, MLIRValue> values) {
    var sb = new StringBuilder();
    new StatementSwitch(values, sb).doSwitch(behavior);
    // TODO: `coredsl.spawn` might become an alternate terminator in the future.
    sb.append("coredsl.end").append('\n');
    return sb.toString();
  }
}

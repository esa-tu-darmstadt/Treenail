package de.tudarmstadt.esa.treenail.codegen;

import com.minres.coredsl.analysis.ElaborationContext;
import com.minres.coredsl.analysis.StorageClass;
import com.minres.coredsl.coreDsl.CompoundStatement;
import com.minres.coredsl.coreDsl.DeclarationStatement;
import com.minres.coredsl.coreDsl.ExpressionInitializer;
import com.minres.coredsl.coreDsl.ExpressionStatement;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.coreDsl.util.CoreDslSwitch;
import java.util.Map;
import org.eclipse.emf.ecore.EObject;

class StatementSwitch extends CoreDslSwitch<Object> {
  private final ElaborationContext ctx;
  private final Map<NamedEntity, MLIRValue> values;
  private final StringBuilder sb;

  private final ExpressionSwitch exprSwitch;

  StatementSwitch(ElaborationContext ctx, Map<NamedEntity, MLIRValue> values,
                  StringBuilder sb) {
    this.ctx = ctx;
    this.values = values;
    this.sb = sb;
    exprSwitch = new ExpressionSwitch(ctx, values, sb);
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
      var nfo = ctx.getNodeInfo(dtor);
      assert nfo.getStorage() == StorageClass.local;
      assert nfo.getType().isIntegerType() : "NYI: Local arrays";
      var init = dtor.getInitializer();
      if (init == null) {
        // TODO: better handling for undefined values
        values.put(dtor, null);
        continue;
      }

      assert init instanceof ExpressionInitializer : "NYI: List initializers";
      var value = exprSwitch.doSwitch(((ExpressionInitializer)init).getValue());
      values.put(dtor, value);
    }

    return this;
  }

  @Override
  public Object caseExpressionStatement(ExpressionStatement exprStmt) {
    exprSwitch.doSwitch(exprStmt.getExpression());
    return this;
  }

  @Override
  public Object defaultCase(EObject obj) {
    sb.append("// unhandled: ").append(obj).append('\n');
    return this;
  }
}

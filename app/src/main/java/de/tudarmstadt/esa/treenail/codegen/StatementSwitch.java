package de.tudarmstadt.esa.treenail.codegen;

import static de.tudarmstadt.esa.treenail.codegen.MLIRType.mapType;

import com.minres.coredsl.analysis.ElaborationContext;
import com.minres.coredsl.analysis.StorageClass;
import com.minres.coredsl.coreDsl.CompoundStatement;
import com.minres.coredsl.coreDsl.DeclarationStatement;
import com.minres.coredsl.coreDsl.ExpressionInitializer;
import com.minres.coredsl.coreDsl.ExpressionStatement;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.coreDsl.util.CoreDslSwitch;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.emf.ecore.EObject;

class StatementSwitch extends CoreDslSwitch<Object> {
  private final ConstructionContext cc;
  private final ExpressionSwitch exprSwitch;

  StatementSwitch(ElaborationContext ctx, Map<NamedEntity, MLIRValue> values,
                  StringBuilder sb) {
    cc = new ConstructionContext(values, new AtomicInteger(0), ctx, sb);
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
  public Object defaultCase(EObject obj) {
    cc.emitLn("// unhandled: %s", obj);
    return this;
  }
}

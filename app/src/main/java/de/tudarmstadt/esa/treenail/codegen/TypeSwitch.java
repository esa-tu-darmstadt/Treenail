package de.tudarmstadt.esa.treenail.codegen;

import com.minres.coredsl.coreDsl.BoolTypeSpecifier;
import com.minres.coredsl.coreDsl.IntegerConstant;
import com.minres.coredsl.coreDsl.IntegerSignedness;
import com.minres.coredsl.coreDsl.IntegerTypeSpecifier;
import com.minres.coredsl.coreDsl.util.CoreDslSwitch;
import org.eclipse.emf.ecore.EObject;

class TypeSwitch extends CoreDslSwitch<MLIRType> {
  @Override
  public MLIRType caseIntegerTypeSpecifier(IntegerTypeSpecifier intTySpec) {
    var sizeExpr = intTySpec.getSize();
    assert sizeExpr instanceof IntegerConstant : "NYI: Non-literal type sizes";
    int size = ((IntegerConstant)sizeExpr).getValue().intValue();
    return MLIRType.getType(size, intTySpec.getSignedness() ==
                                      IntegerSignedness.SIGNED);
  }

  @Override
  public MLIRType caseBoolTypeSpecifier(BoolTypeSpecifier boolTySpec) {
    return MLIRType.getType(1, false);
  }

  @Override
  public MLIRType defaultCase(EObject obj) {
    assert false : "Unhandled type: " + obj;
    return null;
  }
}

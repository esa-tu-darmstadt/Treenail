package de.tudarmstadt.esa.treenail.codegen;

import com.minres.coredsl.type.CoreDslType;

abstract class MLIRType {
  private static class VoidType extends MLIRType {}
  public static final MLIRType VOID = new VoidType();

  static MLIRType mapType(CoreDslType type) {
    if (type.isIntegerType()) {
      return MLIRIntType.mapType(type);
    } else if (type.isStructType()) {
      return MLIRStructType.mapType(type);
    }
    assert false : "NYI: Union, array, enum types";
    return null;
  }
}

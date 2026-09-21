package de.tudarmstadt.esa.treenail.codegen;

import com.minres.coredsl.type.CoreDslType;

abstract class MLIRType {
  private static class VoidType extends MLIRType {}
  public static final MLIRType VOID = new VoidType();
}

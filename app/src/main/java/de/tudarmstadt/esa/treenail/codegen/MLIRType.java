package de.tudarmstadt.esa.treenail.codegen;

abstract class MLIRType {
    private static class VoidType extends MLIRType {}
    public static final MLIRType VOID = new VoidType();
}

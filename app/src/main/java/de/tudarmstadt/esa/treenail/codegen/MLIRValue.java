package de.tudarmstadt.esa.treenail.codegen;

class MLIRValue {
  String name;
  MLIRType type;

  MLIRValue(String name, MLIRType type) {
    this.name = name;
    this.type = type;
  }

  public String toString() { return "%" + name; }
}

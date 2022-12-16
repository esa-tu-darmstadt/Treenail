package de.tudarmstadt.esa.treenail.codegen;

import java.util.LinkedHashMap;
import java.util.Map;

class MLIRType {
  int width;
  boolean isSigned;

  private static Map<Integer, MLIRType> unsignedTypes = new LinkedHashMap<>(),
                                        signedTypes = new LinkedHashMap<>();

  private MLIRType(int width, boolean isSigned) {
    assert width > 0;
    this.width = width;
    this.isSigned = isSigned;
  }

  static MLIRType getType(int width, boolean isSigned) {
    var map = isSigned ? signedTypes : unsignedTypes;
    if (!map.containsKey(width)) {
      map.put(width, new MLIRType(width, isSigned));
    }
    return map.get(width);
  }

  public String toString() { return (isSigned ? "si" : "ui") + width; }
}

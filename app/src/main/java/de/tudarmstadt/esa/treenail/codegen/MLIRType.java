package de.tudarmstadt.esa.treenail.codegen;

import com.minres.coredsl.type.CoreDslType;
import com.minres.coredsl.type.IntegerType;
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

  static MLIRType mapType(CoreDslType type) {
    assert type.isIntegerType();
    var intType = (IntegerType)type;
    return getType(intType.getBitSize(), intType.isSigned());
  }

  public String toString() { return (isSigned ? "si" : "ui") + width; }
}

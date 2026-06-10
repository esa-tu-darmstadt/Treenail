package de.tudarmstadt.esa.treenail.codegen;

import com.minres.coredsl.type.CoreDslType;
import com.minres.coredsl.type.IntegerType;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

class MLIRIntType extends MLIRType {
  int width;
  boolean isSigned;

  private static Map<Integer, MLIRIntType> unsignedTypes = new LinkedHashMap<>(),
                                        signedTypes = new LinkedHashMap<>();

  private MLIRIntType(int width, boolean isSigned) {
    assert width > 0;
    this.width = width;
    this.isSigned = isSigned;
  }

  static MLIRIntType getType(int width, boolean isSigned) {
    var map = isSigned ? signedTypes : unsignedTypes;
    if (!map.containsKey(width)) {
      map.put(width, new MLIRIntType(width, isSigned));
    }
    return map.get(width);
  }

  static MLIRIntType mapType(CoreDslType type) {
    assert type.isIntegerType();
    var intType = (IntegerType)type;
    return getType(intType.getBitSize(), intType.isSigned());
  }

  static MLIRIntType determineType(BigInteger value) {
    // Determine signedness
    boolean isNegative = value.signum() < 0;

    // Determine minimal width (bits)
    // extra bit for sign if negative
    int bits = Math.max(value.bitLength() + (isNegative ? 1 : 0), 1);
    return getType(bits, isNegative);
  }

  private static int getAddSubResultWidth(MLIRIntType lhsTy, MLIRIntType rhsTy) {
    if (lhsTy.isSigned == rhsTy.isSigned)
      return Math.max(lhsTy.width, rhsTy.width) + 1;

    // Extra bit necessary if the respective operand is _unsigned_.
    int lhsExtraBit = lhsTy.isSigned ? 0 : 1;
    int rhsExtraBit = rhsTy.isSigned ? 0 : 1;
    return Math.max(lhsTy.width + lhsExtraBit, rhsTy.width + rhsExtraBit) + 1;
  }

  public static MLIRIntType getAddResultType(MLIRIntType lhsTy, MLIRIntType rhsTy) {
    return getType(getAddSubResultWidth(lhsTy, rhsTy),
                   lhsTy.isSigned | rhsTy.isSigned);
  }

  public static MLIRIntType getSubResultType(MLIRIntType lhsTy, MLIRIntType rhsTy) {
    return getType(getAddSubResultWidth(lhsTy, rhsTy), true);
  }

  public String toString() { return (isSigned ? "si" : "ui") + width; }
}

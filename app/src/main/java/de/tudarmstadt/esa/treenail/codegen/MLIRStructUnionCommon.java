package de.tudarmstadt.esa.treenail.codegen;

import java.util.LinkedHashMap;

abstract class MLIRStructUnionCommon extends MLIRType {
  static String
  createStructUnionTypeString(String type,
                              LinkedHashMap<String, MLIRType> members) {
    StringBuilder sb = new StringBuilder("!hw.");
    sb.append(type);
    sb.append('<');
    final int lastIdx = members.size() - 1;
    int currIdx = 0;
    for (var item : members.entrySet()) {
      String key = item.getKey();
      MLIRType val = item.getValue();
      sb.append(key);
      sb.append(": ");
      sb.append(val);
      if (currIdx != lastIdx) {
        sb.append(", ");
      }
      ++currIdx;
    }
    sb.append(">");
    return sb.toString();
  }

  abstract MLIRType getMemberType(String name);
}

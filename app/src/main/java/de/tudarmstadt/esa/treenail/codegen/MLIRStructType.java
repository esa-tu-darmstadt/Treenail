package de.tudarmstadt.esa.treenail.codegen;

import java.util.LinkedHashMap;

class MLIRStructType extends MLIRType {
  private LinkedHashMap<String, MLIRType> members;
  private MLIRStructType(LinkedHashMap<String, MLIRType> members) {
    this.members = members;
  }

  private static LinkedHashMap<String, MLIRStructType> types =
      new LinkedHashMap<>();
  public static MLIRStructType getType(String typeName) {
    assert types.containsKey(typeName) : "Unknown struct type " + typeName;
    return types.get(typeName);
  }
  public static void
  registerStructType(String name, LinkedHashMap<String, MLIRType> members) {
    assert !types.containsKey(name) : "Duplicate struct name";
    types.put(name, new MLIRStructType(members));
  }
  public String toString() {
    StringBuilder sb = new StringBuilder("!hw.struct<");
    final int lastIdx = members.size() - 1;
    int currIdx = 0;
    for (var item : members.entrySet()) {
      String key = item.getKey();
      MLIRType val = item.getValue();
      sb.append(val);
      sb.append(": ");
      sb.append(key);
      if (currIdx != lastIdx) {
        sb.append(", ");
      }
      ++currIdx;
    }
    sb.append(">");
    return sb.toString();
  }
}

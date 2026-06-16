package de.tudarmstadt.esa.treenail.codegen;

import com.minres.coredsl.type.CoreDslType;

import java.util.LinkedHashMap;

class MLIRStructType extends MLIRType {
  private final LinkedHashMap<String, MLIRType> members;
  private MLIRStructType(LinkedHashMap<String, MLIRType> members) {
    this.members = members;
  }

  private static final LinkedHashMap<String, MLIRStructType> types =
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

  public static MLIRStructType mapType(CoreDslType type) {
    assert type.isStructType();
    // toString() returns "struct <name>", but we only need the name
    String structName = type.toString().substring(7);
    return MLIRStructType.getType(structName);
  }

  public MLIRType getMemberType(String memberName) {
    assert members.containsKey(memberName);
    return members.get(memberName);
  }

  // TODO: this allows the user to modify members
  public LinkedHashMap<String, MLIRType> getMembers() { return members; }
  // TODO: it may make sense to compute this once and store it
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

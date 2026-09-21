package de.tudarmstadt.esa.treenail.codegen;

import com.minres.coredsl.type.CoreDslType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

class MLIRStructType extends MLIRType {
  private final LinkedHashMap<String, MLIRType> members;
  private final String mlirTypeString;

  private static String
  createStructTypeString(LinkedHashMap<String, MLIRType> members) {
    StringBuilder sb = new StringBuilder("!hw.struct<");
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

  MLIRStructType(LinkedHashMap<String, MLIRType> members) {
    this.members = members;
    this.mlirTypeString = createStructTypeString(members);
  }

  public MLIRType getMemberType(String memberName) {
    assert members.containsKey(memberName);
    return members.get(memberName);
  }

  public Map<String, MLIRType> getMembers() {
    return Collections.unmodifiableMap(members);
  }

  public String toString() { return mlirTypeString; }
}

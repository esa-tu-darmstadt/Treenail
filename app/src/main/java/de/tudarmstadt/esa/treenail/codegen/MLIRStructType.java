package de.tudarmstadt.esa.treenail.codegen;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

class MLIRStructType extends MLIRStructUnionCommon {
  private final LinkedHashMap<String, MLIRType> members;
  private final String mlirTypeString;

  MLIRStructType(LinkedHashMap<String, MLIRType> members) {
    this.members = members;
    this.mlirTypeString = MLIRStructUnionCommon.createStructUnionTypeString("struct", members);
  }

  @Override
  int getBitWidth() {
    int sum = 0;
    for (var member : members.values()) {
      sum += member.getBitWidth();
    }
    return sum;
  }

  @Override
  public MLIRType getMemberType(String memberName) {
    assert members.containsKey(memberName);
    return members.get(memberName);
  }

  public Map<String, MLIRType> getMembers() {
    return Collections.unmodifiableMap(members);
  }

  public String toString() { return mlirTypeString; }
}

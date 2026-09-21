package de.tudarmstadt.esa.treenail.codegen;

import com.minres.coredsl.type.CoreDslType;
import java.util.LinkedHashMap;

class ISATypes {
  private final LinkedHashMap<String, MLIRStructType> nameToStructType =
      new LinkedHashMap<>();

  private MLIRStructType getStructType(String name) {
    assert nameToStructType.containsKey(name)
        : "Referencing unknown struct type";
    return nameToStructType.get(name);
  }

  void registerStructType(String name,
                          LinkedHashMap<String, MLIRType> members) {
    assert !nameToStructType.containsKey(name)
        : ("Redefinition of struct "
           + "type");
    nameToStructType.put(name, new MLIRStructType(members));
  }
  MLIRStructType mapStructType(CoreDslType type) {
    assert type.isStructType();
    // toString() returns "struct <name>", but we only need the name
    String structName = type.toString().substring(7);
    return getStructType(structName);
  }
  MLIRType mapType(CoreDslType type) {
    if (type.isIntegerType()) {
      return MLIRIntType.mapType(type);
    } else if (type.isStructType()) {
      return mapStructType(type);
    }
    assert false : "NYI: Union, array, enum types";
    return null;
  }
}

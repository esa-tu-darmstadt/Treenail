package de.tudarmstadt.esa.treenail.codegen;

import com.minres.coredsl.type.CoreDslType;
import java.util.LinkedHashMap;

class ISATypes {
  private final LinkedHashMap<String, MLIRStructType> nameToStructType =
      new LinkedHashMap<>();
  private final LinkedHashMap<String, MLIRUnionType> nameToUnionType = new LinkedHashMap<>();

  private MLIRStructType getStructType(String name) {
    assert nameToStructType.containsKey(name)
        : "Referencing unknown struct type";
    return nameToStructType.get(name);
  }

  private MLIRUnionType getUnionType(String name) {
    assert nameToUnionType.containsKey(name)
            : "Referencing unknown union type";
    return nameToUnionType.get(name);
  }

  void registerStructType(String name,
                          LinkedHashMap<String, MLIRType> members) {
    assert !nameToStructType.containsKey(name)
        : ("Redefinition of struct "
           + "type");
    nameToStructType.put(name, new MLIRStructType(members));
  }
  void registerUnionType(String name, LinkedHashMap<String, MLIRType> members) {
    assert !nameToUnionType.containsKey(name) : "Redefinition of union type";
    nameToUnionType.put(name, new MLIRUnionType(members));
  }

  MLIRStructUnionCommon mapStructOrUnionType(CoreDslType type) {
    assert type.isUnionType() || type.isStructType();
    if (type.isStructType()) {
      return mapStructType(type);
    } else {
      return mapUnionType(type);
    }
  }

  MLIRStructType mapStructType(CoreDslType type) {
    assert type.isStructType();
    // toString() returns "struct <name>", but we only need the name
    String structName = type.toString().substring(7);
    return getStructType(structName);
  }
  MLIRUnionType mapUnionType(CoreDslType type) {
    assert type.isUnionType();
    String unionName = type.toString().substring(6);
    return getUnionType(unionName);
  }
  MLIRType mapType(CoreDslType type) {
    if (type.isIntegerType()) {
      return MLIRIntType.mapType(type);
    } else if (type.isStructType()) {
      return mapStructType(type);
    } else if (type.isUnionType()) {
      return mapUnionType(type);
    }
    assert false : "NYI: Union, array, enum types";
    return null;
  }
}

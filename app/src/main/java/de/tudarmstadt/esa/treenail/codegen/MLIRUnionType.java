package de.tudarmstadt.esa.treenail.codegen;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

class MLIRUnionType extends MLIRStructUnionCommon {
    private final LinkedHashMap<String, MLIRType> members;
    private final String mlirTypeString;

    MLIRUnionType(LinkedHashMap<String, MLIRType> members) {
        this.members = members;
        this.mlirTypeString = MLIRStructUnionCommon.createStructUnionTypeString("union", members);
    }

    @Override
    int getBitWidth() {
        int max = 0;
        for (var member : members.values()) {
            if (member.getBitWidth() > max) {
                max = member.getBitWidth();
            }
        }
        return max;
    }

    @Override
    public MLIRType getMemberType(String memberName) {
        assert members.containsKey(memberName);
        return members.get(memberName);
    }

    public Map<String, MLIRType> getMembers() {
        return Collections.unmodifiableMap(members);
    }

    public String toString() {
        return mlirTypeString;
    }
}

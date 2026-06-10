package de.tudarmstadt.esa.treenail.codegen;

import java.util.LinkedHashMap;

class MLIRSignlessIntType extends MLIRType {
    int width;

    private static final LinkedHashMap<Integer, MLIRSignlessIntType> types = new LinkedHashMap<>();

    private MLIRSignlessIntType(int width) {
        assert width > 0;
        this.width = width;
    }

    public static MLIRSignlessIntType getType(int width) {
        if (!types.containsKey(width)) {
            var res = new MLIRSignlessIntType(width);
            types.put(width, res);
        }
        return types.get(width);
    }

    public String toString() {
        return "i" + width;
    }
}

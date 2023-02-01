package de.tudarmstadt.esa.treenail.codegen;

import static de.tudarmstadt.esa.treenail.codegen.MLIRType.mapType;

import com.minres.coredsl.analysis.AnalysisContext;
import com.minres.coredsl.coreDsl.BitField;
import com.minres.coredsl.coreDsl.BitValue;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.coreDsl.util.CoreDslSwitch;
import com.minres.coredsl.util.TypedBigInteger;
import java.util.Map;

class EncodingFieldSwitch extends CoreDslSwitch<String> {
  private final AnalysisContext ac;
  private final Map<NamedEntity, MLIRValue> values;

  EncodingFieldSwitch(AnalysisContext ac, Map<NamedEntity, MLIRValue> values) {
    this.ac = ac;
    this.values = values;
  }

  private String toBitString(TypedBigInteger bigInt) {
    int n = bigInt.getSize();
    char[] bits = new char[n];
    for (var i = 0; i < n; ++i)
      bits[i] = bigInt.testBit(n - 1 - i) ? '1' : '0';
    return '"' + String.valueOf(bits) + '"';
  }

  @Override
  public String caseBitValue(BitValue val) {
    var bigInt = val.getValue();
    assert bigInt instanceof TypedBigInteger;
    return toBitString((TypedBigInteger)bigInt);
  }

  @Override
  public String caseBitField(BitField field) {
    assert field.getEndIndex().getValue().signum() == 0
        : "NYI: Shifted encoding fields";

    var type = mapType(ac.getDeclaredType(field));
    var value = new MLIRValue(field.getName(), type);
    values.put(field, value);

    return value + " : " + type;
  }
}

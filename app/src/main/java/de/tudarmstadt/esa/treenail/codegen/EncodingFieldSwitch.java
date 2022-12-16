package de.tudarmstadt.esa.treenail.codegen;

import com.minres.coredsl.coreDsl.BitField;
import com.minres.coredsl.coreDsl.BitValue;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.coreDsl.util.CoreDslSwitch;
import com.minres.coredsl.util.BigIntegerWithRadix;
import java.util.Map;

class EncodingFieldSwitch extends CoreDslSwitch<String> {
  private final Map<NamedEntity, MLIRValue> values;

  EncodingFieldSwitch(Map<NamedEntity, MLIRValue> values) {
    this.values = values;
  }

  private String toBitString(BigIntegerWithRadix bigInt) {
    int n = bigInt.getSize();
    char[] bits = new char[n];
    for (var i = 0; i < n; ++i)
      bits[i] = bigInt.testBit(n - 1 - i) ? '1' : '0';
    return '"' + String.valueOf(bits) + '"';
  }

  @Override
  public String caseBitValue(BitValue val) {
    var bigInt = val.getValue();
    assert bigInt instanceof BigIntegerWithRadix;
    return toBitString((BigIntegerWithRadix)bigInt);
  }

  @Override
  public String caseBitField(BitField field) {
    int from = field.getStartIndex().getValue().intValue();
    int to = field.getEndIndex().getValue().intValue();
    assert to == 0 : "NYI: Shifted encoding fields";

    var type = MLIRType.getType(from + 1, false);
    var value = new MLIRValue(field.getName(), type);
    values.put(field, value);

    return value + " : " + type;
  }
}

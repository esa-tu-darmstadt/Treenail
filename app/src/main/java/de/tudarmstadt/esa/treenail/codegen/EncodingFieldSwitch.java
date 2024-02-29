package de.tudarmstadt.esa.treenail.codegen;

import com.minres.coredsl.analysis.AnalysisContext;
import com.minres.coredsl.coreDsl.BitField;
import com.minres.coredsl.coreDsl.BitValue;
import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.coreDsl.util.CoreDslSwitch;
import com.minres.coredsl.util.TypedBigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class EncodingFieldSwitch extends CoreDslSwitch<String> {
  private final Map<NamedEntity, MLIRValue> values;
  private final Map<String, List<EncodingValue>> splitValues =
      new LinkedHashMap<>();

  private static final String UNIQUE_PREFIX = "TREENAIL_WAS_HERE_";

  public class EncodingValue {
    public final NamedEntity name;
    public final MLIRValue val;
    public final int start;
    public final int end;
    public final boolean reversed;

    EncodingValue(NamedEntity name, MLIRValue val, int start, int end,
                  boolean reversed) {
      this.name = name;
      this.val = val;
      this.start = start;
      this.end = end;
      this.reversed = reversed;
    }
  }

  EncodingFieldSwitch(Map<NamedEntity, MLIRValue> values) {
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
    int start = field.getStartIndex().getValue().intValue();
    int end = field.getEndIndex().getValue().intValue();
    boolean reversed = start < end;
    if (reversed) {
      int tmp = end;
      end = start;
      start = tmp;
    }

    // Collect all bit fields
    var fields =
        splitValues.computeIfAbsent(field.getName(), s -> new ArrayList<>());
    var type = MLIRType.getType(start - end + 1, false);
    var value = new MLIRValue(
        UNIQUE_PREFIX + field.getName() + '_' + start + '_' + end, type);
    fields.add(new EncodingValue(field, value, start, end, reversed));

    return value + " : " + type;
  }

  public void combineSplitValues(List<String> splitValueDefStmts) {
    // Merge possibly split and/or reversed bit fields
    int tmpValCnt = 0;
    for (var e : splitValues.entrySet()) {
      // first determine the final type
      var parts = e.getValue();
      int width = 0;
      for (var v : parts) {
        width += v.val.type.width;
      }
      var type = MLIRType.getType(width, false);
      var targetValue = new MLIRValue(e.getKey(), type);
      // Ensure that the mapping to the target value exists
      values.put(parts.get(0).name, targetValue);

      // Sort the split parts
      parts.sort((v1, v2) -> Integer.compare(v1.start, v2.start));

      var expectedName = parts.get(0).name.getName();
      var expectedEnd = parts.get(0).end;
      // Sanity checks
      for (var v : parts) {
        assert expectedName.equals(v.name.getName())
            : "ERROR: split field changed its name";
        if (expectedEnd != v.end) {
          throw new IllegalArgumentException(
              "Invalid encoding mask! A value " + expectedName +
              " was split into multiple parts and bits are missing between [" +
              v.end + ":" + expectedEnd + "]");
        }
        expectedEnd = v.start + 1;
      }

      // Concat the split parts
      MLIRValue tmpVal = null;
      for (var v : parts) {
        var partVal = v.val;
        // Use coredsl.bitextract to reverse bits if requested
        if (v.reversed) {
          var newTmpVar = new MLIRValue(
              UNIQUE_PREFIX + "reversed_" + tmpValCnt++, partVal.type);
          splitValueDefStmts.add(newTmpVar + " = coredsl.bitextract " +
                                 partVal + "[" + v.end + ":" + v.start + "]"
                                 + " : (" + partVal.type + ") -> " +
                                 partVal.type + "\n");
          partVal = newTmpVar;
        }

        if (tmpVal != null) {
          var tmpType =
              MLIRType.getType(tmpVal.type.width + partVal.type.width, false);
          var newTmpVar = new MLIRValue(UNIQUE_PREFIX + tmpValCnt++, tmpType);
          splitValueDefStmts.add(newTmpVar + " = coredsl.concat " + partVal +
                                 ", " + tmpVal + " : " + partVal.type + ", " +
                                 tmpVal.type + "\n");
          tmpVal = newTmpVar;
        } else {
          tmpVal = partVal;
        }
      }
      // Create a NOP operation to copy the value from tmpVal to targetValue
      splitValueDefStmts.add(targetValue + " = coredsl.cast " + tmpVal + " : " +
                             type + " to " + type + "\n");
    }
  }
}

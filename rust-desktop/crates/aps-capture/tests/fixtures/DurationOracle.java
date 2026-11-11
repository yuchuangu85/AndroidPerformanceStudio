import com.androidperformancestudio.capture.*;
import java.math.BigDecimal;

/** Uses the real Compose command builder; normalizes equivalent decimal notation. */
public class DurationOracle {
    public static void main(String[] ignored) {
        Double[] durations = {null, 10.0, 0.25, 10.5, 0.001, 1.000000001, 1e-9};
        for (int index = 0; index < durations.length; index++) {
            var parameters = new SamplingParameters(new SimpleperfTarget.Process(42, "dev.fixture"),
                "cpu-clock", new SamplingRate.Frequency(1000), durations[index],
                CallGraphMode.DWARF, EventScope.USER, "/data/local/tmp/aps/perf.data");
            var arguments = new SimpleperfRecordCommand("fixture", "simpleperf", parameters).getShellArguments();
            int position = arguments.indexOf("--duration");
            String value = position < 0 ? "manual" : new BigDecimal(arguments.get(position + 1)).stripTrailingZeros().toPlainString();
            System.out.println("D\t" + index + "\t" + value);
        }
    }
}

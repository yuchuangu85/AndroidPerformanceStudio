import com.androidperformancestudio.capture.*;
import java.util.ArrayList;
import java.util.List;

/** Runs the real Compose capture model; emits only normalized sampling arguments. */
public class SamplingOracle {
    static String samplingArguments(SamplingParameters parameters) {
        List<String> arguments = new SimpleperfRecordCommand("fixture", "simpleperf", parameters).getShellArguments();
        List<String> sampling = new ArrayList<>();
        for (int i = 2; i < arguments.size(); i++) {
            String flag = arguments.get(i);
            if (List.of("-e", "-f", "-c", "--call-graph").contains(flag)) {
                sampling.add(flag);
                sampling.add(arguments.get(++i));
            } else if (flag.equals("-g")) {
                sampling.add(flag);
            }
        }
        return String.join(" ", sampling);
    }

    public static void main(String[] ignored) {
        for (boolean appScoped : new boolean[] {true, false}) {
            var target = new SimpleperfTarget.Process(42, appScoped ? "dev.fixture" : null);
            int ordinal = 0;
            for (SamplingTemplate template : SamplingTemplate.values()) {
                var parameters = template.create(target);
                System.out.println("T\t" + appScoped + "\t" + ordinal++ + "\t" + parameters.getDurationSeconds().intValue() + "\t" + samplingArguments(parameters));
            }
        }
        int ordinal = 0;
        for (String event : new String[] {"cpu-clock", "cpu-cycles"}) {
            for (EventScope scope : EventScope.values()) {
                for (SamplingRate rate : new SamplingRate[] {new SamplingRate.Frequency(99), new SamplingRate.Period(4096)}) {
                    for (CallGraphMode graph : CallGraphMode.values()) {
                        var parameters = new SamplingParameters(new SimpleperfTarget.Process(42, "dev.fixture"), event, rate, 10.0, graph, scope, "/data/local/tmp/aps/perf.data");
                        System.out.println("M\t" + ordinal++ + "\t" + samplingArguments(parameters));
                    }
                }
            }
        }
    }
}

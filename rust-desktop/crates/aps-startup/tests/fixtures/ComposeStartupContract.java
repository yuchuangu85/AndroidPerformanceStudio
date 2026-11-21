// Fixture oracle: execute the actual compiled Compose importer, analyzer and
// exporter on comparison-cases.csv. No replacement statistical implementation.
import com.androidperformancestudio.startup.analysis.*;
import com.androidperformancestudio.startup.export.*;
import java.nio.file.*;
import java.util.*;

public final class ComposeStartupContract {
    private static StartupAnalysisResult report(Path directory, String template, String name, String values) throws Exception {
        List<String> runs = new ArrayList<>();
        String[] samples = values.split(":");
        for (int i = 0; i < samples.length; i++) {
            long duration = Long.parseLong(samples[i]);
            runs.add(template.replace("\"runId\": \"contract\"", "\"runId\": \"contract-" + (i + 1) + "\"")
                .replace("\"iteration\": 1", "\"iteration\": " + (i + 1))
                .replace("\"displayedTimeMs\": 100", "\"displayedTimeMs\": " + duration)
                .replace("\"elapsedRealtimeNs\": 11000000", "\"elapsedRealtimeNs\": " + (1000000 + duration * 100000)));
        }
        Path input = directory.resolve(name + "-input.json");
        Files.writeString(input, "{\"schemaVersion\":1,\"warnings\":[\"fixture warning\"],\"runs\":[" + String.join(",", runs) + "]}");
        StartupAnalysisResult result = (StartupAnalysisResult) StartupJsonImporter.class.getMethod("import", Path.class).invoke(new StartupJsonImporter(), input);
        new StartupJsonExporter().export(result, directory.resolve(name + ".json"));
        Files.delete(input);
        return result;
    }

    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]);
        String template = Files.readString(fixtures.resolve("compose-run.json"));
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        StringBuilder expected = new StringBuilder("name;status;difference;percent;low;high;threshold;phase\n");
        StartupAnalyzer analyzer = new StartupAnalyzer();
        for (String line : Files.readAllLines(fixtures.resolve("comparison-cases.csv")).stream().skip(1).toList()) {
            String[] fields = line.split(";");
            StartupAnalysisResult current = report(output, template, fields[0] + "-current", fields[1]);
            StartupAnalysisResult baseline = report(output, template, fields[0] + "-baseline", fields[2]);
            StartupComparison c = analyzer.compare(current, baseline, 5.0);
            expected.append(fields[0]).append(';').append(c.getStatus()).append(';').append(c.getMedianDifferenceMs())
                .append(';').append(c.getPercentDifference()).append(';').append(c.getConfidenceIntervalLowMs())
                .append(';').append(c.getConfidenceIntervalHighMs()).append(';').append(c.getPracticalThresholdMs())
                .append(';').append(c.getPhaseDifferences().isEmpty() ? "null" : c.getPhaseDifferences().get(0).getMedianDifferenceMs()).append('\n');
        }
        Files.writeString(output.resolve("expected.csv"), expected);
        System.out.print(expected);
    }
}

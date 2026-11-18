import com.android.tools.idea.io.grpc.ManagedChannel;
import com.android.tools.idea.io.grpc.netty.NettyChannelBuilder;
import com.android.tools.profiler.perfetto.proto.TraceProcessor;
import com.android.tools.profiler.perfetto.proto.TraceProcessorServiceGrpc;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single-sample timing probe for the installed Android Studio default HPROF backend.
 * This is the TraceProcessor daemon path, not the legacy perflib probe and not the whole UI.
 *
 * <pre>
 * STUDIO='/Users/yuchuan.gu/Applications/Android Studio.app/Contents'
 * CP="$STUDIO/plugins/android/lib/*:$STUDIO/lib/*"
 * OUT=$(mktemp -d /tmp/aps-studio-tp-timing.XXXXXX)
 * "$STUDIO/jbr/Contents/Home/bin/javac" -proc:none -Xlint:all,-path -Werror -cp "$CP" \
 *   -d "$OUT" tools/heap-parity/StudioTraceProcessorTimingProbe.java
 * "$STUDIO/jbr/Contents/Home/bin/java" -Xmx1g -cp "$OUT:$CP" \
 *   StudioTraceProcessorTimingProbe \
 *   /absolute/path/to/launcher.hprof \
 *   5d0fa5f467c20a4fee38c6a3f9cba2caaa9d97539a5ae813cbb3284f53e7277a
 * // Alternatively: StudioTraceProcessorTimingProbe --startup-smoke (no input or import).
 * </pre>
 *
 * The probe performs exactly one daemon import and one heap-dump overview RPC. It does not run
 * the launcher sample in this task. The caller owns repetition order, JVM flags, machine load,
 * storage/cache policy and serialization with other large-file samples.
 *
 * Backend evidence for the installed AI-262.10968.63.2623.16898116 build:
 * android-common.jar!/feature_flags.txt contains
 * profiler.use.traceprocessor.for.hprof=COMPLETE:2026; the RC product's default configuration
 * is COMPLETE; production FeatureConfig reads StudioFlags.PROFILER_USE_TRACEPROCESSOR_FOR_HPROF.
 * A property/server/user override can change a running Studio session, so this probe reports the
 * configured installed TP path, not the backend of an already-running GUI session.
 *
 * The daemon is launched as the probe's owned child through /usr/bin/time -l. Its merged output
 * is read until the exact listening-port line is observed. The probe accepts only one process
 * whose command resolves to the exact installed daemon binary and only kills that exact PID.
 * It then waits for /usr/bin/time to reap and return. If ownership or reaping is not provable,
 * cleanup fails closed; it never enumerates or kills unrelated Studio/perfetto processes.
 * maxRSS is populated only when the wrapper's exact "maximum resident set size" line parses after
 * wrapper exit; otherwise daemonPeakRssBytes is null. The RSS is /usr/bin/time's child rusage,
 * not a claim about Studio's full process or UI memory.
 *
 * Timed ranges use System.nanoTime and begin after all input/library identity checks:
 * daemonStartupNs: ProcessBuilder start through the daemon listening-port response;
 * loadTraceRpcNs: blocking LoadTrace response through complete successful response;
 * queryBatchHeapDumpOverviewRpcNs: blocking QueryBatch response through one complete successful
 * HEAP_DUMP_RESULT. Channel construction, daemon identity discovery, cleanup and postflight
 * hashes are outside these ranges. No raw heap rows, IDs, class names, fields or references are
 * emitted. The response contains only scalar timings, counts and aggregate byte totals.
 *
 * The overview class aggregates are computed from ClassOverview rows and retain only row count,
 * object count, shallow/native/retained totals grouped by reported heap name. HeapOverview rows
 * retain only heap-level retained totals. TP shallow/native/retained values are not compared to
 * or forced to equal perflib values. Known count checks are limited to the independently recorded
 * total node count 2,501,406 and App node count 2,073,125; a count mismatch rejects the sample.
 *
 * Direct installed API used here:
 * trace_processor_daemon --server_timeout=5m --llvm_symbolizer_path INSTALLED_SYMBOLIZER
 * TraceProcessorServiceGrpc.newBlockingStub(NettyChannelBuilder.forAddress(...).usePlaintext())
 * LoadTraceRequest(traceId, absolute HPROF path)
 * QueryBatchRequest(QueryParameters(traceId, HeapDumpParameters.getDefaultInstance()))
 * This is the same daemon/RPC shape used by Studio's TraceProcessorServiceImpl.loadHeapDumpData.
 * It is not the separate trace_processor_server/trace-processor CLI, and no SQL shell path is
 * assumed. The actual installed UI adapter also builds ClassDb/HeapSet and other UI state after
 * the overview; this probe does not claim UI readiness, rendering, filters, detail queries,
 * file picking, IDE startup, capture/transport or override resolution.
 */
public final class StudioTraceProcessorTimingProbe {
  private static final String INPUT_SHA256 =
      "5d0fa5f467c20a4fee38c6a3f9cba2caaa9d97539a5ae813cbb3284f53e7277a";
  private static final long INPUT_BYTES = 184_442_700L;
  private static final String PRODUCT_SHA256 =
      "20fb94e6c71ea3527c86b003d1822e7eb048c6f8350e336e3015aaf76c2adbea";
  private static final String PROFILERS_SHA256 =
      "5a21b8c9f3c41167b66e450fbc68bbe0b49b39c1cf0ac3dacbf7f447c9d714f6";
  private static final String COMMON_SHA256 =
      "bd330cd7f1b35b8c30de2e3fbe7bfd51db898b1dd1962cc340a68f7298d1f057";
  private static final String ANDROID_SHA256 =
      "7ed2adaf9e024831d90c746a038bee17b5f86d0d012c8311ed9b6bb84377ae30";
  private static final String PROTO_SHA256 =
      "8f70e596e5117e0d82a623d89b13f1374bccfea90cb80ed8d2b12b9d2aa7d322";
  private static final String GRPC_SHA256 =
      "0bf7c52ef66b9bb19818574957dadf83d2071627cf47e768f7ca5144c402c04d";
  private static final String STUDIO_PROTO_SHA256 =
      "4bebd02abe9a045d0d2920f8a808108407907ba3495f8835b186bed5a5c77d0e";
  private static final String GSON_SHA256 =
      "0564aa34202c4da1d2865b5745c5848570c7fcb1d074a49ed370130154fafdc0";
  private static final String DAEMON_SHA256 =
      "a64e60538396cf4421f29d9c91efef9cd6a3a605f43a4f12865ec0677c68012d";
  private static final String SYMBOLIZER_RELATIVE =
      "plugins/android-ndk/resources/lldb/bin/llvm-symbolizer";
  private static final String SYMBOLIZER_SHA256 =
      "afba5bedbe62fb8ea79d64b53622e04fb44d43fbd525199b575650cc31cd46b4";
  private static final long EXPECTED_ALL_OBJECTS = 2_501_406L;
  private static final long EXPECTED_APP_OBJECTS = 2_073_125L;
  private static final Pattern LISTENING = Pattern.compile(
      "^Server listening on (?:127\\.0\\.0\\.1|localhost):(\\d+)\\s*$");
  private static final Pattern MAX_RSS = Pattern.compile(
      "^\\s*(\\d+)\\s+maximum resident set size\\s*$");

  private StudioTraceProcessorTimingProbe() {}

  public static void main(String[] args) {
    Map<String, Object> result;
    int exitCode = 0;
    try {
      result = run(args);
    } catch (ProbeFailure failure) {
      result = Map.of("ok", false, "error", failure.getMessage());
      exitCode = 1;
    } catch (Exception | LinkageError failure) {
      result = Map.of("ok", false, "error", "probe_failed",
          "exceptionType", failure.getClass().getName());
      exitCode = 2;
    }
    System.out.println(new GsonBuilder().serializeNulls().create().toJson(result));
    if (exitCode != 0) System.exit(exitCode);
  }

  private static Map<String, Object> run(String[] args) throws Exception {
    boolean smoke = args.length == 1 && "--startup-smoke".equals(args[0]);
    require(smoke || args.length == 2, "usage_absolute_hprof_path_expected_sha256_or_--startup-smoke");
    Path input = smoke ? null : Path.of(args[0]);
    String expected = smoke ? null : args[1].toLowerCase(Locale.ROOT);
    if (!smoke) {
      require(input.isAbsolute(), "input_path_must_be_absolute");
      require(expected.matches("[0-9a-f]{64}"), "expected_sha256_must_be_64_hex_digits");
      require(INPUT_SHA256.equals(expected), "unsupported_launcher_sha256");
    }

    Path contents = installedContents();
    List<StableSource> sources = installedSources(contents, input, expected);
    for (StableSource source : sources) source.verify();
    StableSource heapFile = smoke ? null : sources.get(sources.size() - 1);
    if (!smoke) require(heapFile.attributes.size() == INPUT_BYTES, "unexpected_launcher_length");

    Path daemonPath = contents.resolve(
        "plugins/android/resources/trace_processor_daemon/trace_processor_daemon").toRealPath();
    Path symbolizer = contents.resolve(SYMBOLIZER_RELATIVE).toRealPath();

    Process wrapper = null;
    ProcessHandle daemon = null;
    ManagedChannel channel = null;
    Thread outputReader = null;
    AtomicReference<OutputState> outputState = new AtomicReference<>(new OutputState());
    Map<String, Object> result = null;
    Throwable operationFailure = null;
    try {
      long daemonStart = System.nanoTime();
      wrapper = new ProcessBuilder("/usr/bin/time", "-l", daemonPath.toString(),
          "--server_timeout=5m", "--llvm_symbolizer_path", symbolizer.toString())
          .redirectErrorStream(true)
          .start();
      outputReader = startOutputReader(wrapper.getInputStream(), outputState);
      int port = awaitPort(outputState, wrapper, Duration.ofSeconds(10));
      long daemonStartupNs = System.nanoTime() - daemonStart;
      daemon = findOwnedDaemon(wrapper.toHandle(), daemonPath);

      channel = NettyChannelBuilder.forAddress("localhost", port)
          .usePlaintext()
          .maxInboundMessageSize(536_870_912)
          .build();
      TraceProcessorServiceGrpc.TraceProcessorServiceBlockingStub stub =
          TraceProcessorServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(15, TimeUnit.MINUTES);
      if (smoke) {
        TraceProcessor.QueryBatchResponse empty = stub.withDeadlineAfter(5, TimeUnit.SECONDS)
            .queryBatch(TraceProcessor.QueryBatchRequest.getDefaultInstance());
        require(empty.getResultCount() == 0, "empty_query_unexpected_response");
        result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("mode", "startup_smoke_not_a_benchmark");
        result.put("daemonPid", daemon.pid());
        result.put("daemonPort", port);
        result.put("emptyQueryRpcOk", true);
        result.put("measurementIterations", 0);
      } else {
      long traceId = 1L;
      long loadStart = System.nanoTime();
      TraceProcessor.LoadTraceResponse loadResponse = stub.loadTrace(
          TraceProcessor.LoadTraceRequest.newBuilder()
              .setTraceId(traceId)
              .setTracePath(heapFile.realPath.toString())
              .build());
      long loadTraceRpcNs = System.nanoTime() - loadStart;
      require(loadResponse.getOk(), "load_trace_rpc_not_ok");

      long overviewStart = System.nanoTime();
      TraceProcessor.QueryBatchResponse batchResponse = stub.queryBatch(
          TraceProcessor.QueryBatchRequest.newBuilder()
              .addQuery(TraceProcessor.QueryParameters.newBuilder()
                  .setTraceId(traceId)
                  .setHeapDumpRequest(
                      TraceProcessor.QueryParameters.HeapDumpParameters.getDefaultInstance()))
              .build());
      long overviewRpcNs = System.nanoTime() - overviewStart;
      Overview overview = parseOverview(batchResponse);
      require(overview.totalObjects == EXPECTED_ALL_OBJECTS, "aggregate_mismatch_all_objects");
      require(overview.appObjects == EXPECTED_APP_OBJECTS, "aggregate_mismatch_app_objects");

      result = new LinkedHashMap<>();
      result.put("ok", true);
      result.put("backend", "installed_studio_trace_processor_daemon");
      result.put("configuredDefaultBackend", "trace_processor");
      result.put("studioBuild", "AI-262.10968.63.2623.16898116");
      result.put("inputSha256", expected);
      result.put("inputBytes", heapFile.attributes.size());
      result.put("productInfoSha256", PRODUCT_SHA256);
      result.put("androidProfilersSha256", PROFILERS_SHA256);
      result.put("androidCommonSha256", COMMON_SHA256);
      result.put("androidJarSha256", ANDROID_SHA256);
      result.put("traceProcessorProtosSha256", PROTO_SHA256);
      result.put("studioGrpcSha256", GRPC_SHA256);
      result.put("studioProtoSha256", STUDIO_PROTO_SHA256);
      result.put("gsonSha256", GSON_SHA256);
      result.put("traceProcessorDaemonSha256", DAEMON_SHA256);
      result.put("llvmSymbolizerSha256", SYMBOLIZER_SHA256);
      result.put("javaRuntimeVersion", System.getProperty("java.runtime.version"));
      result.put("daemonPid", daemon.pid());
      result.put("daemonPort", port);
      result.put("measurementIterations", 1);
      result.put("daemonStartupNs", daemonStartupNs);
      result.put("loadTraceRpcNs", loadTraceRpcNs);
      result.put("queryBatchHeapDumpOverviewRpcNs", overviewRpcNs);
      result.put("overviewResponseComplete", true);
      result.put("knownCountValidation", "passed");
      result.put("classOverviewRowCount", overview.classRowCount);
      result.put("classOverviewTotalObjectCount", overview.totalObjects);
      result.put("classOverviewAppObjectCount", overview.appObjects);
      result.put("classOverviewAggregateCount", overview.classAggregates.size());
      result.put("classAggregates", overview.classAggregates);
      result.put("heapOverviewRowCount", overview.heapAggregates.size());
      result.put("heapAggregates", overview.heapAggregates);
      result.put("daemonPeakRssBytes", null);
      }
    } catch (Throwable failure) {
      operationFailure = failure;
    } finally {
      Throwable cleanupFailure = cleanup(wrapper, daemon, daemonPath, channel, outputReader,
          outputState);
      if (operationFailure == null && cleanupFailure != null) operationFailure = cleanupFailure;
      try {
        for (StableSource source : sources) source.verify();
      } catch (Throwable postflightFailure) {
        if (operationFailure == null) operationFailure = postflightFailure;
      }
      if (operationFailure == null && result != null) {
        result.put("daemonPeakRssBytes", parseMaxRss(outputState.get().lines));
        result.put("daemonWrapperExitCode", outputState.get().wrapperExitCode);
        result.put("sourceStable", true);
      }
    }
    if (operationFailure != null) {
      if (operationFailure instanceof ProbeFailure probeFailure) throw probeFailure;
      throw new ProbeFailure("probe_failed");
    }
    return result;
  }

  private static Path installedContents() throws Exception {
    Path javaHome = Path.of(System.getProperty("java.home")).toRealPath();
    Path contents = javaHome.getParent().getParent().getParent();
    require(contents.resolve("jbr/Contents/Home").toRealPath().equals(javaHome),
        "use_installed_studio_jbr");
    require(Files.isDirectory(contents.resolve("plugins/android/lib")),
        "unexpected_studio_contents");
    return contents;
  }

  private static List<StableSource> installedSources(Path contents, Path input, String expected)
      throws Exception {
    List<StableSource> sources = new ArrayList<>();
    sources.add(new StableSource(contents.resolve("Resources/product-info.json"), PRODUCT_SHA256));
    sources.add(new StableSource(contents.resolve("plugins/android/lib/android-profilers.jar"),
        PROFILERS_SHA256));
    sources.add(new StableSource(contents.resolve("plugins/android/lib/android-common.jar"),
        COMMON_SHA256));
    sources.add(new StableSource(contents.resolve("plugins/android/lib/android.jar"), ANDROID_SHA256));
    sources.add(new StableSource(contents.resolve("plugins/android/lib/traceprocessor_protos.jar"),
        PROTO_SHA256));
    sources.add(new StableSource(contents.resolve("plugins/android/lib/studio-grpc.jar"), GRPC_SHA256));
    sources.add(new StableSource(contents.resolve("plugins/android/lib/studio-proto.jar"),
        STUDIO_PROTO_SHA256));
    sources.add(new StableSource(contents.resolve("lib/intellij.libraries.gson.jar"), GSON_SHA256));
    sources.add(new StableSource(contents.resolve(SYMBOLIZER_RELATIVE), SYMBOLIZER_SHA256));
    sources.add(new StableSource(contents.resolve(
        "plugins/android/resources/trace_processor_daemon/trace_processor_daemon"), DAEMON_SHA256));
    if (input != null) sources.add(new StableSource(input, expected));
    return sources;
  }

  private static Thread startOutputReader(InputStream input, AtomicReference<OutputState> state) {
    Thread reader = new Thread(() -> {
      try (BufferedReader lines = new BufferedReader(new InputStreamReader(input))) {
        String line;
        while ((line = lines.readLine()) != null) {
          OutputState current = state.get();
          synchronized (current.lines) {
            current.lines.add(line);
          }
          Matcher match = LISTENING.matcher(line);
          if (match.matches()) {
            current.port.set(Integer.parseInt(match.group(1)));
            current.portReady.countDown();
          }
        }
      } catch (Exception failure) {
        state.get().readerFailure.set(failure.getClass().getSimpleName());
      } finally {
        // EOF without a port is a startup failure, not a full timeout.
        state.get().portReady.countDown();
      }
    }, "aps-owned-tp-output-reader");
    reader.setDaemon(false);
    reader.start();
    return reader;
  }

  private static int awaitPort(AtomicReference<OutputState> state, Process wrapper, Duration timeout)
      throws InterruptedException {
    OutputState output = state.get();
    if (!output.portReady.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
      throw new ProbeFailure("daemon_start_timeout");
    }
    require(output.readerFailure.get() == null, "daemon_output_reader_failed");
    require(wrapper.isAlive(), "daemon_exited_before_port");
    int port = output.port.get();
    require(port > 0 && port <= 65_535, "invalid_daemon_port");
    return port;
  }

  private static ProcessHandle findOwnedDaemon(ProcessHandle wrapper, Path daemonPath)
      throws Exception {
    List<ProcessHandle> candidates = new ArrayList<>();
    addIfExact(candidates, wrapper, daemonPath);
    wrapper.descendants().forEach(handle -> addIfExact(candidates, handle, daemonPath));
    candidates.sort(Comparator.comparingLong(ProcessHandle::pid));
    require(candidates.size() == 1, "daemon_pid_ownership_unresolved");
    ProcessHandle daemon = candidates.get(0);
    require(daemon.isAlive(), "daemon_not_alive_after_port");
    return daemon;
  }

  private static void addIfExact(List<ProcessHandle> candidates, ProcessHandle handle, Path path) {
    if (!handle.isAlive()) return;
    try {
      String command = handle.info().command().orElse(null);
      if (command != null && path.equals(Path.of(command).toRealPath())) candidates.add(handle);
    } catch (Exception ignored) {
      // An uninspectable process is not accepted as the owned daemon.
    }
  }

  private static Throwable cleanup(Process wrapper, ProcessHandle daemon, Path daemonPath,
      ManagedChannel channel, Thread outputReader, AtomicReference<OutputState> state) {
    Throwable failure = null;
    // Startup failures can occur before the normal ownership lookup.
    if (daemon == null && wrapper != null && wrapper.isAlive()) {
      try {
        daemon = findOwnedDaemon(wrapper.toHandle(), daemonPath);
      } catch (Exception ownershipFailure) {
        failure = new ProbeFailure("daemon_cleanup_ownership_unresolved");
      }
    }
    if (channel != null) {
      try {
        channel.shutdownNow();
        if (!channel.awaitTermination(15, TimeUnit.SECONDS)) {
          failure = new ProbeFailure("channel_threads_not_reaped");
        }
      } catch (Throwable cleanupFailure) {
        failure = cleanupFailure;
      }
    }
    if (daemon != null) {
      try {
        requireOwnedDaemon(daemon, wrapper, daemonPath);
        if (daemon.isAlive()) {
          daemon.destroy();
          try {
            daemon.onExit().get(3, TimeUnit.SECONDS);
          } catch (TimeoutException timeout) {
            requireOwnedDaemon(daemon, wrapper, daemonPath);
            daemon.destroyForcibly();
            require(!daemon.onExit().get(5, TimeUnit.SECONDS).isAlive(), "daemon_not_reaped");
          }
        }
      } catch (Throwable cleanupFailure) {
        if (failure == null) failure = cleanupFailure;
      }
    }
    if (wrapper != null) {
      try {
        if (!wrapper.waitFor(15, TimeUnit.SECONDS)) {
          if (failure == null) failure = new ProbeFailure("daemon_wrapper_not_reaped");
        }
        state.get().wrapperExitCode = wrapper.isAlive() ? null : wrapper.exitValue();
      } catch (Throwable cleanupFailure) {
        if (failure == null) failure = cleanupFailure;
      }
    }
    if (outputReader != null) {
      try {
        outputReader.join(15_000L);
        if (outputReader.isAlive() && failure == null) {
          failure = new ProbeFailure("daemon_output_reader_not_reaped");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        if (failure == null) failure = interrupted;
      }
    }
    return failure;
  }

  private static void requireOwnedDaemon(ProcessHandle daemon, Process wrapper, Path daemonPath)
      throws Exception {
    if (!daemon.isAlive()) return;
    require(daemon.pid() != wrapper.pid(), "wrapper_is_not_exact_daemon");
    boolean descendant = wrapper.toHandle().descendants()
        .anyMatch(candidate -> candidate.pid() == daemon.pid());
    require(descendant, "daemon_is_not_wrapper_descendant");
    require(daemonPath.equals(commandPath(daemon)), "daemon_command_changed");
  }

  private static Path commandPath(ProcessHandle handle) throws Exception {
    String command = handle.info().command().orElseThrow(
        () -> new ProbeFailure("daemon_command_unavailable"));
    return Path.of(command).toRealPath();
  }

  private static Long parseMaxRss(List<String> lines) {
    Long value = null;
    synchronized (lines) {
      for (String line : lines) {
        Matcher match = MAX_RSS.matcher(line);
        if (match.matches()) value = Long.parseLong(match.group(1));
      }
    }
    return value;
  }

  private static Overview parseOverview(TraceProcessor.QueryBatchResponse response) {
    require(response.getResultCount() == 1, "overview_response_count_incomplete");
    TraceProcessor.QueryResult result = response.getResult(0);
    require(result.getOk(), "overview_query_not_ok");
    require(result.getResultCase() == TraceProcessor.QueryResult.ResultCase.HEAP_DUMP_RESULT,
        "overview_result_type_incomplete");
    require(result.hasHeapDumpResult(), "overview_heap_dump_result_missing");
    TraceProcessor.HeapDumpResult heapDump = result.getHeapDumpResult();
    require(!heapDump.getClassOverviewList().isEmpty(), "overview_class_aggregates_missing");
    Map<String, ClassAggregate> classByHeap = new LinkedHashMap<>();
    for (TraceProcessor.HeapDumpResult.ClassOverview row : heapDump.getClassOverviewList()) {
      require(row.getInstanceCount() >= 0 && row.getShallowSize() >= 0
          && row.getNativeSize() >= 0 && row.getRetainedSize() >= 0
          && row.getRetainedNativeSize() >= 0, "overview_negative_aggregate");
      String heap = row.getHeapName().isEmpty() ? "default" : row.getHeapName();
      ClassAggregate aggregate = classByHeap.computeIfAbsent(heap, ClassAggregate::new);
      aggregate.add(row);
    }
    List<Map<String, Object>> classAggregates = new ArrayList<>();
    for (ClassAggregate aggregate : classByHeap.values()) classAggregates.add(aggregate.toMap());
    List<Map<String, Object>> heapAggregates = new ArrayList<>();
    for (TraceProcessor.HeapDumpResult.HeapOverview row : heapDump.getHeapOverviewList()) {
      require(row.getRetainedSize() >= 0 && row.getRetainedNativeSize() >= 0,
          "overview_negative_heap_aggregate");
      Map<String, Object> aggregate = new LinkedHashMap<>();
      aggregate.put("heapName", row.getHeapName().isEmpty() ? "default" : row.getHeapName());
      aggregate.put("retainedBytes", row.getRetainedSize());
      aggregate.put("retainedNativeBytes", row.getRetainedNativeSize());
      heapAggregates.add(aggregate);
    }
    long total = 0;
    long app = 0;
    for (ClassAggregate aggregate : classByHeap.values()) {
      total = Math.addExact(total, aggregate.objectCount);
      if (isAppHeap(aggregate.heapName)) app = Math.addExact(app, aggregate.objectCount);
    }
    return new Overview(heapDump.getClassOverviewCount(), total, app, classAggregates,
        heapAggregates);
  }

  private static boolean isAppHeap(String heapName) {
    return "app".equalsIgnoreCase(heapName) || "HEAP_TYPE_APP".equals(heapName);
  }

  private static final class Overview {
    final long classRowCount;
    final long totalObjects;
    final long appObjects;
    final List<Map<String, Object>> classAggregates;
    final List<Map<String, Object>> heapAggregates;

    Overview(long classRowCount, long totalObjects, long appObjects,
        List<Map<String, Object>> classAggregates, List<Map<String, Object>> heapAggregates) {
      this.classRowCount = classRowCount;
      this.totalObjects = totalObjects;
      this.appObjects = appObjects;
      this.classAggregates = classAggregates;
      this.heapAggregates = heapAggregates;
    }
  }

  private static final class ClassAggregate {
    final String heapName;
    long rowCount;
    long objectCount;
    long shallowBytes;
    long nativeBytes;
    long retainedBytes;
    long retainedNativeBytes;

    ClassAggregate(String heapName) {
      this.heapName = heapName;
    }

    void add(TraceProcessor.HeapDumpResult.ClassOverview row) {
      rowCount++;
      objectCount = Math.addExact(objectCount, row.getInstanceCount());
      shallowBytes = Math.addExact(shallowBytes, row.getShallowSize());
      nativeBytes = Math.addExact(nativeBytes, row.getNativeSize());
      retainedBytes = Math.addExact(retainedBytes, row.getRetainedSize());
      retainedNativeBytes = Math.addExact(retainedNativeBytes, row.getRetainedNativeSize());
    }

    Map<String, Object> toMap() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("heapName", heapName);
      result.put("classRowCount", rowCount);
      result.put("objectCount", objectCount);
      result.put("shallowBytes", shallowBytes);
      result.put("nativeBytes", nativeBytes);
      result.put("retainedBytes", retainedBytes);
      result.put("retainedNativeBytes", retainedNativeBytes);
      return result;
    }
  }

  private static final class OutputState {
    final CountDownLatch portReady = new CountDownLatch(1);
    final AtomicInteger port = new AtomicInteger(-1);
    final AtomicReference<String> readerFailure = new AtomicReference<>();
    final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    volatile Integer wrapperExitCode;
  }

  private static final class StableSource {
    final Path path;
    final Path realPath;
    final String expectedSha256;
    final BasicFileAttributes attributes;

    StableSource(Path path, String expectedSha256) throws Exception {
      this.path = path.toAbsolutePath().normalize();
      this.realPath = this.path.toRealPath();
      this.expectedSha256 = expectedSha256;
      this.attributes = Files.readAttributes(realPath, BasicFileAttributes.class);
      require(attributes.isRegularFile() && attributes.fileKey() != null,
          "source_requires_regular_file_and_file_key");
    }

    void verify() throws Exception {
      verifyAttributes();
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream stream = Files.newInputStream(realPath)) {
        byte[] block = new byte[64 * 1024];
        int length;
        while ((length = stream.read(block)) != -1) digest.update(block, 0, length);
      }
      verifyAttributes();
      require(expectedSha256.equals(HexFormat.of().formatHex(digest.digest())),
          "source_sha256_mismatch");
    }

    private void verifyAttributes() throws Exception {
      require(realPath.equals(path.toRealPath()), "source_real_path_changed");
      BasicFileAttributes current = Files.readAttributes(realPath, BasicFileAttributes.class);
      require(current.isRegularFile() && attributes.fileKey().equals(current.fileKey())
          && attributes.size() == current.size()
          && attributes.lastModifiedTime().equals(current.lastModifiedTime())
          && attributes.creationTime().equals(current.creationTime()), "source_identity_changed");
    }
  }

  private static void require(boolean condition, String code) {
    if (!condition) throw new ProbeFailure(code);
  }

  private static final class ProbeFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ProbeFailure(String code) {
      super(code);
    }
  }
}

import com.android.tools.perflib.heap.ClassObj;
import com.android.tools.perflib.heap.Heap;
import com.android.tools.perflib.heap.Instance;
import com.android.tools.perflib.heap.Snapshot;
import com.android.tools.perflib.heap.ext.NativeRegistryPostProcessor;
import com.android.tools.perflib.heap.io.InMemoryBuffer;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single-shot timing of the installed legacy perflib backend; not a Studio UI benchmark.
 *
 * <pre>
 * STUDIO='/Users/yuchuan.gu/Applications/Android Studio.app/Contents'
 * CP="$STUDIO/plugins/android/lib/*:$STUDIO/lib/*"
 * OUT=$(mktemp -d /tmp/aps-import-timing.XXXXXX)
 * "$STUDIO/jbr/Contents/Home/bin/javac" -proc:none -Xlint:all,-path -Werror -cp "$CP" \
 *   -d "$OUT" tools/heap-parity/ImportTimingProbe.java
 * "$STUDIO/jbr/Contents/Home/bin/java" -Xmx3g -cp "$OUT:$CP" ImportTimingProbe \
 *   /absolute/path/to/launcher.hprof \
 *   5d0fa5f467c20a4fee38c6a3f9cba2caaa9d97539a5ae813cbb3284f53e7277a --summary
 * </pre>
 *
 * Exactly one parse/retained computation per process, no parse warmup or repetition option.
 * The caller owns repetition order, JVM flags, system load and cache policy. A fresh JVM is
 * not equivalent to a warmed Studio JVM. Preflight hashes read the entire input and libraries,
 * affecting OS caches and JVM state: this is NOT a cold-storage measurement.
 *
 * Installed-bytecode evidence (JBR javap -c -p, Rabbit 3 / 2026.2.3 RC 1):
 * InMemoryBuffer(File) uses RandomAccessFile("r") and FileChannel.map(READ_ONLY), not an eager
 * file read. bufferCreateNs covers open/map/close; later page faults can occur in createSnapshotNs.
 * Snapshot.createSnapshot(buffer) includes HprofParser.parseBuffer and the default
 * NativeRegistryPostProcessor, using an empty ProguardMap. computeRetainedSizesNs is separate.
 * coreLoadNs spans only these three contiguous stages, including their timer boundaries.
 * Mandatory aggregate validation is untimed. --summary adds a separately timed scalar-only
 * traversal AFTER validation; without it summaryTraversalNs is null, never a fabricated zero.
 * Identity checks, SHA-256, golden assertions, JSON construction/output and dispose are not timed.
 * No explicit GC, heap values, object IDs, per-object rows or root chains are exported.
 * Retained sums in the optional summary overlap; they are not union retained sizes.
 *
 * HeapDumpCaptureObject.load(InMemoryBuffer) uses this same snapshot/native/retained sequence,
 * then creates ClassDb/HeapSet/instance adapters, filters and other presentation state omitted
 * here. Its load(Range, Executor) lambda selects TraceProcessor for non-ART dumps, or for ART
 * when isUseTraceProcessorForHprofEnabled is true: loadTrace then loadFromTraceProcessor /
 * loadHeapDumpData. This probe neither executes nor times that separate path, UI readiness,
 * IDE/JVM startup, file picking, capture/transport, remapping, or subsequent UI queries.
 * It does not establish which branch an already running Studio session uses.
 *
 * DEFAULT BACKEND: this installed build defaults to TraceProcessor, NOT perflib. Evidence:
 * android-common.jar!/feature_flags.txt line 169 contains
 * profiler.use.traceprocessor.for.hprof=COMPLETE:2026. FeatureConfigurationProvider loads
 * this as COMPLETE; FeatureConfiguration computes COMPLETE for an RC version. The production
 * IntellijProfilerServices.FeatureConfigProd reads StudioFlags.PROFILER_USE_TRACEPROCESSOR_FOR_HPROF.
 * BooleanFlag's bare false fallback is NOT the configured default: the bundled file provider
 * supplies true. User/property/server overrides can change the effective running-session value;
 * no running Studio session was inspected. Do not report these perflib times as default Studio times.
 *
 * TP HANDOFF ONLY (not executed here): the installed importer executable is
 * $STUDIO/plugins/android/resources/trace_processor_daemon/trace_processor_daemon.
 * Launch a new owned process with --server_timeout=5m, read its merged stdout/stderr until
 * ^Server listening on (?:127.0.0.1|localhost):(?&lt;port&gt;\d+)\n*$ matches, and use that port.
 * Studio also supplies --llvm_symbolizer_path and the installed symbolizer path; that option
 * serves symbolization, not an HPROF input argument. This daemon is a gRPC service, not the
 * upstream trace_processor_shell CLI: do not pass a .hprof as a positional CLI argument.
 * trace_processor_server/trace-processor is the separate Sherlock server, not this GUI path.
 * With the same bundled JAR classpath, the direct installed RPC API is:
 *
 * <pre>
 * // Imports: com.android.tools.profiler.perfetto.proto.TraceProcessor.*;
 * //          com.android.tools.profiler.perfetto.proto.TraceProcessorServiceGrpc;
 * var channel = com.android.tools.idea.io.grpc.netty.NettyChannelBuilder
 *     .forAddress("localhost", port).usePlaintext().maxInboundMessageSize(536870912).build();
 * var stub = TraceProcessorServiceGrpc.newBlockingStub(channel)
 *     .withDeadlineAfter(10, java.util.concurrent.TimeUnit.MINUTES);
 * var loaded = stub.loadTrace(LoadTraceRequest.newBuilder()
 *     .setTraceId(1L).setTracePath(absoluteHprofPath).build());
 * // Reject !loaded.getOk(); never print response/error contents or heap rows.
 * var batch = stub.queryBatch(QueryBatchRequest.newBuilder().addQuery(
 *     QueryParameters.newBuilder().setTraceId(1L)
 *         .setHeapDumpRequest(QueryParameters.HeapDumpParameters.getDefaultInstance())).build());
 * // Require exactly one result, getOk(), and hasHeapDumpResult() before using its scalars.
 * // Always shut down/await the channel and terminate/wait only for the owned daemon.
 * </pre>
 *
 * LoadTrace is the import RPC; QueryBatch(heap_dump_request) is the separate overview RPC
 * used by TraceProcessorServiceImpl.loadHeapDumpData. Time them separately, with daemon startup
 * and provenance/hash checks outside those intervals. The requests/signatures and --helpfull
 * were inspected, but no daemon service, RPC, or HPROF import was run. No standalone TP timing
 * implementation or TP correctness parity is claimed. SDK and TP may use different size policies;
 * the perflib golden aggregates below must not silently become TP acceptance criteria.
 * TP evidence SHA-256 (documentation provenance, not additional perflib runtime pins):
 * android-common.jar: bd330cd7f1b35b8c30de2e3fbe7bfd51db898b1dd1962cc340a68f7298d1f057
 * android.jar: 7ed2adaf9e024831d90c746a038bee17b5f86d0d012c8311ed9b6bb84377ae30
 * traceprocessor_protos.jar: 8f70e596e5117e0d82a623d89b13f1374bccfea90cb80ed8d2b12b9d2aa7d322
 * trace_processor_daemon: a64e60538396cf4421f29d9c91efef9cd6a3a605f43a4f12865ec0677c68012d
 *
 * Pins below copy installed-library identity from the unchanged baseline, but deliberately
 * bind the CURRENT authorized launcher hash and aggregate evidence in
 * rust-desktop/HPROF_FUNCTIONAL_VALIDATION.md, not the manifest's older input hash.
 * File identity (real path, file key, length, modification/creation times) and full SHA-256
 * are checked before and after work. These checks detect ordinary changes, not an adversary
 * mutating and restoring a mapped file between checks; the caller must keep sources immutable.
 * Stdout is one flat JSON object of scalar values. Rejected results have no timing fields
 * and exit nonzero; failures omit exception messages/stack traces to avoid leaking heap data.
 */
public final class ImportTimingProbe {
  private static final String INPUT_SHA256 =
      "5d0fa5f467c20a4fee38c6a3f9cba2caaa9d97539a5ae813cbb3284f53e7277a";
  private static final long INPUT_BYTES = 184_442_700L;
  private static final String SDK_SHA256 =
      "9aed366191805dea00bc3e8abe4c7d37a6f80a9818e1e30bbea57ab5ac3d7864";
  private static final String PROFILERS_SHA256 =
      "5a21b8c9f3c41167b66e450fbc68bbe0b49b39c1cf0ac3dacbf7f447c9d714f6";
  private static final String PRODUCT_SHA256 =
      "20fb94e6c71ea3527c86b003d1822e7eb048c6f8350e336e3015aaf76c2adbea";

  private ImportTimingProbe() {}

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
    require(args.length == 2 || (args.length == 3 && "--summary".equals(args[2])),
        "usage_absolute_hprof_path_expected_sha256_optional_--summary");
    Path input = Path.of(args[0]);
    require(input.isAbsolute(), "input_path_must_be_absolute");
    String expected = args[1].toLowerCase(Locale.ROOT);
    require(expected.matches("[0-9a-f]{64}"), "expected_sha256_must_be_64_hex_digits");
    require(INPUT_SHA256.equals(expected), "unsupported_launcher_sha256");
    boolean withSummary = args.length == 3;

    Path sdk = codeSource(Snapshot.class);
    Path contents = sdk.getParent().getParent().getParent().getParent();
    require(sdk.equals(contents.resolve("plugins/android/lib/sdk-tools.jar").toRealPath()),
        "unexpected_perflib_location");
    for (Class<?> type : List.of(InMemoryBuffer.class, Heap.class, Instance.class,
        ClassObj.class, NativeRegistryPostProcessor.class)) {
      require(sdk.equals(codeSource(type)), "mixed_perflib_class_sources");
    }
    require(Path.of(System.getProperty("java.home")).toRealPath().equals(
        contents.resolve("jbr/Contents/Home").toRealPath()), "use_installed_studio_jbr");

    List<StableSource> sources = new ArrayList<>();
    sources.add(new StableSource(contents.resolve("Resources/product-info.json"), PRODUCT_SHA256));
    sources.add(new StableSource(sdk, SDK_SHA256));
    sources.add(new StableSource(contents.resolve("plugins/android/lib/android-profilers.jar"),
        PROFILERS_SHA256));
    StableSource heapFile = new StableSource(input, expected);
    sources.add(heapFile);
    require(heapFile.attributes.size() == INPUT_BYTES, "unexpected_launcher_length");
    File inputFile = heapFile.realPath.toFile();

    InMemoryBuffer buffer = null;
    Snapshot snapshot = null;
    try {
      long start = System.nanoTime();
      buffer = new InMemoryBuffer(inputFile);
      long bufferReady = System.nanoTime();
      snapshot = Snapshot.createSnapshot(buffer);
      long snapshotReady = System.nanoTime();
      snapshot.computeRetainedSizes();
      long retainedReady = System.nanoTime();

      Counts counts = validate(snapshot);
      RetainedSummary summary = null;
      Long summaryNs = null;
      if (withSummary) {
        long summaryStart = System.nanoTime();
        summary = summarize(snapshot);
        summaryNs = System.nanoTime() - summaryStart;
        expect(summary.nodes, counts.nodes, "summary_nodes");
        expect(summary.appObjectArrays, 86_385L, "app_object_arrays");
        expect(summary.appObjectArrayShallow, 2_825_900L, "app_object_array_shallow");
        expect(summary.appObjectArrayRetainedSum, 175_106_267L,
            "app_object_array_retained_sum_overlapping");
      }

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("ok", true);
      result.put("backend", "installed_studio_legacy_perflib");
      result.put("studioBuild", "AI-262.10968.63.2623.16898116");
      result.put("inputSha256", expected);
      result.put("inputBytes", heapFile.attributes.size());
      result.put("sdkToolsSha256", SDK_SHA256);
      result.put("androidProfilersSha256", PROFILERS_SHA256);
      result.put("productInfoSha256", PRODUCT_SHA256);
      result.put("javaRuntimeVersion", System.getProperty("java.runtime.version"));
      result.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
      result.put("measurementIterations", 1);
      result.put("parseWarmupIterations", 0);
      result.put("cacheState", "uncontrolled_after_preflight_sha256_reads");
      result.put("sourceStable", true);
      result.put("bufferCreateNs", bufferReady - start);
      result.put("createSnapshotNs", snapshotReady - bufferReady);
      result.put("computeRetainedSizesNs", retainedReady - snapshotReady);
      result.put("coreLoadNs", retainedReady - start);
      result.put("summaryTraversalNs", summaryNs);
      result.put("heapCount", snapshot.getHeaps().size());
      result.put("rawRootCount", snapshot.getGcRoots().size());
      result.put("allNodeCount", counts.nodes);
      result.put("allOrdinaryCount", counts.ordinary);
      result.put("allClassObjectCount", counts.nodes - counts.ordinary);
      result.put("allShallowBytes", counts.shallow);
      result.put("allNativeBytes", counts.nativeBytes);
      result.put("allNativeOwnerCount", counts.nativeOwners);
      result.put("appNodeCount", counts.appNodes);
      result.put("appOrdinaryCount", counts.appOrdinary);
      result.put("appClassObjectCount", counts.appNodes - counts.appOrdinary);
      result.put("appShallowBytes", counts.appShallow);
      result.put("appNativeBytes", counts.appNative);
      result.put("appNativeOwnerCount", counts.appNativeOwners);
      if (summary != null) {
        result.put("reachableNodeCount", summary.reachable);
        result.put("reachableShallowBytes", summary.reachableShallow);
        result.put("allRetainedSumOverlappingBytes", summary.retainedSum);
        result.put("appRetainedSumOverlappingBytes", summary.appRetainedSum);
        result.put("appObjectArrayCount", summary.appObjectArrays);
        result.put("appObjectArrayShallowBytes", summary.appObjectArrayShallow);
        result.put("appObjectArrayRetainedSumOverlappingBytes", summary.appObjectArrayRetainedSum);
      }
      return result;
    } finally {
      try {
        if (snapshot != null) snapshot.dispose();
        else if (buffer != null) buffer.dispose();
      } finally {
        // No result is emitted until cleanup and every postflight check succeeds.
        for (StableSource source : sources) source.verify();
      }
    }
  }

  private static Counts validate(Snapshot snapshot) {
    Counts counts = new Counts();
    int appHeaps = 0;
    for (Heap heap : snapshot.getHeaps()) {
      boolean app = "app".equalsIgnoreCase(heap.getName());
      if (app) appHeaps++;
      // Heap.forEachInstance visits ordinary instances only; class objects are separate.
      for (ClassObj type : heap.getClasses()) counts.add(type, app, false);
      heap.forEachInstance(instance -> {
        counts.add(instance, app, true);
        return true;
      });
    }
    expect(appHeaps, 1L, "app_heap_count");
    expect(snapshot.getGcRoots().size(), 390_650L, "raw_roots");
    expect(counts.nodes, 2_501_406L, "all_nodes");
    expect(counts.ordinary, 2_466_900L, "all_ordinary");
    expect(counts.nativeBytes, 34_971_067L, "all_native_bytes");
    expect(counts.nativeOwners, 122_947L, "all_native_owners");
    expect(counts.appNodes, 2_073_125L, "app_nodes");
    expect(counts.appOrdinary, 2_069_044L, "app_ordinary");
    expect(counts.appShallow, 104_666_905L, "app_shallow");
    expect(counts.appNative, 34_945_672L, "app_native_bytes");
    expect(counts.appNativeOwners, 122_703L, "app_native_owners");
    return counts;
  }

  private static RetainedSummary summarize(Snapshot snapshot) {
    RetainedSummary summary = new RetainedSummary();
    for (Heap heap : snapshot.getHeaps()) {
      boolean app = "app".equalsIgnoreCase(heap.getName());
      for (ClassObj type : heap.getClasses()) summary.add(type, app, false);
      heap.forEachInstance(instance -> {
        summary.add(instance, app, true);
        return true;
      });
    }
    return summary;
  }

  private static final class Counts {
    long nodes, ordinary, shallow, nativeBytes, nativeOwners;
    long appNodes, appOrdinary, appShallow, appNative, appNativeOwners;

    void add(Instance instance, boolean app, boolean ordinaryInstance) {
      long size = instance.getSize();
      long nativeSize = instance.getNativeSize();
      require(size >= 0 && nativeSize >= 0, "negative_size");
      nodes++;
      if (ordinaryInstance) ordinary++;
      shallow = Math.addExact(shallow, size);
      nativeBytes = Math.addExact(nativeBytes, nativeSize);
      if (nativeSize > 0) nativeOwners++;
      if (app) {
        appNodes++;
        if (ordinaryInstance) appOrdinary++;
        appShallow = Math.addExact(appShallow, size);
        appNative = Math.addExact(appNative, nativeSize);
        if (nativeSize > 0) appNativeOwners++;
      }
    }
  }

  private static final class RetainedSummary {
    long nodes, reachable, reachableShallow, retainedSum, appRetainedSum;
    long appObjectArrays, appObjectArrayShallow, appObjectArrayRetainedSum;

    void add(Instance instance, boolean app, boolean ordinaryInstance) {
      long retained = instance.getTotalRetainedSize();
      require(retained >= 0, "negative_retained_size");
      nodes++;
      retainedSum = Math.addExact(retainedSum, retained);
      if (instance.isReachable()) {
        reachable++;
        reachableShallow = Math.addExact(reachableShallow, instance.getSize());
      }
      if (app) {
        appRetainedSum = Math.addExact(appRetainedSum, retained);
        ClassObj type = ordinaryInstance ? instance.getClassObj() : null;
        if (type != null && "java.lang.Object[]".equals(type.getClassName())) {
          appObjectArrays++;
          appObjectArrayShallow = Math.addExact(appObjectArrayShallow, instance.getSize());
          appObjectArrayRetainedSum = Math.addExact(appObjectArrayRetainedSum, retained);
        }
      }
    }
  }

  private static Path codeSource(Class<?> type) throws Exception {
    return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
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
      verify();
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

  private static void expect(long actual, long expected, String metric) {
    require(actual == expected, "aggregate_mismatch_" + metric);
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

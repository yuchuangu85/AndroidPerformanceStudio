import com.android.tools.perflib.heap.*;
import com.android.tools.perflib.heap.io.InMemoryBuffer;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.util.*;

/** Read-only extraction from Studio's bundled perflib, not a replacement parser. */
public final class StudioProbe {
  static String id(long n) { return Long.toUnsignedString(n); }
  public static void main(String[] args) throws Exception {
    Snapshot s = Snapshot.createSnapshot(new InMemoryBuffer(new File(args[0])));
    s.computeRetainedSizes();
    List<Object> classes = new ArrayList<>(), roots = new ArrayList<>(), objects = new ArrayList<>();
    for (Heap owner : s.getHeaps()) for (ClassObj c : owner.getClasses()) {
      for (Heap h : s.getHeaps()) {
        List<Instance> members = c.getHeapInstances(h.getId());
        if (members == null || members.isEmpty()) continue;
        long shallow = 0, retained = 0;
        for (Instance i : members) { shallow += i.getSize(); retained += i.getTotalRetainedSize(); }
        classes.add(Map.of("classId", id(c.getId()), "className", c.getClassName(), "heap", h.getName(),
            "count", members.size(), "shallow", shallow, "retainedSum", retained));
      }
    }
    for (RootObj r : s.getGcRoots()) {
      Map<String,Object> row = new TreeMap<>();
      row.put("id", id(r.getId())); row.put("kind", r.getRootType().name());
      row.put("tag", r.getRootType().getType()); row.put("strength", null); roots.add(row);
    }
    List<String> selected = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
    if (selected.isEmpty()) {
      for (Heap h : s.getHeaps()) {
        TreeMap<Long, Instance> candidates = new TreeMap<>(Long::compareUnsigned);
        Instance[] deepest = new Instance[1];
        h.forEachInstance(i -> {
          candidates.put(i.getId(), i);
          if (candidates.size() > 2) candidates.pollLastEntry();
          if (i.isReachable() && (deepest[0] == null || i.getDistanceToGcRoot() > deepest[0].getDistanceToGcRoot()
              || (i.getDistanceToGcRoot() == deepest[0].getDistanceToGcRoot()
                  && Long.compareUnsigned(i.getId(), deepest[0].getId()) < 0))) deepest[0] = i;
          return true;
        });
        for (long value : candidates.keySet()) selected.add(id(value));
        if (deepest[0] != null && !selected.contains(id(deepest[0].getId()))) selected.add(id(deepest[0].getId()));
      }
    }
    for (String selectedId : selected) {
      Instance i = s.findInstance(Long.parseUnsignedLong(selectedId));
      if (i == null) throw new IllegalArgumentException("Missing object " + selectedId);
      Map<String,Object> row = new TreeMap<>();
      row.put("id", id(i.getId())); row.put("heap", i.getHeap().getName());
      row.put("classId", i.getClassObj() == null ? null : id(i.getClassObj().getId()));
      row.put("shallow", i.getSize()); row.put("retained", i.getTotalRetainedSize());
      row.put("native", i.getNativeSize()); row.put("depth", i.isReachable() ? i.getDistanceToGcRoot() : null);
      List<String> chain = new ArrayList<>(); Set<Long> seen = new HashSet<>(); Instance current = i;
      while (current != null && current.isReachable() && seen.add(current.getId())) {
        chain.add(id(current.getId()));
        if (current.getDistanceToGcRoot() == 0) break;
        Instance next = null; Iterator<Instance> refs = current.getHardReverseReferences().iterator();
        while (refs.hasNext()) {
          Instance c = refs.next();
          if (c.getDistanceToGcRoot() == current.getDistanceToGcRoot() - 1
              && (next == null || Long.compareUnsigned(c.getId(), next.getId()) < 0)) next = c;
        }
        current = next;
      }
      row.put("rootChain", chain.isEmpty() ? null : chain);
      row.put("rootChainComplete", current != null && current.getDistanceToGcRoot() == 0); objects.add(row);
    }
    Map<String,Object> result = new TreeMap<>();
    result.put("analyzer", "Studio bundled perflib Snapshot: legacy profiler HPROF load path");
    result.put("classes", classes); result.put("roots", roots); result.put("objects", objects);
    result.put("limitations", List.of("Root strength unavailable: null is not strong or weak.",
        "Hard-reference shortest root paths do not reproduce UI tie-breaking.",
        "Histogram excludes class objects; retainedSum sums overlapping per-object retained sizes."));
    System.out.println(new GsonBuilder().serializeNulls().create().toJson(result)); s.dispose();
  }
}

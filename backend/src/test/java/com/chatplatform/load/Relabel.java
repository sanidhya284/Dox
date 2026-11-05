package com.chatplatform.load;

import com.chatplatform.load.RunClassifier.Observations;
import com.chatplatform.load.RunClassifier.Verdict;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-derives the outcome of stored run summaries (docs/benchmarks/*.json) from their RECORDED fields with
 * the current {@link RunClassifier}. Nothing is re-run. Prints one JSON line per file:
 * {"file", "outcome", "reasons", "notes"}; deploy/bench/relabel.py writes them back and keeps the original
 * label as "labelAtRun". Recorded percentiles are rounded to 0.01 ms, which the rules' margins absorb.
 *
 * <pre>./gradlew benchRelabel -Prelabel.dir=../docs/benchmarks</pre>
 */
public final class Relabel {

    private Relabel() {
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        File[] files = new File(args[0]).listFiles((d, n) -> n.endsWith(".json"));
        Arrays.sort(files);
        for (File f : files) {
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = mapper.readValue(f, Map.class);
            Verdict v = classify(summary);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("file", f.getName());
            line.put("outcome", v.outcome().name());
            line.put("reasons", v.reasons());
            line.put("notes", v.notes());
            System.out.println(mapper.writeValueAsString(line));
        }
    }

    static Verdict classify(Map<String, Object> summary) {
        Map<String, Object> params = map(summary, "params");
        return new RunClassifier(num(params.get("lagThresholdMs"))).classify(observations(summary));
    }

    static Observations observations(Map<String, Object> s) {
        Map<String, Object> params = map(s, "params");
        Map<String, Object> sends = map(s, "sends");
        Map<String, Object> latency = map(s, "latencyMs");
        Map<String, Object> lag = map(latency, "scheduleLag");
        Map<String, Object> deltas = map(s, "serverCounterDeltas");
        Map<String, Object> inv = map(s, "invariants");
        @SuppressWarnings("unchecked")
        List<Object> thirds = (List<Object>) latency.get("ackP50ByWindowThird");
        double windowSeconds = Duration.parse((String) params.get("window")).toMillis() / 1000.0;
        double stored = deltas.containsKey("messagesCreated") ? num(deltas.get("messagesCreated")) / windowSeconds
                : Double.NaN;
        return new Observations(num(params.get("targetRate")), num(sends.get("achievedRate")), stored,
                num(lag.get("p99")), (long) num(lag.get("count")), num(s.get("harnessGcShare")),
                (int) num(map(s, "server").get("hikariPendingSamples")), (long) num(sends.get("resends")),
                (long) num(sends.get("outboxRemaining")), num(thirds.get(0)), num(thirds.get(2)),
                num(map(latency, "sendToAck").get("p99")),
                Boolean.TRUE.equals(inv.get("rowsMatchAcked")), Boolean.TRUE.equals(inv.get("seqContiguous")));
    }

    /** A recorded number, or a percentile string: "12.34", or "n/a (n=300 < 1000)", which becomes NaN. */
    static double num(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }
}

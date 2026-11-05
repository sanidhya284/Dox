package com.chatplatform.load;

import java.util.ArrayList;
import java.util.List;

/** Minimal reader for the Prometheus text format: enough to sum a metric's series, optionally by label. */
final class PrometheusText {

    private record Sample(String name, String labels, double value) {
    }

    private final List<Sample> samples;

    private PrometheusText(List<Sample> samples) {
        this.samples = samples;
    }

    static PrometheusText parse(String text) {
        List<Sample> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int brace = line.indexOf('{');
            int space = line.lastIndexOf(' ');
            if (space < 0) {
                continue;
            }
            String name;
            String labels = "";
            if (brace >= 0 && brace < space) {
                name = line.substring(0, brace);
                labels = line.substring(brace + 1, line.lastIndexOf('}'));
            } else {
                name = line.substring(0, line.indexOf(' '));
            }
            try {
                out.add(new Sample(name, labels, Double.parseDouble(line.substring(space + 1))));
            } catch (NumberFormatException ignored) {
                // a timestamp or an unexpected value: skip the line
            }
        }
        return new PrometheusText(out);
    }

    /** Sum of every series of {@code name} whose labels contain all {@code labelMatches}; NaN if none. */
    double sum(String name, String... labelMatches) {
        double total = 0;
        boolean found = false;
        outer:
        for (Sample s : samples) {
            if (!s.name().equals(name)) {
                continue;
            }
            for (String m : labelMatches) {
                if (!s.labels().contains(m)) {
                    continue outer;
                }
            }
            total += s.value();
            found = true;
        }
        return found ? total : Double.NaN;
    }
}

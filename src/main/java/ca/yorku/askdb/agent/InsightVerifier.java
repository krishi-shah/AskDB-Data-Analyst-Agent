package ca.yorku.askdb.agent;

import ca.yorku.askdb.data.QueryResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Matches every number in the summary to the result within a rounding tolerance. */
public class InsightVerifier {
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])[-+]?\\$?\\d[\\d,]*(?:\\.\\d+)?%?");

    /** A number found in text: the original text, its value, decimals, and whether it ends in %. */
    public record FoundNumber(String raw, double value, int decimals, boolean percent) {
    }

    public static List<FoundNumber> extractNumbers(String text) {
        List<FoundNumber> found = new ArrayList<>();
        Matcher m = NUMBER.matcher(text == null ? "" : text);
        while (m.find()) {
            String raw = m.group();
            String cleaned = raw.replace(",", "").replace("$", "").replaceAll("%$", "");
            double value;
            try {
                value = Double.parseDouble(cleaned);
            } catch (NumberFormatException e) {
                continue;
            }
            int dot = cleaned.indexOf('.');
            found.add(new FoundNumber(raw, value, dot < 0 ? 0 : cleaned.length() - dot - 1, raw.endsWith("%")));
        }
        return found;
    }

    public VerificationReport verify(Insight insight, QueryResult result, String question) {
        List<Double> plain = new ArrayList<>();
        List<Double> percent = new ArrayList<>();
        candidates(result, plain, percent);
        Set<Double> allowed = new HashSet<>();
        extractNumbers(question).forEach(n -> allowed.add(n.value()));
        List<String> checked = new ArrayList<>();
        List<String> unverified = new ArrayList<>();
        for (FoundNumber n : extractNumbers(insight.text())) {
            double value = n.value();
            if (allowed.contains(value) || isOrdinal(value, result) || isYear(value)) {
                checked.add(n.raw());
                continue;
            }
            double tol = Math.max(Math.abs(value) * 0.005, 0.5 * Math.pow(10, -n.decimals()));
            List<Double> pool = new ArrayList<>(plain);
            if (n.percent()) {
                pool.addAll(0, percent);
            }
            boolean match = pool.stream().anyMatch(c -> Math.abs(Math.abs(value) - Math.abs(c)) <= tol);
            (match ? checked : unverified).add(n.raw());
        }
        return new VerificationReport(unverified.isEmpty(), checked, unverified);
    }

    private static boolean isOrdinal(double value, QueryResult result) {
        return value == Math.rint(value) && value >= 0 && value <= Math.max(result.rowCount(), 10);
    }

    private static boolean isYear(double value) {
        return value == Math.rint(value) && value >= 1900 && value <= LocalDate.now().getYear() + 5;
    }

    private static void candidates(QueryResult result, List<Double> plain, List<Double> percent) {
        plain.add((double) result.rowCount());
        for (List<Object> row : result.getRows()) {
            for (Object cell : row) {
                if (cell instanceof Number num) {
                    plain.add(num.doubleValue());
                } else if (cell instanceof String s) {
                    extractNumbers(s).forEach(n -> plain.add(n.value()));
                }
            }
        }
        for (int i : result.numericColumns()) {
            List<Double> values = new ArrayList<>();
            for (Object v : result.columnValues(i)) {
                if (v instanceof Number num) {
                    values.add(num.doubleValue());
                }
            }
            if (values.isEmpty()) {
                continue;
            }
            double total = values.stream().mapToDouble(Double::doubleValue).sum();
            double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            plain.addAll(List.of(total, total / values.size(), min, max, max - min));
            if (total != 0) {
                for (double v : values) {
                    percent.add(v / total * 100);
                }
            }
            List<Double> head = values.subList(0, Math.min(50, values.size()));
            for (double a : head) {
                for (double b : head) {
                    if (a != b) {
                        plain.add(a - b);
                        if (a != 0) {
                            percent.add((b - a) / Math.abs(a) * 100);
                            plain.add(b / a);
                        }
                    }
                }
            }
        }
    }
}

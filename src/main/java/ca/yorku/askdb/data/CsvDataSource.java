package ca.yorku.askdb.data;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/** Adapter: CSV files become tables of an in-memory SQLite database. */
public class CsvDataSource extends DataSource {
    private final List<Path> paths;
    private final Map<String, Path> tableNames = new LinkedHashMap<>();

    public CsvDataSource(List<Path> paths) {
        this.paths = List.copyOf(paths);
    }

    public List<Path> getPaths() {
        return paths;
    }

    public Map<String, Path> getTableNames() {
        return tableNames;
    }

    @Override
    public String label() {
        return paths.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(", "));
    }

    @Override
    public void connect() {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
            Set<String> used = new HashSet<>();
            for (Path path : paths) {
                Table table = read(path);
                String base = tableName(path);
                String name = base;
                int suffix = 2;
                while (used.contains(name)) {
                    name = base + "_" + suffix++;
                }
                used.add(name);
                load(c, name, table);
                tableNames.put(name, path);
            }
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA query_only = ON");
            }
            conn = c;
        } catch (SQLException e) {
            throw new DatasetException.ImportFailed("The CSV data could not be loaded: " + cleanMessage(e), e);
        }
    }

    static String tableName(Path path) {
        String stem = path.getFileName().toString().replaceFirst("\\.[^.]*$", "");
        String name = stem.replaceAll("\\W+", "_").replaceAll("^_+|_+$", "").toLowerCase();
        if (name.isEmpty()) {
            name = "table";
        }
        return Character.isDigit(name.charAt(0)) ? "t_" + name : name;
    }

    private record Table(List<String> columns, List<String> types, List<List<Object>> rows) {
    }

    private static Table read(Path path) {
        String file = path.getFileName().toString();
        List<CSVRecord> records;
        List<String> header;
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader reader = new InputStreamReader(Files.newInputStream(path), decoder);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                     .setAllowMissingColumnNames(true).setIgnoreSurroundingSpaces(true).build().parse(reader)) {
            header = parser.getHeaderNames();
            records = parser.getRecords();
        } catch (NoSuchFileException e) {
            throw new DatasetException.ImportFailed(file + " was not found.", e);
        } catch (CharacterCodingException e) {
            throw new DatasetException.ImportFailed(file + " is not a UTF-8 text file.", e);
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            if (e.getCause() instanceof CharacterCodingException) {
                throw new DatasetException.ImportFailed(file + " is not a UTF-8 text file.", e);
            }
            throw new DatasetException.ImportFailed(file + " could not be read: " + e.getMessage(), e);
        }
        if (header.isEmpty()) {
            throw new DatasetException.ImportFailed(file + " is empty.");
        }
        if (header.stream().anyMatch(h -> h == null || h.isBlank())) {
            throw new DatasetException.ImportFailed(file + " has no header row or has blank column names.");
        }
        List<String> columns = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String clean = header.get(i).replaceAll("\\W+", "_").replaceAll("^_+|_+$", "");
            columns.add(clean.isEmpty() ? "col" + i : clean);
        }
        List<List<String>> raw = new ArrayList<>();
        for (CSVRecord rec : records) {
            if (rec.size() != columns.size()) {
                throw new DatasetException.ImportFailed(file + " has inconsistent rows: line "
                        + rec.getRecordNumber() + " has " + rec.size() + " fields, expected " + columns.size() + ".");
            }
            List<String> cells = new ArrayList<>();
            rec.forEach(cells::add);
            raw.add(cells);
        }
        List<String> types = new ArrayList<>();
        for (int c = 0; c < columns.size(); c++) {
            types.add(inferType(raw, c));
        }
        List<List<Object>> rows = new ArrayList<>(raw.size());
        for (List<String> cells : raw) {
            List<Object> row = new ArrayList<>(cells.size());
            for (int c = 0; c < cells.size(); c++) {
                row.add(convert(cells.get(c), types.get(c)));
            }
            rows.add(row);
        }
        return new Table(columns, types, rows);
    }

    /** A column is numeric only when every non-empty value parses as a number. */
    private static String inferType(List<List<String>> raw, int c) {
        boolean any = false;
        boolean allLong = true;
        boolean allDouble = true;
        for (List<String> row : raw) {
            String v = row.get(c);
            if (v.isEmpty()) {
                continue;
            }
            any = true;
            if (allLong && !v.matches("[-+]?\\d{1,18}")) {
                allLong = false;
            }
            if (!allLong && allDouble) {
                try {
                    Double.parseDouble(v);
                } catch (NumberFormatException e) {
                    allDouble = false;
                }
            }
            if (!allDouble) {
                break;
            }
        }
        if (!any) {
            return "TEXT";
        }
        return allLong ? "INTEGER" : allDouble ? "REAL" : "TEXT";
    }

    private static Object convert(String v, String type) {
        if (v.isEmpty()) {
            return null;
        }
        return switch (type) {
            case "INTEGER" -> Long.parseLong(v.startsWith("+") ? v.substring(1) : v);
            case "REAL" -> Double.parseDouble(v);
            default -> v;
        };
    }

    private static void load(Connection c, String name, Table table) throws SQLException {
        List<String> defs = new ArrayList<>();
        for (int i = 0; i < table.columns().size(); i++) {
            defs.add(quote(table.columns().get(i)) + " " + table.types().get(i));
        }
        String cols = String.join(", ", defs);
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE " + quote(name) + " (" + cols + ")");
        }
        String marks = table.columns().stream().map(x -> "?").collect(Collectors.joining(", "));
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + quote(name) + " VALUES (" + marks + ")")) {
            for (List<Object> row : table.rows()) {
                for (int i = 0; i < row.size(); i++) {
                    ps.setObject(i + 1, row.get(i));
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
        c.commit();
        c.setAutoCommit(true);
    }

    static String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}

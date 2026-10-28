package ca.yorku.askdb.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Factory: picks the DataSource class from the file extensions. */
public class DataSourceFactory {
    static final Set<String> SQLITE_EXTENSIONS = Set.of(".db", ".sqlite", ".sqlite3");
    static final Set<String> CSV_EXTENSIONS = Set.of(".csv");

    private final int maxFileMb;

    public DataSourceFactory(int maxFileMb) {
        this.maxFileMb = maxFileMb;
    }

    public DataSource create(List<Path> files) {
        if (files == null || files.isEmpty()) {
            throw new DatasetException.UnsupportedFile("Choose a SQLite file or one or more CSV files.");
        }
        for (Path path : files) {
            if (Files.isRegularFile(path)) {
                double sizeMb;
                try {
                    sizeMb = Files.size(path) / (1024.0 * 1024.0);
                } catch (IOException e) {
                    continue;
                }
                if (sizeMb > maxFileMb) {
                    throw new DatasetException.FileTooLarge(String.format(Locale.ROOT,
                            "%s is %.1f MB; the limit is %d MB.", path.getFileName(), sizeMb, maxFileMb));
                }
            }
        }
        Set<String> extensions = files.stream().map(DataSourceFactory::extension).collect(Collectors.toSet());
        if (SQLITE_EXTENSIONS.containsAll(extensions)) {
            if (files.size() != 1) {
                throw new DatasetException.UnsupportedFile("Import one SQLite database at a time.");
            }
            return new SQLiteDataSource(files.get(0));
        }
        if (CSV_EXTENSIONS.containsAll(extensions)) {
            return new CsvDataSource(files);
        }
        Set<String> supported = new TreeSet<>(SQLITE_EXTENSIONS);
        supported.addAll(CSV_EXTENSIONS);
        throw new DatasetException.UnsupportedFile("Unsupported file type. Supported types: "
                + String.join(", ", supported) + ".");
    }

    static String extension(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }
}

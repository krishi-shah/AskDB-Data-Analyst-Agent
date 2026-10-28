package ca.yorku.askdb.data;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import org.sqlite.SQLiteConfig;

/** A SQLite file opened read-only, so no statement can change it. */
public class SQLiteDataSource extends DataSource {
    private static final byte[] SQLITE_HEADER = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    private final Path path;

    public SQLiteDataSource(Path path) {
        this.path = path;
    }

    public Path getPath() {
        return path;
    }

    @Override
    public String label() {
        return path.getFileName().toString();
    }

    @Override
    public void connect() {
        byte[] header = new byte[SQLITE_HEADER.length];
        try (InputStream in = Files.newInputStream(path)) {
            int read = in.readNBytes(header, 0, header.length);
            if (read != header.length || !Arrays.equals(header, SQLITE_HEADER)) {
                throw new DatasetException.ImportFailed(label() + " is not a valid SQLite database.");
            }
        } catch (IOException e) {
            throw new DatasetException.ImportFailed("Cannot open " + label() + ": " + e.getMessage(), e);
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try {
            Connection c = config.createConnection("jdbc:sqlite:" + path.toAbsolutePath());
            try (Statement st = c.createStatement()) {
                st.executeQuery("SELECT count(*) FROM sqlite_master").close();
            }
            conn = c;
        } catch (SQLException e) {
            throw new DatasetException.ImportFailed(label() + " could not be read: " + cleanMessage(e), e);
        }
    }
}

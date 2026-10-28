package ca.yorku.askdb.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataSourceFactoryTest {
    private static final Path DATA = Path.of("data");
    private final DataSourceFactory factory = new DataSourceFactory(200);

    @TempDir
    Path dir;

    @Test
    void sqliteExtensionGivesSqliteSource() {
        assertInstanceOf(SQLiteDataSource.class, factory.create(List.of(DATA.resolve("sample_store.db"))));
    }

    @Test
    void csvFilesGiveCsvSource() {
        DataSource source = factory.create(List.of(DATA.resolve("csv/customers.csv"), DATA.resolve("csv/orders.csv")));
        assertInstanceOf(CsvDataSource.class, source);
        source.connect();
        assertEquals(List.of("customers", "orders"), source.listTables());
        source.close();
    }

    @Test
    void unsupportedExtensionRejected() {
        assertThrows(DatasetException.UnsupportedFile.class, () -> factory.create(List.of(dir.resolve("notes.txt"))));
    }

    @Test
    void fileOverLimitRejected() throws IOException {
        Path big = dir.resolve("big.csv");
        Files.writeString(big, "a\n" + "1\n".repeat(600_000));
        assertThrows(DatasetException.FileTooLarge.class, () -> new DataSourceFactory(1).create(List.of(big)));
    }

    @Test
    void sqliteSourceIsReadOnly() {
        DataSource source = factory.create(List.of(DATA.resolve("sample_store.db")));
        source.connect();
        assertThrows(SQLException.class, () -> {
            try (Statement st = source.connection().createStatement()) {
                st.executeUpdate("DELETE FROM orders");
            }
        });
        source.close();
    }

    @Test
    void fakeSqliteFileRejected() throws IOException {
        Path fake = dir.resolve("fake.db");
        Files.writeString(fake, "not a database");
        DataSource source = factory.create(List.of(fake));
        assertThrows(DatasetException.ImportFailed.class, source::connect);
        source.close();
    }
}

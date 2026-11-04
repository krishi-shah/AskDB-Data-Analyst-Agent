package ca.yorku.askdb.presentation.gui;

import static org.junit.jupiter.api.Assertions.assertSame;

import ca.yorku.askdb.MockLLMProvider;
import ca.yorku.askdb.NoEmbeddings;
import ca.yorku.askdb.app.AskDBFacade;
import ca.yorku.askdb.config.Settings;
import ca.yorku.askdb.data.QueryResult;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChartSourceTest {
    @TempDir
    Path home;

    private AskDBFacade facade;
    private MainWindow window;

    @AfterEach
    void tearDown() {
        if (window != null) {
            window.dispose();
        }
        if (facade != null) {
            facade.close();
        }
    }

    @Test
    void chartChangeUsesTheTableOnScreen() {
        Settings settings = new Settings();
        settings.home = home;
        facade = new AskDBFacade(settings, new MockLLMProvider(), new NoEmbeddings());
        window = new MainWindow(facade);
        QueryResult shown = new QueryResult(List.of("region"), List.of(List.of("North")), false, 1, "SELECT 1");
        window.showPartialResult(shown, "SELECT 1", null);
        assertSame(shown, window.getController().resultForChart());
    }
}

package ca.yorku.askdb.presentation.gui;

import ca.yorku.askdb.app.AskDBFacade;
import ca.yorku.askdb.config.Settings;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Manual check, not a unit test: opens the real window with the real local model, asks questions through
 * GuiController, and saves each tab as a PNG. Usage: GuiScreenshots HOME_DIR OUT_DIR DATASET QUESTION...
 */
public final class GuiScreenshots {
    private GuiScreenshots() {
    }

    public static void main(String[] args) throws Exception {
        Settings settings = Settings.load();
        settings.home = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        AtomicReference<MainWindow> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // default look and feel
            }
            MainWindow w = new MainWindow(new AskDBFacade(settings));
            w.setVisible(true);
            ref.set(w);
        });
        MainWindow window = ref.get();
        GuiController controller = window.getController();
        SwingUtilities.invokeAndWait(() -> controller.onImportClicked(List.of(Path.of(args[2])), true));
        waitIdle(controller);
        capture(window, 1, out.resolve("gui_schema.png"));
        for (int i = 3; i < args.length; i++) {
            String question = args[i];
            SwingUtilities.invokeAndWait(() -> window.focusAsk(question));
            SwingUtilities.invokeAndWait(() -> controller.onAskClicked(question));
            waitIdle(controller);
            capture(window, 0, out.resolve("gui_ask_" + (i - 2) + ".png"));
        }
        capture(window, 2, out.resolve("gui_history.png"));
        capture(window, 3, out.resolve("gui_usage.png"));
        SwingUtilities.invokeAndWait(window::dispose);
        System.exit(0);
    }

    private static void waitIdle(GuiController controller) throws Exception {
        Thread.sleep(300);
        AtomicReference<Boolean> busy = new AtomicReference<>(true);
        while (busy.get()) {
            Thread.sleep(500);
            SwingUtilities.invokeAndWait(() -> busy.set(controller.isBusy()));
        }
        Thread.sleep(800);
    }

    private static void capture(MainWindow window, int tab, Path file) throws Exception {
        SwingUtilities.invokeAndWait(() -> window.getTabs().setSelectedIndex(tab));
        Thread.sleep(1200);
        SwingUtilities.invokeAndWait(() -> {
            BufferedImage img = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            window.paintAll(g);
            g.dispose();
            try {
                ImageIO.write(img, "png", file.toFile());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        System.out.println("saved " + file);
    }
}

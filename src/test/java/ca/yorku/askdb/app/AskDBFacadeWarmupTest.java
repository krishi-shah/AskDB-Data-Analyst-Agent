package ca.yorku.askdb.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.askdb.MockLLMProvider;
import ca.yorku.askdb.NoEmbeddings;
import ca.yorku.askdb.config.Settings;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AskDBFacadeWarmupTest {
    @TempDir
    Path home;

    @Test
    void constructorDoesNotWaitForTheModelToLoad() throws Exception {
        HangingProvider provider = new HangingProvider();
        Settings settings = new Settings();
        settings.home = home;
        long start = System.nanoTime();
        AskDBFacade facade = new AskDBFacade(settings, provider, new NoEmbeddings());
        long ms = (System.nanoTime() - start) / 1_000_000;
        try {
            assertTrue(ms < 1000, "opening AskDB waited " + ms + " ms for the model");
            assertTrue(provider.entered.await(1, TimeUnit.SECONDS), "warmup never started");
        } finally {
            provider.release.countDown();
            facade.close();
        }
    }

    private static final class HangingProvider extends MockLLMProvider {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void warmup() {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

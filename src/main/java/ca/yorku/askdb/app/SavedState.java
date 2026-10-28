package ca.yorku.askdb.app;

import ca.yorku.askdb.agent.ConversationMemory;
import ca.yorku.askdb.config.Settings;
import ca.yorku.askdb.util.Json;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * State kept between separate askdb commands and shared with the GUI: the loaded dataset, the conversation
 * memory, the routing policy, and the sample-sharing setting. Stored in ~/.askdb/state.json.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SavedState {
    public List<String> dataset = new ArrayList<>();
    public List<ConversationMemory.Turn> memory = new ArrayList<>();
    public String policy;
    public Boolean shareSamples;

    public static SavedState load(Settings settings) {
        Path file = settings.stateFile();
        if (!Files.isRegularFile(file)) {
            return new SavedState();
        }
        try {
            return Json.MAPPER.readValue(file.toFile(), SavedState.class);
        } catch (IOException e) {
            return new SavedState();
        }
    }

    public void save(Settings settings) {
        settings.ensureHome();
        try {
            Files.writeString(settings.stateFile(), Json.pretty(this));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + settings.stateFile() + ": " + e.getMessage(), e);
        }
    }

    /** Applies the saved policy and sample setting to the facade. */
    public void applySettings(AskDBFacade facade) {
        if (policy != null) {
            facade.setRoutingPolicy(policy);
        }
        if (shareSamples != null) {
            facade.setShareSamples(shareSamples);
        }
    }
}

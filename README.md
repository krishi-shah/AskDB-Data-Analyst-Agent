# EECS 3311 Course Project — AskDB

AskDB is a natural-language data analyst written in Java. You import a SQLite database or CSV files, ask a question in plain English, and the agent plans a read-only query, checks it, runs it, and shows the SQL, the result, a chart, and a short summary whose numbers are checked against that result.

AskDB has a desktop GUI (Swing) and a command line (`askdb`, built with picocli). Both call the same `AskDBFacade`, so every feature works the same way in both.

- **Stage 1 design report:** [docs/Stage1_Design_Report.md](docs/Stage1_Design_Report.md)

**Team:** Krishi Rajeshkumar Shah (220968905)

## Prerequisites

- **Java 21 (JDK).** Any JDK 21 works, for example [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21). Maven is not needed: the Maven wrapper (`mvnw.cmd` / `mvnw`) downloads it on first use.
- **Ollama**, from [ollama.com/download](https://ollama.com/download). This is the free local model server. Once it is installed, pull the default model:

  ```
  ollama pull qwen2.5-coder:7b
  ```

  On a computer with less than 8 GB of memory, `qwen2.5-coder:3b` is smaller and faster. Set `OLLAMA_MODEL=qwen2.5-coder:3b` in `.env` to use it. In testing, the 3B model did not ask clarifying questions; it chose a meaning and answered.
- **Optional:** `ollama pull nomic-embed-text` gives semantic schema search. Without it, AskDB uses a built-in offline embedder (hashed words and character trigrams), and keyword search if that fails.

No paid service or credit card is needed. Cloud models are optional.

## Build

From the project folder:

```
mvnw.cmd -q package             # Windows
./mvnw -q package               # macOS / Linux
```

This compiles the code, runs the checks, and writes `target/askdb.jar` (one jar with all libraries). Add `-DskipTests` to skip the checks.

## Configuration

Copy the template and edit the copy:

```
copy .env.example .env          # Windows
cp .env.example .env            # macOS / Linux
```

`.env` is read from the folder AskDB is started from. It is listed in `.gitignore` and must never be committed. The settings are:

| Setting | Meaning |
|---|---|
| `OLLAMA_URL`, `OLLAMA_MODEL` | Local model server and model (default `qwen2.5-coder:7b`) |
| `GEMINI_API_KEY`, `GEMINI_MODEL` | Optional free-tier fast model. Leave the placeholder to skip it. |
| `GROQ_API_KEY`, `GROQ_MODEL` | Optional free-tier strong model. Leave the placeholder to skip it. |
| `ASKDB_POLICY` | `local_only` (default), `cheap_first`, or `strong_only` |
| `ASKDB_HOME` | Folder for history, saved questions, descriptions, and usage. Default `~/.askdb` |

Without cloud keys, every policy uses the local model. Local Only never sends data off the computer.

## Launch

The Ollama app must be running.

- **GUI:** `askdb-gui.cmd` on Windows, or `java -cp target/askdb.jar ca.yorku.askdb.presentation.gui.MainWindow` (also `askdb gui`)
- **CLI:** `askdb.cmd --help` on Windows, or `java -jar target/askdb.jar --help`

The examples below write `askdb`; on Windows that is `askdb.cmd` (or just `askdb` from the project folder).

## Sample data

`data/sample_store.db` is a synthetic store with `customers`, `products`, `orders`, and `order_items` covering 2024 and 2025. `data/csv/customers.csv` and `data/csv/orders.csv` contain the same data as CSV. All names and numbers were generated from a fixed seed by a script that is kept in the project history (commit `00c21a3`). No real people or companies are included.

## Examples (CLI)

```
askdb import data/sample_store.db                 # F01, F02: load read-only and describe the schema
askdb schema orders                               # F02: show one table
askdb describe orders.status "Order state: delivered, shipped or cancelled"   # F02: edit a description
askdb ask "Which product category had the highest revenue in 2025?" --chart   # F03, F08, F09
askdb ask "now show it for every category in 2024" --continue                 # F10: follow-up
askdb ask "Who are our best customers?"           # F04: asks what "best" means
askdb ask "Delete all cancelled orders"           # F05: refused, data is read-only
askdb ask "Monthly revenue in 2025" --trace       # F06, F12: every model call and tool result
askdb sql "SELECT region, COUNT(*) FROM customers GROUP BY region" --chart bar  # F07: no model
askdb history                                     # F11
askdb save 3 "category revenue"                   # F11: name a history entry
askdb rerun "category revenue"                    # F11: rerun the stored SQL without the model
askdb config --policy cheap_first                 # F12: change routing (falls back to local without keys)
askdb usage                                       # F12: calls, tokens, latency, cost
askdb shell                                       # interactive session with follow-ups
```

Charts saved with `--chart` go to the `out/` folder. `--chart` alone keeps the recommended type; `--chart pie` (or `bar`, `line`, `scatter`, `table`) asks for one.

## Examples (GUI)

1. Click **Import data...** and choose `data/sample_store.db`. The Schema tab shows tables and descriptions; double-click a description to edit it.
2. On the **Ask** tab, type a question and press **Ask**. Agent steps appear live on the left. The answer, the verified badge, the chart, and the result table appear on the right.
3. Edit the SQL on the left and press **Run SQL** to run it without the model. Use the **Chart** list to change the chart type and **Save Image...** to export it.
4. If the question is ambiguous, a box shows the possible meanings. Click one, or type your own.
5. **New Conversation** clears the follow-up memory.
6. The **History** tab searches past questions. Use **Rerun SQL**, **Ask Again**, and **Save As...** there; double-click a saved question to run it.
7. **Settings** chooses the routing policy and whether sample rows may be sent to the model. The **Usage and Trace** tab shows calls, tokens, latency, cost, and the full step trace.

## Basic checks

```
mvnw.cmd test                   # Windows
./mvnw test                     # macOS / Linux
```

The JUnit 5 checks cover the SQL guard, the data source factory, chart selection, the number verifier, and orchestrator runs with a scripted `MockLLMProvider` (in `src/test/java` only, never in the application). Full testing is Stage 3.

## Project layout

Package `ca.yorku.askdb`, in `src/main/java/ca/yorku/askdb/`:

```
presentation/gui    Swing window, controller (SwingWorker), settings dialog, trace view
presentation/cli    picocli commands and the console progress printer
app                 AskDBFacade, Session, UsageTracker, SavedState
agent               orchestrator, planner, prompt builder, parser, memory, insight generator and verifier
agent/tools         search_schema, sample_rows, column_values, run_query, make_chart, ask_user
llm                 provider adapters (Ollama, Gemini, Groq), factory, router, routing policies
retrieval           embedding providers, schema index, schema describer
data                data sources (SQLite, CSV), factory, schema reader, query executor
safety              SQL rule decorators and validator
charts              chart products, factory, recommender, JFreeChart rendering
storage             history, saved questions, descriptions (application database)
config              settings from .env and the environment
```

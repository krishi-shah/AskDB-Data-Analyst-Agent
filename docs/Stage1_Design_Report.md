# AskDB: A Natural Language Data Analyst Agent

**EECS 3311 Fall 2026 · Course Project · Stage 1 Design Report**

**Team members:** Krishi Rajeshkumar Shah (220968905)
**Repository:** https://github.com/krishi-shah/AskDB-Data-Analyst-Agent

This report contains the complete Stage 1 design: project overview, feature specifications, UML class diagram, design pattern explanations, use case diagram and descriptions, sequence diagrams, the feature to design traceability table, and an explanation of how every feature is realized. All diagrams are drawn in UMLet. The diagram files are in diagrams/umlet, and the pictures in this report are exported from those files.

## Stage 1 deliverable checklist

| Stage 1 requirement | Where it is in this report |
|---|---|
| Project overview: problem, users, agent, models, architecture | Sections 1.1–1.7 |
| Detailed feature specifications (at least 10) | Section 2, F01–F12 |
| UML class diagram | Section 3 |
| Design pattern explanations (at least 5) | Section 4 |
| Use-case diagram | Section 5 |
| Detailed use-case descriptions | Section 6 |
| Sequence diagrams | Section 7 |
| Feature-to-design traceability table | Section 8 |
| Feature implementation explanations | Section 9 |

# 1. Project Overview

## 1.1 Problem and Motivation

A huge amount of useful information lives in databases and spreadsheets: sales records, course enrollment data, inventory, survey results, club membership lists. Getting answers out of that data usually requires SQL. People who do not know SQL either wait for someone who does or give up on the question. People who do know SQL spend a lot of time writing small, repetitive, ad hoc queries.

General purpose chatbots can write SQL, but they are not a real solution. They cannot see the actual schema, so they guess table and column names. They cannot run the query, so they cannot notice when it fails or returns nothing. They cannot check their own claims, so they sometimes report numbers that were never computed. They also offer no protection against destructive statements such as `DELETE` or `DROP`.

AskDB solves this by placing an AI agent inside a carefully designed software system. The agent can look at the schema, run safe queries, observe results, correct its own mistakes, and explain the answer, while deterministic components make sure the database is never modified and every number shown to the user actually came from the data.

## 1.2 Target Users

| User group | What they need |
|---|---|
| Business users and small business owners | Answers such as "which products sold best last quarter" without learning SQL |
| Students and researchers | Fast exploration of course, lab, or public datasets delivered as CSV or SQLite files |
| Junior data analysts | A faster way to draft, check, and visualize queries, with the SQL always visible and editable |

## 1.3 What the Agent Can Do

Given a question in plain English, the AskDB agent can:

* retrieve the tables and columns that are relevant to the question using semantic search over the schema;
* create a short plan for answering the question;
* decide which tool to call next (search the schema, look at sample rows, check the distinct values of a column, run a query, make a chart, or ask the user);
* write SQL, have it validated by a safety guard, run it, and read the result;
* repair the query when it fails or returns a suspicious result;
* ask a clarifying question when the request can be understood in more than one way;
* choose an appropriate chart for the result;
* write a short summary whose numbers are verified against the actual result;
* remember the recent conversation so the user can ask follow up questions.

## 1.4 Why an AI Agent Is Appropriate

Answering a data question is a multistep task with feedback from the environment. The correct SQL depends on the real schema, the real values stored in each column (for example `"ON"` versus `"Ontario"`), and the result of the previous attempt. A single prompt sent to an LLM cannot see any of this and cannot correct itself.

An agent loop fits naturally: the model reasons about the question, chooses a tool, observes the result, and decides what to do next. The decisions involved (which tables matter, whether the question is ambiguous, whether a result is plausible, whether to retry, which chart fits) are exactly the kind of judgement LLMs are good at. The parts that must never be wrong (query safety, execution, number checking, and persistence) are kept deterministic.

## 1.5 AI Models

| Role | Model | Reason |
|---|---|---|
| Strong tier (planning, difficult repairs) | `gpt-oss-120b` on the Groq free tier | Strong open weight reasoning model; free with no credit card, limited only by rate limits |
| Fast tier (routine steps, descriptions, summaries) | A Gemini Flash model on the Google AI Studio free tier | Fast, capable, and free with no credit card, limited only by rate limits |
| Local tier (offline and private mode) | Qwen2.5 Coder 7B running in Ollama | Open weights running on the user's own machine: no account, no quota, and no data leaves the computer |
| Embeddings for schema retrieval | A local Sentence Transformers MiniLM model | Free, fast, and runs offline |
| Test double | `MockLLMProvider` with scripted responses | Makes deterministic tests of the agent pipeline possible and uses no quota |

**Zero cost policy.** AskDB is designed so that building, testing, and running it never costs money. Every model is either an open weight model running locally through Ollama or a provider's free tier that needs no credit card. No billing account is ever attached to any API key. Free tiers are limited by requests per minute and per day, so `ModelRouter` retries rate limited calls with exponential backoff and then falls back to another free provider, ending with the local model, which has no quota at all. Because some free cloud tiers may use submitted data to improve their models, the cloud tiers are only used with sample and public datasets; any private data should be analyzed with the Local Only policy.

The exact models can be changed in configuration without modifying any agent code, because every provider sits behind the `LLMProvider` interface (Adapter pattern) and the choice of provider is made by the current `RoutingPolicy` (State pattern).

## 1.6 How the AI Model Interacts with the Rest of the System

The guiding rule is: **the model proposes, the software decides.**

1. The LLM never connects to the database. It only returns structured JSON that describes either a plan, a tool call (tool name and arguments), or a final answer.
2. `ResponseParser` converts that JSON into typed objects (`QueryPlan`, `AgentAction`, `Insight`). Malformed output is rejected and reported back to the model once.
3. `ToolRegistry` checks that the requested tool exists and that the arguments match the tool's parameter schema before anything runs.
4. Every SQL statement passes through `SqlValidator`, a stack of decorators that only allows single, read only `SELECT` statements with a row limit. The dataset is also opened in read only mode as a second layer of protection.
5. Tool results are summarized before they are sent back to the model (column names, row count, and at most a small number of rows). This limits token usage and the amount of data shared with external services.
6. Values stored in the data are always treated as data, never as instructions to the agent.
7. The final summary is checked by `InsightVerifier`, which confirms every number in the text against the query result.
8. Every step is published as an `AgentEvent`, so the GUI, the CLI, and the usage tracker can observe the agent without the agent depending on them.

## 1.7 Overall Architecture

AskDB is arranged in layers. The pictures Stage 1 asks for are the UML class diagram (section 3), the use-case diagram (section 5), and the sequence diagrams (section 7).

**Presentation layer.** The GUI and the CLI are both clients of `AskDBFacade`. Neither contains business logic.

**Application layer.** `AskDBFacade` is the single entry point for all features and owns the current `Session` (data source, schema, schema index, conversation memory, pending clarification).

**Agent core.** `AgentOrchestrator` runs the plan, act, observe loop. `Planner` produces a `QueryPlan`. `ToolRegistry` looks up and runs the six tools.

**LLM and retrieval.** `ModelRouter` selects a provider through a `RoutingPolicy` and handles fallback. `SchemaIndex` performs semantic search over the schema.

**Data access and safety.** `DataSource` hides whether data came from SQLite or CSV. `SqlValidator` and `QueryExecutor` guarantee safe, bounded execution.

**Output and persistence.** Charts, history, and saved questions.

## 1.8 Technology Stack

| Concern | Choice |
|---|---|
| Language | Python 3.11 |
| GUI | PySide6 (Qt for Python) |
| CLI | Typer with Rich for tables and progress output |
| Data | Python `sqlite3`, pandas for CSV loading and type inference |
| Embeddings | Sentence Transformers (local MiniLM model) |
| Charts | matplotlib |
| LLM access | Google Gen AI Python SDK (Gemini), Groq through its OpenAI compatible endpoint, Ollama HTTP API; all free to use |
| Application storage | A separate SQLite file for history and saved questions |

The design itself is language independent. Python was chosen because it has mature libraries for LLM access, embeddings, data handling, and charts.

## 1.9 GUI and CLI

**GUI.** The main window has four tabs:

* **Ask:** question box, live progress of agent steps, a New Conversation button, and the answer panel (verified summary, SQL editor, result table, chart with a chart type selector, and a Save button).
* **Schema:** tree of tables and columns with types, keys, row counts, descriptions, and sample rows.
* **History:** searchable table of past questions, plus the list of saved questions.
* **Usage and Trace:** step by step trace of the latest request and session totals for tokens, latency, and requests used against each provider's free rate limits.

A Settings dialog configures the routing policy, API keys, and whether sample rows may be sent to external models.

**CLI.** Every major feature is also available from the terminal:

| Command | Feature |
|---|---|
| `askdb import data/sample_store.db` or `askdb import orders.csv customers.csv` | F01 |
| `askdb schema [table]` | F02 |
| `askdb ask "which region grew fastest in 2025?" [--chart out.png] [--trace] [--continue]` | F03, F05, F06, F08, F09, F10 |
| `askdb shell` (interactive session with memory and clarification prompts) | F04, F10 |
| `askdb sql "SELECT ..."` | F05, F07 |
| `askdb history [--search text] [--saved]`, `askdb save ID "name"`, `askdb rerun ID` | F11 |
| `askdb config --policy cheap_first`, `askdb usage` | F12 |

# 2. Detailed Feature Specifications

AskDB has 12 major features. None of them are account or housekeeping operations such as login, logout, exit, or about.

| ID | Feature | Type |
|---|---|---|
| F01 | Dataset Import | Deterministic |
| F02 | Schema Explorer with Semantic Descriptions | Hybrid |
| F03 | Natural Language Question Answering | AI |
| F04 | Ambiguity Clarification | AI |
| F05 | Query Safety Guard | Deterministic |
| F06 | Self Correcting Query Repair | Hybrid |
| F07 | SQL Review and Manual Editing | Deterministic |
| F08 | Automatic Chart Generation | Hybrid |
| F09 | Grounded Insight Summary | Hybrid |
| F10 | Follow Up Conversation Memory | AI |
| F11 | Query History and Saved Questions | Deterministic |
| F12 | Model Routing and Usage Monitor | Hybrid |

## F01 Dataset Import

| Field | Specification |
|---|---|
| Description | Loads a SQLite database file or one or more CSV files into a working session. CSV files are converted into tables of an in memory SQLite database with inferred column types, so the rest of the system always works through one relational interface. |
| User Interaction | GUI: File menu, Import Dataset, then choose a `.db`/`.sqlite` file or several `.csv` files. A progress bar is shown and the Schema tab opens when loading finishes. CLI: `askdb import data/sample_store.db` or `askdb import orders.csv customers.csv`. |
| Input | One SQLite file path, or one or more CSV file paths. Optional table name for each CSV. |
| Output | An active `Session` with a connected `DataSource` and a `SchemaInfo` object (tables, columns, types, keys, row counts). A confirmation message with table and row counts. |
| AI Involvement | Deterministic. |
| Expected Workflow | 1. The user selects the file(s). 2. `GuiController.on_import_clicked()` calls `AskDBFacade.import_dataset()`. 3. `DataSourceFactory.create()` chooses `SQLiteDataSource` or `CsvDataSource` from the file extension. 4. The source connects; for CSV it parses the files, infers types, and creates tables. 5. `SchemaReader.read()` builds `SchemaInfo`. 6. Schema enrichment (F02) runs. 7. The schema is displayed. |
| Error/Alternative Cases | Unsupported extension: the user sees the list of supported types. Corrupt or locked SQLite file: an error is shown and the previous session stays active. CSV without a header or with inconsistent rows: the import stops with the line number, or skipped rows are reported. Two CSV files with the same name: a numeric suffix is added to the table name. File larger than the configured limit: `FileTooLargeError` is shown with the file size and the limit, and the previous session stays active. |

## F02 Schema Explorer with Semantic Descriptions

| Field | Specification |
|---|---|
| Description | Displays every table and column with type, keys, row counts, and sample rows. The agent writes a short plain English description of each table and column using its name and a few sample values. Names and descriptions are embedded into a `SchemaIndex`, which the agent later uses to retrieve only the relevant parts of the schema for a question. |
| User Interaction | GUI: Schema tab shows a tree; selecting a table shows columns, descriptions, and five sample rows. The user can edit any description. CLI: `askdb schema` or `askdb schema orders`. |
| Input | The active session. Optional edited descriptions from the user. |
| Output | Annotated schema view and a built `SchemaIndex`. |
| AI Involvement | Hybrid. Schema reading is deterministic, descriptions come from the LLM (fast tier), and embeddings come from the local embedding model. |
| Expected Workflow | 1. `SchemaDescriber.describe()` collects sample rows for each table. 2. `PromptBuilder.build_description_prompt()` creates the prompt. 3. `ModelRouter.complete()` sends it to the fast tier. 4. `ResponseParser.parse_descriptions()` stores the descriptions in `ColumnInfo`. 5. `SchemaIndex.build()` embeds every table and column entry. 6. `MainWindow.show_schema()` shows the result. |
| Error/Alternative Cases | LLM unavailable: descriptions are left empty, the index is built from names only, and a notice is shown. Unparseable description output: one retry, then that table is skipped. User edits a description: only that entry is embedded again. The user disabled sample sharing in Settings: descriptions are generated from names and types only. |

## F03 Natural Language Question Answering

| Field | Specification |
|---|---|
| Description | The core agent capability. The user asks a question in English; the agent retrieves relevant schema, creates a plan, and uses tools in a loop to write and run SQL until it can give a final answer with the SQL, a result table, a chart, and a verified summary. |
| User Interaction | GUI: type in the Ask box and press Ask; each agent step appears live (for example "Searching schema", "Running query"). CLI: `askdb ask "which product category grew fastest last quarter?"`. |
| Input | Question text, the active session, and the conversation memory. |
| Output | An `AgentAnswer` with status, final SQL, `QueryResult`, `ChartSpec`, a verified `Insight`, and the step trace. |
| AI Involvement | AI, supported by deterministic tools. |
| Expected Workflow | 1. `AskDBFacade.ask()` calls `AgentOrchestrator.run()`. 2. `Planner.create_plan()` retrieves schema hits from `SchemaIndex` and asks the LLM for a `QueryPlan`. 3. In a loop of at most eight steps, the orchestrator builds a step prompt, gets an `AgentAction` from the LLM, and runs it through `ToolRegistry.execute()`. 4. Once a query succeeds and the model returns a final action, `MakeChartTool` builds a chart and `InsightGenerator` writes the summary. 5. `InsightVerifier.verify()` checks the summary. 6. The answer is added to memory and history and displayed. |
| Error/Alternative Cases | No dataset loaded: the user is asked to import one. Question the data cannot answer ("what will the weather be tomorrow"): status `NO_DATA` with an explanation, and no invented numbers. Step limit reached: status `FAILED` with the partial trace and a suggestion to rephrase or edit the SQL. Malformed LLM output: the parse error is returned to the model once as an observation, then the request fails gracefully. Provider timeout: handled by fallback (F12). Ambiguous question: F04. Failing query: F06. |

## F04 Ambiguity Clarification

| Field | Specification |
|---|---|
| Description | When a question has several reasonable interpretations, the agent asks the user instead of guessing. Examples: "top customers" (by revenue or by number of orders), or "sales" when both `gross_sales` and `net_sales` exist. |
| User Interaction | GUI: a clarification card with option buttons and a free text box. CLI (`askdb shell`): numbered options and a prompt for the choice. |
| Input | The question, the schema matches, and later the user's choice. |
| Output | A `ClarificationRequest`, followed by a final answer that states the chosen interpretation. |
| AI Involvement | AI. |
| Expected Workflow | 1. The planner marks the plan as ambiguous, or the model calls `AskUserTool` during the loop. 2. The orchestrator saves the `AgentState` in the session as pending. 3. The answer returns with status `NEEDS_CLARIFICATION`. 4. The user picks an option. 5. `AskDBFacade.answer_clarification()` calls `AgentOrchestrator.resume()`, which continues the loop with the clarified meaning. |
| Error/Alternative Cases | The user ignores the card and asks something else: the pending state is discarded. The reply is still ambiguous: at most two clarification rounds, after which the agent uses the most common interpretation and states the assumption in the answer. Non interactive mode (a single CLI `ask`): the agent must state its assumption instead of asking. |

## F05 Query Safety Guard

| Field | Specification |
|---|---|
| Description | Every SQL statement, whether written by the agent or typed by the user, passes through stacked decorators before execution: read only (`SELECT` or `WITH` only), a single statement, no forbidden objects or commands (`PRAGMA`, `ATTACH`, `load_extension`, system tables), and a maximum row limit. Queries also have an execution timeout, and the database connection is opened read only. |
| User Interaction | Automatic. Blocked statements show a red banner in the GUI or a message in the CLI with the reason. |
| Input | SQL text. |
| Output | A `ValidationResult`: valid (possibly rewritten with a `LIMIT`) or rejected with a reason. |
| AI Involvement | Deterministic. |
| Expected Workflow | 1. `RunQueryTool` or `AskDBFacade.run_manual_sql()` calls `SqlValidator.validate()`. 2. Each rule decorator adds its check and calls the wrapped rule. 3. If all rules pass, `QueryExecutor.run()` executes it with a timeout. |
| Error/Alternative Cases | The user asks the agent to "delete the old rows": the agent explains that AskDB is read only and the answer status is `REFUSED`. Multiple statements or statements hidden after a comment: rejected by `SingleStatementRule`. Slow query: cancelled at the timeout and reported to the agent, which may simplify it. Unparseable SQL: returned to the agent as an error for repair (F06). |

## F06 Self Correcting Query Repair

| Field | Specification |
|---|---|
| Description | When a query fails (syntax error, unknown column, type mismatch) or returns a suspicious result (empty when rows were expected), the agent reads the error, inspects the schema or the distinct values of a column, and rewrites the query. It makes at most three repair attempts. Under the Cheap First policy the router escalates to the strong model from the second attempt. |
| User Interaction | Automatic. Progress shows messages such as "Attempt 2 of 3: fixing unknown column". Each attempt appears in the trace. |
| Input | The failed SQL, the error message or empty result, and schema context. |
| Output | A corrected query with its result, or a `FAILED` answer that explains what was tried. |
| AI Involvement | Hybrid. Error detection and retry limits are deterministic; the fix comes from the LLM. |
| Expected Workflow | 1. `RunQueryTool` returns a failed `ToolResult`. 2. The orchestrator increases the repair count. 3. `PromptBuilder.build_repair_prompt()` includes the SQL and error. 4. `ModelRouter.complete()` is called with the attempt number. 5. The new action goes through validation and execution again. |
| Error/Alternative Cases | The model produces the same SQL again: the loop stops early. Three attempts fail: status `FAILED` with the last error and a suggestion to edit the SQL manually (F07). The empty result is genuine: after confirming the filter values with `ColumnValuesTool`, the agent reports that no rows match instead of silently loosening the filter. |

## F07 SQL Review and Manual Editing

| Field | Specification |
|---|---|
| Description | The final SQL of every answer is shown in an editor. The user can change it and run it directly without calling the LLM. Edited SQL still goes through the safety guard. |
| User Interaction | GUI: SQL panel under each answer with a Run button. CLI: `askdb sql "SELECT region, SUM(total) FROM orders GROUP BY region"`. |
| Input | SQL text. |
| Output | Result table and chart; a history entry marked as manual. |
| AI Involvement | Deterministic. |
| Expected Workflow | 1. `GuiController.on_run_sql_clicked()` calls `AskDBFacade.run_manual_sql()`. 2. `SqlValidator.validate()` checks the statement. 3. `QueryExecutor.run()` executes it. 4. `ChartRecommender.recommend()` picks a chart. 5. `HistoryRepository.add()` records it. 6. The result is displayed. |
| Error/Alternative Cases | Rejected by the guard: the reason is shown and the editor keeps the text. SQLite error: the message is shown. No dataset loaded: the user is asked to import one. |

## F08 Automatic Chart Generation

| Field | Specification |
|---|---|
| Description | Chooses a suitable chart from the shape of the result and the chart hint in the plan: line for time series, bar for comparisons across categories, pie for a share of a total with few categories, scatter for two numeric columns, and a table or single value card otherwise. The user can switch the chart type. |
| User Interaction | GUI: chart under the result table with a chart type selector and Save Image. CLI: `--chart out.png` saves the image and the table is printed in the terminal. |
| Input | `QueryResult` and the chart hint from the `QueryPlan`. |
| Output | A `ChartSpec` shown in `MainWindow`, or saved as an image from the CLI. |
| AI Involvement | Hybrid. The LLM suggests a chart type; `ChartFactory` chooses a chart class that fits the data and that class renders it. |
| Expected Workflow | 1. `MakeChartTool.execute()` calls `ChartRecommender.recommend()`. 2. `ChartFactory.create()` returns the hinted chart class if it fits the result; otherwise it returns the first class that fits. 3. That class builds a `ChartSpec`. 4. The chart is displayed. |
| Error/Alternative Cases | Unsuitable hint (a pie chart with 40 categories): falls back to bar. A single value result: shown as a value card. No numeric column: table only. Rendering error: the table is shown and the error is logged. |

## F09 Grounded Insight Summary

| Field | Specification |
|---|---|
| Description | Adds a two or three sentence plain English summary to each answer. `InsightVerifier` checks every number in the summary against the result, including simple derived values such as percentages. Unverified numbers cause one regeneration; if they persist, those claims are marked with a warning. |
| User Interaction | GUI: summary above the table with a "Verified" badge or a warning icon on unverified claims. CLI: summary printed with a verification line. |
| Input | The question and the result (at most 50 rows plus computed totals are sent to the model). |
| Output | An `Insight` with its verification status. |
| AI Involvement | Hybrid. The LLM writes the text; the verification is deterministic. |
| Expected Workflow | 1. `InsightGenerator.generate()` builds a prompt and calls the fast tier. 2. `ResponseParser.parse_insight()` creates the `Insight`. 3. `InsightVerifier.verify()` extracts numbers and matches them to the result within rounding tolerance. 4. On failure the summary is regenerated once with feedback. 5. The insight is attached to the answer. |
| Error/Alternative Cases | Empty result: a fixed deterministic message is used and no LLM call is made. Unusual number formats the verifier cannot parse: treated as unverified. LLM unavailable: the answer is shown without a summary. The prompt forbids causal claims not supported by the data. |

## F10 Follow Up Conversation Memory

| Field | Specification |
|---|---|
| Description | Keeps the most recent turns (question, SQL, and a short result summary) so the user can refine an answer: "now only for 2024", "break that down by region", "what about the lowest ones?". Memory is bounded and can be reset. |
| User Interaction | GUI: keep typing in the Ask box; a New Conversation button clears memory. CLI: `askdb shell` keeps memory across questions; a single `askdb ask` is stateless unless `--continue` is used. |
| Input | The follow up question and `ConversationMemory`. |
| Output | A new answer built on the previous query. |
| AI Involvement | AI. |
| Expected Workflow | 1. `ConversationMemory.context_text()` is included in the planning prompt. 2. The planner resolves references such as "that" or "those" to the previous query. 3. The flow continues as in F03. 4. `ConversationMemory.add_turn()` stores the new turn. |
| Error/Alternative Cases | Reference with no previous turn ("filter that"): the agent asks for clarification (F04). Memory limit reached: the oldest turns are dropped. Unrelated new topic: treated as a fresh question. A new dataset is imported: memory is cleared. |

## F11 Query History and Saved Questions

| Field | Specification |
|---|---|
| Description | Every answer is stored in the application database with question, SQL, status, row count, model, cost, and time. The user can search history, rerun an entry, and save questions under a name to reuse later, for example on next month's data. |
| User Interaction | GUI: History tab with a search box and actions for Rerun and Save; a Saved list beside it. CLI: `askdb history --search revenue`, `askdb save 42 "Monthly revenue"`, `askdb rerun 42`. |
| Input | Search text, entry ID, and a name for saving. |
| Output | History lists, saved question lists, and rerun answers. |
| AI Involvement | Deterministic. Rerunning executes the stored SQL without an LLM call. An "Ask again" option sends the original question through the agent instead by calling `AskDBFacade.ask(entry.question)`. |
| Expected Workflow | 1. `AskDBFacade.ask()` and `run_manual_sql()` call `HistoryRepository.add()`. 2. `get_history()` lists or searches entries. 3. `save_question()` calls `SavedQuestionRepository.save()`. 4. `rerun_history()` validates and executes the stored SQL. |
| Error/Alternative Cases | Stored SQL refers to a table that does not exist in the current dataset: an error with the option to ask the agent again. Duplicate saved name: the user is asked to rename. Damaged application database: it is recreated with a warning and the old file is kept as a backup. |

## F12 Model Routing and Usage Monitor

| Field | Specification |
|---|---|
| Description | The user selects a routing policy: **Cheap First** (fast model first, strong model for repairs and parse failures), **Strong Only**, or **Local Only** (Ollama, offline, no data leaves the machine). All three policies use only free models. The router retries rate limited calls with exponential backoff and then falls back to another available free provider, ending with the local model. The Usage and Trace tab shows each agent step (tool, model, tokens, latency) and session totals, including requests used against each provider's free rate limits. The cost field is always zero with the free configuration; it is kept so the design would still report correctly if a paid provider were ever added. |
| User Interaction | GUI: Settings dialog for the policy; Usage and Trace tab. CLI: `askdb config --policy local_only`, `askdb usage`, and `--trace` on `askdb ask`. |
| Input | Policy name; agent events produced during requests. |
| Output | Active policy, per step trace, and a `UsageSummary`. |
| AI Involvement | Hybrid. Deterministic routing and accounting around AI calls. |
| Expected Workflow | 1. `AskDBFacade.set_routing_policy()` makes sure providers exist through `LLMProviderFactory.create()` and calls `ModelRouter.set_policy()`. 2. During a request, `ModelRouter.complete()` asks `RoutingPolicy.select()` for a provider and calls it. 3. The orchestrator publishes an `AgentEvent` for each step. 4. `TraceView`, `ConsoleProgressPrinter`, and `UsageTracker` receive it. 5. `get_usage_summary()` returns totals. |
| Error/Alternative Cases | Missing API key: that policy is unavailable and the user sees setup instructions, or Local Only is used if Ollama is running. Provider timeout or rate limit: the next provider is tried and the fallback is logged. Ollama not running: a clear message. Local model cost: reported as zero. |

# 3. UML Class Diagram

Stage 1 asks for one UML class diagram. It is drawn as five small views of the same model so each picture stays readable. A class named in more than one view is the same class. Only the important attributes and methods are shown. Stereotypes `<<interface>>` and `<<abstract>>` mark interfaces and abstract classes. A multiplicity of `1` means exactly one, and `0..1` means zero or one. `0..n` means zero or more, and `1..n` means one or more. In course UML those last two are written `0..*` and `1..*`.

## 3.1 View A: Presentation and Application Layer

![View A: Presentation and Application Layer](../diagrams/png/view-a-presentation.png)

The agent classes are in View B. Model routing is in View C. Data access and the SQL guard are in View D. Charts and history are in View E.

## 3.2 View B: Agent Core and Tools

![View B: Agent Core and Tools](../diagrams/png/view-b-agent.png)

## 3.3 View C: LLM Access, Routing and Retrieval

![View C: LLM Access, Routing and Retrieval](../diagrams/png/view-c-llm.png)

## 3.4 View D: Data Access and Query Safety

![View D: Data Access and Query Safety](../diagrams/png/view-d-data.png)

## 3.5 View E: Charts and History

![View E: Charts and History](../diagrams/png/view-e-charts.png)

## 3.6 Responsibilities of the Main Classes

| Class | Responsibility |
|---|---|
| `AskDBFacade` | Single entry point for GUI and CLI; coordinates subsystems for every feature |
| `Session` | Holds the state of the currently loaded dataset and conversation |
| `AgentOrchestrator` | Runs the plan, act, observe loop; enforces step, repair, and clarification limits; publishes events |
| `Planner` | Retrieves relevant schema and asks the LLM for a `QueryPlan` |
| `PromptBuilder` / `ResponseParser` | Build every prompt from templates; convert model output into typed objects |
| `ToolRegistry` / `Tool` | Register, describe, validate, and execute the agent's tools |
| `ModelRouter` / `RoutingPolicy` | Choose a model for each call, escalate on repeated failure, fall back on provider errors |
| `LLMProvider` adapters | Hide vendor SDK differences behind one interface |
| `SchemaIndex` | Semantic search over schema entries using embeddings |
| `DataSource` / `DataSourceFactory` | Uniform relational access to SQLite and CSV data |
| `SqlValidator` / `SqlRule` | Stack of safety checks around every SQL statement |
| `QueryExecutor` | Bounded, timed execution of validated SQL |
| `InsightGenerator` / `InsightVerifier` | Write summaries and check every number against the result |
| `ChartFactory` / chart classes | Create the chart class that fits a result |
| Repositories | Persist history and saved questions |

Note on `QueryPlan.category`: the planner classifies every request as `query`, `destructive` (a request to change data), or `unanswerable` (the schema cannot answer it). This lets the orchestrator refuse or decline early without calling any tools. `AgentState.successful_query` prevents the agent from giving a final answer before any query has succeeded, and `seen_sql` lets the orchestrator detect a repair loop that keeps producing the same failing SQL.

# 4. Design Pattern Explanations

AskDB uses seven patterns taught in the course: Facade, Adapter, Observer, Factory, State, Decorator, and Builder. Each one solves a specific problem in this application. The six tools, the GUI widgets, and the history storage classes are ordinary classes. They are not claimed as extra patterns.

## 4.1 Facade

| Question | Answer |
|---|---|
| Design problem | Two different interfaces (GUI and CLI) must offer the same 12 features. Each feature involves several subsystems: the agent, data sources, the validator, repositories, charts, and the router. |
| Participating classes | `AskDBFacade` (Facade); `GuiController` and `CliApp` (Clients); `AgentOrchestrator`, `DataSourceFactory`, `SchemaReader`, `SchemaDescriber`, `SqlValidator`, `QueryExecutor`, `ChartRecommender`, `HistoryRepository`, `SavedQuestionRepository`, `ModelRouter`, `LLMProviderFactory`, `UsageTracker` (Subsystem classes) |
| Roles | The facade exposes one simple method per user goal (`import_dataset`, `ask`, `run_manual_sql`, `set_routing_policy`, and so on) and coordinates the subsystem calls. Clients only translate user input into facade calls and display the results. |
| Why appropriate | It guarantees that the GUI and CLI behave identically, which the project requires, and it keeps the presentation layer independent of the internal structure. |
| Without it | Both interfaces would repeat the same orchestration logic and depend on about twenty classes each. The two copies would drift apart, and every internal change would require editing both interfaces. |

## 4.2 Adapter

| Question | Answer |
|---|---|
| Design problem | Each LLM vendor has a different SDK, message format, error types, and token usage fields. Separately, CSV files are not relational, yet the agent needs to run SQL on them. |
| Participating classes | LLM access: `LLMProvider` (Target), `GeminiAdapter`, `GroqAdapter`, `OllamaAdapter` (Adapters), `GeminiClient`, `GroqClient`, Ollama HTTP API (Adaptees), `ModelRouter` (Client). Data access: `DataSource` (Target), `CsvDataSource` (Adapter), CSV files loaded through pandas (Adaptee), `QueryExecutor` and the tools (Clients). |
| Roles | Each adapter converts the neutral message list into the vendor format, calls the vendor, and converts the response into an `LLMResponse`. `CsvDataSource` loads CSV files into an in memory SQLite database and exposes them through the same `DataSource` interface as a real database. |
| Why appropriate | The agent code depends only on `LLMProvider` and `DataSource`, so providers and data formats can be added or swapped without touching the agent. It also allows `MockLLMProvider` to replace real models in tests. |
| Without it | Vendor specific code would spread through the planner, orchestrator, describer, and insight generator. Supporting local models or a second vendor would mean editing all of them, and deterministic testing of the agent pipeline would be impossible. |

## 4.3 State

| Question | Answer |
|---|---|
| Design problem | `ModelRouter` must behave differently depending on the routing mode the user selected: Cheap First, Strong Only, or Local Only. The rest of the agent should not contain that choice. |
| Participating classes | `ModelRouter` (context); `RoutingPolicy` (state); `CheapFirstPolicy`, `StrongOnlyPolicy`, `LocalOnlyPolicy` (concrete states). |
| Roles | The router keeps one current state. `select()` is handled by that state. `set_policy()` replaces the state when the user changes Settings. |
| Why appropriate | Each mode is a small class. Switching mode changes the router's behavior without editing the agent. |
| Without it | `ModelRouter` would contain a conditional for every mode, and adding a mode would mean editing that conditional. |

## 4.4 Observer

| Question | Answer |
|---|---|
| Design problem | Several parts of the system must react to agent progress (the GUI trace and progress list, the CLI progress printer, and the usage tracker), but the agent must not depend on the GUI or CLI. |
| Participating classes | `AgentOrchestrator` (Subject), `AgentEventListener` (Observer interface), `TraceView`, `ConsoleProgressPrinter`, `UsageTracker` (Concrete observers), `AgentEvent` (notification data). |
| Roles | The subject keeps a list of observers and calls `on_event()` whenever something changes. Observers decide independently how to react. |
| Why appropriate | It keeps the dependency direction correct (presentation depends on the core, never the reverse). New observers can be added without changing the agent. |
| Without it | The orchestrator would call GUI methods directly, breaking the layering. The CLI could not reuse the agent, and views would have to poll for changes. |

## 4.5 Factory

| Question | Answer |
|---|---|
| Design problem | Which concrete class to create depends on runtime input: the file type being imported, the provider named in configuration, or the chart that fits a result. Clients should depend only on the product interface. |
| Participating classes | `DataSourceFactory.create(paths)` returns `SQLiteDataSource` or `CsvDataSource`. `LLMProviderFactory.create(name)` returns `GeminiAdapter`, `GroqAdapter`, `OllamaAdapter`, or `MockLLMProvider`. `ChartFactory.create(result, hint)` returns the first fitting chart class: `LineChart`, `BarChart`, `ScatterChart`, `PieChart`, or `TableChart`. |
| Roles | Each factory is the creator. The interfaces (`DataSource`, `LLMProvider`, and `ChartProduct`) are the products. `ChartRecommender` asks `ChartFactory` for a chart and does not choose the class itself. |
| Why appropriate | Creation logic lives in one place, so the facade, the CLI, and the agent never need to know concrete class names. |
| Without it | Checks such as "if the path ends with .csv" or "if this result is a time series" would be repeated in the facade and the agent, and adding a source, provider, or chart would mean finding every place objects are created. |

## 4.6 Decorator

| Question | Answer |
|---|---|
| Design problem | Every SQL statement must pass several independent safety checks, and a check may reject the statement or rewrite it (for example by adding a row limit). Each check must be testable on its own. |
| Participating classes | `SqlRule` (component); `SingleStatementRule`, `ReadOnlyRule`, `ForbiddenObjectRule`, `RowLimitRule` (concrete decorators, each wrapping the next rule); `SqlValidator` (builds the wrapped stack); `RunQueryTool` and `AskDBFacade` (call `SqlValidator`). |
| Roles | Each decorator adds one check, then calls the wrapped rule with the original or rewritten SQL. A failure stops there. The outermost rule is what `validate()` calls. |
| Why appropriate | Safety stays a set of small wrappers. A new check is a new decorator around the existing stack. |
| Without it | One validation method would mix parsing, read only checks, forbidden objects, and limits. Changing one check could break the others. |

## 4.7 Builder

| Question | Answer |
|---|---|
| Design problem | Every prompt is a complex object assembled the same way: a system instruction, the question, schema hits or tool results, and a required JSON shape. The parts differ for planning, a tool step, a repair, an insight, and a schema description. |
| Participating classes | `PromptBuilder` (builder); `Planner`, `AgentOrchestrator`, `SchemaDescriber`, and `InsightGenerator` (directors that ask for one kind of prompt). |
| Roles | `PromptBuilder` exposes one build method per prompt kind (`build_plan_prompt`, `build_step_prompt`, `build_repair_prompt`, `build_insight_prompt`, `build_description_prompt`). Each method assembles the message list. Callers do not concatenate prompt text themselves. |
| Why appropriate | Prompt wording lives in one class, so a change to the JSON contract is made once. |
| Without it | The planner, orchestrator, describer, and insight generator would each assemble their own prompt strings, and those strings would drift apart. |

# 5. Use Case Diagram

![Use case diagram](../diagrams/png/use-case.png)

Actors are the stick figures outside the system boundary. Use cases are the ovals inside it. A solid line is an association. A dashed arrow labeled «include» goes from the base use case to the included one. A dashed arrow labeled «extend» goes from the extending use case to the base use case. These are the UML include and extend relationships.

**Actors.** The **Data Analyst** is the primary user of all everyday features, including choosing a clarification (UC04) and choosing a routing policy (UC10). The **LLM Service** (cloud APIs or the local Ollama server), the **Embedding Model**, and the **Data Files** are secondary actors outside the system boundary.

**Relationships.** Importing always builds the schema view and index (UC01 includes UC02). Every path that executes SQL includes UC06 Validate Query. Clarification (UC04) and repair (UC07) extend UC03 only when their conditions occur. Follow up questions reuse the full question answering flow (include UC03).

**Feature coverage.** F01 → UC01; F02 → UC01, UC02; F03, F08, F09 → UC03; F04 → UC04; F10 → UC05; F05 → UC06; F06 → UC07; F07 → UC08; F11 → UC09; F12 → UC10.

# 6. Use Case Descriptions

## UC01 Import Dataset

| Field | Description |
|---|---|
| Use Case ID | UC01 |
| Use Case Name | Import Dataset |
| Actor(s) | Data Analyst (primary); Data Files, LLM Service, Embedding Model (secondary, through UC02) |
| Goal | Load a SQLite database or CSV files so questions can be asked about them. |
| Preconditions | AskDB is running. The files exist and are readable. |
| Trigger | The analyst chooses File, Import Dataset in the GUI or runs `askdb import`. |
| Main Success Scenario | 1. The analyst selects one SQLite file or one or more CSV files. 2. The system checks the file types and size. 3. The system creates the matching data source and connects in read only mode (CSV files are loaded into tables with inferred types). 4. The system reads tables, columns, keys, and row counts. 5. The system enriches the schema and builds the index (UC02). 6. The system creates a new session and clears conversation memory. 7. The system shows the schema and a confirmation with table and row counts. |
| Alternative/Exception Flows | 2a. Unsupported or mixed file types: the system lists supported types and stops. 2b. File larger than the configured limit (`max_import_mb`): the system stops with an error showing the file size and the limit, and the previous session remains active. 3a. Corrupt or locked SQLite file: an error is shown and the previous session remains active. 3b. CSV without a header or with bad rows: the system reports the file and line, or the number of skipped rows. 3c. Duplicate CSV names: a numeric suffix is added. 5a. LLM unavailable: the schema is shown without descriptions (see UC02). |
| Postconditions | An active session exists with a connected read only data source, schema, and schema index. |
| Related Feature(s) | F01, F02 |

## UC02 Explore Schema

| Field | Description |
|---|---|
| Use Case ID | UC02 |
| Use Case Name | Explore Schema |
| Actor(s) | Data Analyst (primary); LLM Service, Embedding Model (secondary) |
| Goal | Understand the structure and meaning of the data, and give the agent a searchable description of it. |
| Preconditions | A dataset has been imported. |
| Trigger | Automatically after import, or the analyst opens the Schema tab or runs `askdb schema`. |
| Main Success Scenario | 1. The system collects a few sample rows per table (if sample sharing is allowed). 2. The system asks the fast model for a one sentence description of each table and column. 3. The system stores the descriptions with the schema. 4. The system embeds every table and column entry into the schema index. 5. The analyst browses tables, columns, types, keys, row counts, descriptions, and sample rows. 6. Optionally, the analyst edits a description; the system updates that index entry. |
| Alternative/Exception Flows | 1a. Sample sharing disabled: descriptions are generated from names and types only. 2a. LLM unavailable: descriptions stay empty, the index uses names only, and a notice is shown. 3a. Unparseable model output: retried once, then that table is skipped. |
| Postconditions | The schema is annotated and the schema index is ready for retrieval. |
| Related Feature(s) | F02 |

## UC03 Ask Question in Natural Language

| Field | Description |
|---|---|
| Use Case ID | UC03 |
| Use Case Name | Ask Question in Natural Language |
| Actor(s) | Data Analyst (primary); LLM Service, Embedding Model (secondary) |
| Goal | Get a correct, explained answer to a question about the data without writing SQL. |
| Preconditions | A dataset is loaded. At least one model provider is available. |
| Trigger | The analyst types a question and presses Ask, or runs `askdb ask "..."`. |
| Main Success Scenario | 1. The analyst enters a question. 2. The system retrieves the most relevant tables and columns from the schema index. 3. The agent creates a plan and classifies the request as a normal query. 4. The agent chooses tools step by step (for example search schema, check column values, run query). 5. Every query is validated (UC06) and executed, and the result is returned to the agent. 6. The agent gives a final answer after a successful query. 7. The system chooses and builds a chart. 8. The agent writes a short summary and the system verifies every number in it against the result. 9. The system stores the turn in memory and the answer in history. 10. The system displays the summary, SQL, table, chart, and trace. |
| Alternative/Exception Flows | 1a. No dataset loaded: the analyst is asked to import one. 3a. Request to change data: the agent refuses and explains that AskDB is read only (status Refused). 3b. The data cannot answer the question: the agent explains why (status No Data). 3c. The question is ambiguous: UC04. 5a. A query fails or returns a suspicious empty result: UC07. 5b. A query is rejected by the safety guard: the agent is told only read queries are allowed and must rewrite it. 6a. The agent tries to answer before any query succeeded: the system rejects the final answer and the loop continues. 6b. Step limit reached: status Failed with the partial trace. 8a. A number cannot be verified: the summary is regenerated once, then flagged with a warning. Any step: the model provider fails, and the router falls back to another provider (UC10). |
| Postconditions | An answer with a status is displayed and recorded in history, and memory holds the new turn. The data is unchanged. |
| Related Feature(s) | F03, F05, F08, F09 |

## UC04 Clarify Ambiguous Question

| Field | Description |
|---|---|
| Use Case ID | UC04 |
| Use Case Name | Clarify Ambiguous Question |
| Actor(s) | Data Analyst (primary); LLM Service (secondary) |
| Goal | Resolve a question with several reasonable meanings before answering it. |
| Preconditions | UC03 is in progress in an interactive session. The plan or a step identified an ambiguity. |
| Trigger | The planner marks the plan as ambiguous, or the agent calls the ask user tool. |
| Main Success Scenario | 1. The system pauses the agent and saves its state. 2. The system shows the clarification prompt with two to four options. 3. The analyst picks an option or types an answer. 4. The system resumes the agent with the clarified meaning. 5. UC03 continues, and the final answer states the interpretation used. |
| Alternative/Exception Flows | 3a. The analyst asks a different question instead: the paused state is discarded. 4a. The answer is still ambiguous: after two rounds the agent chooses the most common interpretation and states it. 1a. Non interactive session (a single CLI command): the agent does not pause and must state its assumption. |
| Postconditions | The question is answered under an explicit interpretation. |
| Related Feature(s) | F04 |

## UC05 Ask Follow Up Question

| Field | Description |
|---|---|
| Use Case ID | UC05 |
| Use Case Name | Ask Follow Up Question |
| Actor(s) | Data Analyst (primary); LLM Service (secondary) |
| Goal | Refine or extend a previous answer without repeating the full question. |
| Preconditions | At least one answered turn exists in the current conversation. |
| Trigger | The analyst types a follow up such as "now only for 2024" in the same conversation or in `askdb shell`. |
| Main Success Scenario | 1. The analyst enters a follow up question. 2. The system adds recent turns (questions, SQL, result summaries) to the planning context. 3. The agent resolves references such as "that" or "those" to the previous query. 4. UC03 continues with the modified query. 5. The new turn is added to memory. |
| Alternative/Exception Flows | 2a. No previous turn exists: the agent asks what the reference means (UC04). 2b. Memory is full: the oldest turn is dropped. 3a. The question is unrelated: it is treated as a new question. The analyst presses New Conversation: memory is cleared. |
| Postconditions | The answer reflects both the new request and the earlier context. |
| Related Feature(s) | F10 |

## UC06 Validate Query

| Field | Description |
|---|---|
| Use Case ID | UC06 |
| Use Case Name | Validate Query |
| Actor(s) | Included use case; triggered on behalf of the Data Analyst or the agent. |
| Goal | Guarantee that only a single, read only, bounded query ever reaches the database. |
| Preconditions | SQL text is about to be executed. |
| Trigger | Any request to run SQL (agent tool call, manual SQL, or rerun). |
| Main Success Scenario | 1. The system checks that the text is exactly one parseable statement. 2. The system checks that it only reads data. 3. The system checks that no forbidden command or system table is used. 4. The system adds or tightens a row limit. 5. The validated SQL is executed with a timeout. |
| Alternative/Exception Flows | 1a to 3a. A check fails: execution is blocked and a human readable reason is returned to the caller (the agent or the analyst). 5a. The timeout expires: the query is cancelled and reported as an error. |
| Postconditions | Either a bounded result was produced or nothing was executed. The data is never modified. |
| Related Feature(s) | F05 |

## UC07 Repair Failed Query

| Field | Description |
|---|---|
| Use Case ID | UC07 |
| Use Case Name | Repair Failed Query |
| Actor(s) | LLM Service (secondary); runs on behalf of the Data Analyst |
| Goal | Recover automatically from a failing or suspicious query. |
| Preconditions | In UC03, a query failed, was rejected, or returned an unexpected empty result. |
| Trigger | The run query tool returns an error or an empty result. |
| Main Success Scenario | 1. The system records the failure and increases the repair count. 2. The system builds a repair prompt with the SQL and the error. 3. The router selects a model for this attempt (the strong model from the second attempt under Cheap First). 4. The agent inspects the schema or column values if needed and proposes new SQL. 5. The new SQL is validated and executed. 6. UC03 continues with the successful result. |
| Alternative/Exception Flows | 4a. The agent repeats a SQL statement that already failed: the system stops with status Failed. 5a. Three repair attempts fail: status Failed, with the last error and a suggestion to edit the SQL (UC08). 4b. The empty result is genuine: after confirming the filter values, the agent reports that no rows match. |
| Postconditions | A working query and result exist, or the failure is clearly explained. |
| Related Feature(s) | F06 |

## UC08 Review and Edit SQL

| Field | Description |
|---|---|
| Use Case ID | UC08 |
| Use Case Name | Review and Edit SQL |
| Actor(s) | Data Analyst |
| Goal | Inspect and change the SQL behind an answer and run it directly. |
| Preconditions | A dataset is loaded. |
| Trigger | The analyst edits the SQL panel and presses Run, or runs `askdb sql "..."`. |
| Main Success Scenario | 1. The analyst edits or types SQL. 2. The system validates it (UC06). 3. The system executes it. 4. The system builds a chart. 5. The system records the entry in history as manual. 6. The system displays the result and chart. |
| Alternative/Exception Flows | 2a. Rejected by validation: the reason is shown and the text stays in the editor. 3a. SQLite error: the message is shown. |
| Postconditions | The result is displayed and recorded, and no LLM was called. |
| Related Feature(s) | F07, F05 |

## UC09 Manage History and Saved Questions

| Field | Description |
|---|---|
| Use Case ID | UC09 |
| Use Case Name | Manage History and Saved Questions |
| Actor(s) | Data Analyst |
| Goal | Find, reuse, and name previous questions. |
| Preconditions | The application database is available. |
| Trigger | The analyst opens the History tab, or runs `askdb history`, `askdb save`, or `askdb rerun`. |
| Main Success Scenario | 1. The system lists recent history entries. 2. The analyst searches by text. 3. The analyst saves an entry under a name. 4. The analyst reruns an entry; the system validates and executes its stored SQL and displays the result. |
| Alternative/Exception Flows | 3a. The name already exists: the analyst is asked to choose another. 4a. The stored SQL refers to a missing table: an error is shown with the option to ask the agent again with the original question. The application database is damaged: it is recreated and the old file is kept as a backup. |
| Postconditions | History and saved questions are updated; reruns produce new history entries. |
| Related Feature(s) | F11 |

## UC10 Configure Models and View Usage

| Field | Description |
|---|---|
| Use Case ID | UC10 |
| Use Case Name | Configure Models and View Usage |
| Actor(s) | Data Analyst (primary); LLM Service (secondary) |
| Goal | Control which free models are used and see how much of each free quota the requests consume. |
| Preconditions | AskDB is running. |
| Trigger | The user changes the routing policy in Settings or runs `askdb config --policy`; the user opens the Usage and Trace tab or runs `askdb usage`. |
| Main Success Scenario | 1. The user selects Cheap First, Strong Only, or Local Only. 2. The system creates any missing providers and activates the policy. 3. During later requests, the router selects a provider for each call according to the policy. 4. Each agent step is published as an event and recorded. 5. The user views the step by step trace and session totals of tokens, latency, and estimated cost. |
| Alternative/Exception Flows | 2a. A required API key is missing: the policy is unavailable and setup instructions are shown. 2b. Ollama is not running for Local Only: a clear message is shown. 3a. The selected provider times out or is rate limited: the router tries the next available provider and records a fallback event (never to a cloud provider under Local Only). |
| Postconditions | The chosen policy is active and usage data is up to date. |
| Related Feature(s) | F12 |

# 7. Sequence Diagrams

Stage 1 asks for sequence diagrams of the important behaviors, not a separate picture for every feature. These seven cover those behaviors. Calls between objects use methods from the class diagram. The command line calls the same facade method as the window.

| Diagram | Behavior | Features | Use cases |
|---|---|---|---|
| SD01 | Import dataset and build schema index | F01, F02 | UC01, UC02 |
| SD02 | Answer a natural language question | F03, F08, F09 | UC03 |
| SD03 | Refusal, safety validation, and query repair | F05, F06 | UC06, UC07 |
| SD04 | Clarification and follow up question | F04, F10 | UC04, UC05 |
| SD05 | Run manually edited SQL | F07, F05, F08 | UC08, UC06 |
| SD06 | History and saved questions | F11 | UC09 |
| SD07 | Model routing, fallback, and usage monitoring | F12 | UC10 |

The CLI starts the same facade call as the GUI. The `CliApp` method for each diagram is: SD01 `import_cmd` (schema browse after import: `schema_cmd`); SD02 `ask_cmd`; SD03 `ask_cmd`; SD04 `shell_cmd`; SD05 `sql_cmd`; SD06 `history_cmd`, `save_cmd`, and `rerun_cmd`; SD07 `config_cmd` and `usage_cmd`.

## SD01 Import Dataset and Build Schema Index

![SD01 Import Dataset and Build Schema Index](../diagrams/png/sd01-import.png)

## SD02 Answer a Natural Language Question

![SD02 Answer a Natural Language Question](../diagrams/png/sd02-ask.png)

## SD03 Refusal, Safety Validation, and Query Repair

![SD03 Refusal, Safety Validation, and Query Repair](../diagrams/png/sd03-repair.png)

## SD04 Clarification and Follow Up Question

![SD04 Clarification and Follow Up Question](../diagrams/png/sd04-clarify.png)

## SD05 Run Manually Edited SQL

![SD05 Run Manually Edited SQL](../diagrams/png/sd05-manual-sql.png)

No LLM participates in this diagram: manual SQL is fully deterministic.

## SD06 History and Saved Questions

![SD06 History and Saved Questions](../diagrams/png/sd06-history.png)

## SD07 Model Routing, Fallback, and Usage Monitoring

![SD07 Model Routing, Fallback, and Usage Monitoring](../diagrams/png/sd07-routing.png)

# 8. Feature to Design Traceability Table

| Feature | Description | Type | Related Use Case | Classes | Key Methods | Sequence Diagram | Design Pattern(s) |
|---|---|---|---|---|---|---|---|
| F01 Dataset Import | Load SQLite or CSV data into a read only session | Deterministic | UC01 Import Dataset | MainWindow, GuiController, AskDBFacade, DataSourceFactory, SQLiteDataSource, CsvDataSource, SchemaReader, Session | `on_import_clicked()`, `import_dataset()`, `create()`, `connect()`, `read()` | SD01 | Facade, Factory, Adapter |
| F02 Schema Explorer | Annotated schema view and semantic schema index | Hybrid | UC01, UC02 Explore Schema | SchemaDescriber, PromptBuilder, ModelRouter, ResponseParser, SchemaIndex, LocalEmbeddingProvider, MainWindow | `describe()`, `build_description_prompt()`, `complete()`, `parse_descriptions()`, `build()`, `embed()`, `update_entry()`, `show_schema()` | SD01 | Adapter, Builder, Factory |
| F03 Natural Language Question Answering | Agent plans, uses tools, and answers a question | AI | UC03 Ask Question | AskDBFacade, AgentOrchestrator, Planner, SchemaIndex, PromptBuilder, ModelRouter, LLMProvider, ResponseParser, ToolRegistry, SearchSchemaTool, SampleRowsTool, RunQueryTool, QueryExecutor | `ask()`, `run()`, `create_plan()`, `search()`, `build_step_prompt()`, `complete()`, `parse_action()`, `execute()`, `finalize()` | SD02 | Facade, Builder, State, Adapter, Observer |
| F04 Ambiguity Clarification | Ask the user when a question has several meanings | AI | UC04 Clarify Ambiguous Question | AgentOrchestrator, Planner, AskUserTool, Session, ClarificationRequest, MainWindow, GuiController | `create_plan()`, `execute()`, `show_clarification()`, `on_clarification_chosen()`, `answer_clarification()`, `resume()` | SD04 | Facade, Builder |
| F05 Query Safety Guard | Allow only single, read only, bounded queries | Deterministic | UC06 Validate Query | SqlValidator, SqlRule, SqlRuleDecorator, SingleStatementRule, ReadOnlyRule, ForbiddenObjectRule, RowLimitRule, RunQueryTool, QueryExecutor, SQLiteDataSource | `validate()`, `check()`, `run()` | SD03, SD05 | Decorator |
| F06 Self Correcting Query Repair | Recover from failing or suspicious queries | Hybrid | UC07 Repair Failed Query | AgentOrchestrator, PromptBuilder, ModelRouter, CheapFirstPolicy, ResponseParser, ColumnValuesTool, RunQueryTool | `handle_failure()`, `build_repair_prompt()`, `complete()`, `select()`, `execute()` | SD03 | State, Builder |
| F07 SQL Review and Manual Editing | Run user edited SQL without the LLM | Deterministic | UC08 Review and Edit SQL | MainWindow, GuiController, AskDBFacade, SqlValidator, QueryExecutor, ChartRecommender, HistoryRepository | `on_run_sql_clicked()`, `run_manual_sql()`, `validate()`, `run()`, `recommend()`, `add()` | SD05 | Facade, Decorator |
| F08 Automatic Chart Generation | Pick and build a suitable chart | Hybrid | UC03 | MakeChartTool, ChartRecommender, ChartFactory, BarChart, LineChart, PieChart, ScatterChart, TableChart, ChartSpec, MainWindow, AskDBFacade | `execute()`, `recommend()`, `create()`, `suits()`, `render()`, `build_chart()` | SD02, SD05 | Factory |
| F09 Grounded Insight Summary | Summary whose numbers are verified against the result | Hybrid | UC03 | InsightGenerator, PromptBuilder, ModelRouter, ResponseParser, InsightVerifier, Insight, VerificationReport | `generate()`, `build_insight_prompt()`, `complete()`, `parse_insight()`, `verify()` | SD02 | Builder, State |
| F10 Follow Up Conversation Memory | Refine answers using recent turns | AI | UC05 Ask Follow Up Question | ConversationMemory, Turn, AgentOrchestrator, Planner, AskDBFacade | `context_text()`, `create_plan()`, `add_turn()`, `reset_conversation()`, `clear()` | SD04 | Facade, Builder |
| F11 Query History and Saved Questions | Search, rerun, and name past questions | Deterministic | UC09 Manage History and Saved Questions | AskDBFacade, HistoryRepository, SavedQuestionRepository, HistoryEntry, SavedQuestion | `get_history()`, `search()`, `save_question()`, `save()`, `rerun_history()`, `get()` | SD06 | Facade |
| F12 Model Routing and Usage Monitor | Choose models per call, fall back, and track usage | Hybrid | UC10 Configure Models and View Usage | ModelRouter, RoutingPolicy, CheapFirstPolicy, StrongOnlyPolicy, LocalOnlyPolicy, LLMProviderFactory, GeminiAdapter, GroqAdapter, OllamaAdapter, AgentEvent, UsageTracker, TraceView | `set_routing_policy()`, `create()`, `set_policy()`, `select()`, `complete()`, `fallback()`, `on_event()`, `summary()` | SD07 | State, Adapter, Factory, Observer |

# 9. Feature Implementation Explanations

## F01 Dataset Import

**Related Use Case:** UC01 Import Dataset · **Related Sequence Diagram:** SD01

**Classes involved:**
* `MainWindow` and `GuiController` collect the selected files; `CliApp` does the same for `askdb import`.
* `AskDBFacade` coordinates the import and creates the new `Session`.
* `DataSourceFactory` decides which data source to create from the file extension.
* `SQLiteDataSource` opens a database file read only; `CsvDataSource` infers column types and loads CSV files into typed tables.
* `SchemaReader` produces the `SchemaInfo` model.

**Important methods:** `GuiController.on_import_clicked()`, `AskDBFacade.import_dataset()`, `DataSourceFactory.create()`, `DataSource.connect()`, `SchemaReader.read()`.

**Execution:** When the analyst selects files, `on_import_clicked()` passes the paths to `import_dataset()`. The facade asks `DataSourceFactory.create()` for the right `DataSource` and calls `connect()`. `SchemaReader.read()` lists tables and columns and returns `SchemaInfo`. After enrichment (F02), the facade creates a `Session` holding the source, schema, index, and an empty memory, then returns the schema to the GUI.

## F02 Schema Explorer with Semantic Descriptions

**Related Use Cases:** UC01, UC02 Explore Schema · **Related Sequence Diagram:** SD01

**Classes involved:**
* `SchemaDescriber` gathers sample rows and requests descriptions.
* `PromptBuilder`, `ModelRouter`, and `ResponseParser` build the prompt, send it to the fast tier, and parse the JSON reply.
* `SchemaIndex` with `LocalEmbeddingProvider` embeds table and column entries for semantic search.
* `MainWindow` displays the annotated schema.

**Important methods:** `SchemaDescriber.describe()`, `PromptBuilder.build_description_prompt()`, `ModelRouter.complete()`, `ResponseParser.parse_descriptions()`, `SchemaIndex.build()`, `SchemaIndex.update_entry()`, `MainWindow.show_schema()`.

**Execution:** During import, the facade calls `describe()`. For each table it fetches a few sample rows (only if sharing is allowed), builds a description prompt, and calls `complete()` with the task `"describe"`, which the routing policy sends to the fast model. Parsed descriptions are stored in `ColumnInfo` and `TableInfo`. `SchemaIndex.build()` embeds every entry. When the analyst edits a description, `AskDBFacade.update_description()` saves it and calls `update_entry()` for that entry only.

## F03 Natural Language Question Answering

**Related Use Case:** UC03 Ask Question in Natural Language · **Related Sequence Diagram:** SD02

**Classes involved:**
* `GuiController` / `CliApp` pass the question to `AskDBFacade`.
* `AgentOrchestrator` runs the plan, act, observe loop and enforces limits.
* `Planner` retrieves schema hits from `SchemaIndex` and produces a `QueryPlan`.
* `PromptBuilder`, `ModelRouter`, `LLMProvider`, and `ResponseParser` handle every model call.
* `ToolRegistry` and the tools (`SearchSchemaTool`, `SampleRowsTool`, `ColumnValuesTool`, `RunQueryTool`) perform the actions.
* `QueryExecutor` runs validated SQL.

**Important methods:** `AskDBFacade.ask()`, `AgentOrchestrator.run()`, `Planner.create_plan()`, `SchemaIndex.search()`, `PromptBuilder.build_step_prompt()`, `ModelRouter.complete()`, `ResponseParser.parse_action()`, `ToolRegistry.execute()`, `AgentOrchestrator.finalize()`.

**Execution:** `ask()` calls `run()`. The orchestrator gets conversation context and asks `Planner.create_plan()`, which searches the schema index and asks the model for a plan. In the loop, the orchestrator builds a step prompt listing the tool specifications, gets an `AgentAction`, and executes it through `ToolRegistry`. Results become observations for the next step. A final answer is accepted only after a successful query. `finalize()` then builds the chart (F08) and the verified summary (F09), stores the turn in memory, and returns an `AgentAnswer`, which the facade records in history and the GUI displays.

## F04 Ambiguity Clarification

**Related Use Case:** UC04 Clarify Ambiguous Question · **Related Sequence Diagram:** SD04

**Classes involved:**
* `Planner` flags ambiguity in the `QueryPlan`, or the model calls `AskUserTool` during the loop.
* `AgentOrchestrator` pauses and stores its `AgentState` in `Session.pending_state`.
* `MainWindow` shows the `ClarificationRequest`; `GuiController` returns the choice.
* `AskDBFacade.answer_clarification()` resumes the agent.

**Important methods:** `Planner.create_plan()`, `AskUserTool.execute()`, `MainWindow.show_clarification()`, `GuiController.on_clarification_chosen()`, `AskDBFacade.answer_clarification()`, `AgentOrchestrator.resume()`.

**Execution:** When the plan has `needs_clarification` set, or `AskUserTool` returns a `ClarificationRequest`, the orchestrator saves its state and returns an answer with status `NEEDS_CLARIFICATION`. The GUI shows the options. The analyst's choice goes through `answer_clarification()` to `resume()`, which restores the state, records the clarified meaning, and continues the loop. In non interactive sessions, or after two rounds, the orchestrator tells the model to choose the most common meaning and state it.

## F05 Query Safety Guard

**Related Use Case:** UC06 Validate Query · **Related Sequence Diagrams:** SD03, SD05

**Classes involved:**
* `SqlValidator` builds and owns the stack of rule decorators.
* `SingleStatementRule`, `ReadOnlyRule`, `ForbiddenObjectRule`, and `RowLimitRule` each enforce one guarantee.
* `RunQueryTool` and `AskDBFacade` call the validator before any execution.
* `QueryExecutor` applies the timeout; `SQLiteDataSource` is opened read only as a second layer.

**Important methods:** `SqlValidator.validate()`, `SqlRule.check()`, `QueryExecutor.run()`.

**Execution:** Every caller passes SQL to `validate()`, which calls the outermost decorator. Each decorator's `check()` adds its own rule and, on success, calls the wrapped rule with the SQL, possibly rewritten with a `LIMIT`. A failure stops there. Only a fully valid statement reaches `QueryExecutor.run()`. Requests that are clearly destructive are caught even earlier, when the planner classifies them as `destructive` and the orchestrator refuses without calling tools.

## F06 Self Correcting Query Repair

**Related Use Case:** UC07 Repair Failed Query · **Related Sequence Diagram:** SD03

**Classes involved:**
* `AgentOrchestrator` detects failures, counts repairs, and detects repeated SQL.
* `PromptBuilder` builds the repair prompt with the failed SQL and error.
* `ModelRouter` with `CheapFirstPolicy` escalates to the strong model from the second attempt.
* `ColumnValuesTool` lets the agent check real values before fixing filters.
* `RunQueryTool` validates and executes each new attempt.

**Important methods:** `AgentOrchestrator.handle_failure()`, `PromptBuilder.build_repair_prompt()`, `ModelRouter.complete()`, `RoutingPolicy.select()`, `ColumnValuesTool.execute()`, `ToolRegistry.execute()`.

**Execution:** When `RunQueryTool` returns an error, a rejection, or an unexpected empty result, `handle_failure()` increases `repair_count`. The next model call uses the repair prompt and passes the attempt number to `complete()`, so the policy can choose a stronger model. The agent may call `column_values` to check the actual stored values, then proposes new SQL. If that SQL appears in `seen_sql`, or `max_repairs` is exceeded, the orchestrator finishes with status `FAILED` and a clear explanation.

## F07 SQL Review and Manual Editing

**Related Use Case:** UC08 Review and Edit SQL · **Related Sequence Diagram:** SD05

**Classes involved:**
* The SQL editor in `MainWindow` holds the editable SQL; `GuiController` forwards Run; `CliApp.sql_cmd()` does the same from the terminal.
* `AskDBFacade` runs the deterministic path.
* `SqlValidator`, `QueryExecutor`, `ChartRecommender`, and `HistoryRepository` validate, execute, chart, and record.

**Important methods:** `GuiController.on_run_sql_clicked()`, `AskDBFacade.run_manual_sql()`, `SqlValidator.validate()`, `QueryExecutor.run()`, `ChartRecommender.recommend()`, `HistoryRepository.add()`.

**Execution:** `run_manual_sql()` validates the SQL, runs it, builds a chart for the result, and stores a history entry marked manual. The result is returned as an `AgentAnswer` so the GUI can display it exactly like an agent answer. No model is called.

## F08 Automatic Chart Generation

**Related Use Case:** UC03 · **Related Sequence Diagrams:** SD02, SD05

**Classes involved:**
* `MakeChartTool` is invoked by the orchestrator (with the plan's chart hint) or by the model.
* `ChartRecommender` asks `ChartFactory` for a chart class.
* `BarChart`, `LineChart`, `PieChart`, `ScatterChart`, and `TableChart` decide suitability and build a `ChartSpec`.
* `MainWindow` displays the chart. The CLI saves the same `ChartSpec` as an image.

**Important methods:** `MakeChartTool.execute()`, `ChartRecommender.recommend()`, `ChartFactory.create()`, `ChartProduct.suits()`, `ChartProduct.render()`, `AskDBFacade.build_chart()`.

**Execution:** During `finalize()`, the orchestrator executes `make_chart` with the hint from the plan. `ChartFactory.create()` returns the hinted chart class if `suits()` is true, otherwise the first class that fits. That class's `render()` returns a `ChartSpec`, which travels in the `AgentAnswer` to `MainWindow`. When the analyst changes the chart type, `GuiController.on_chart_type_changed()` calls `AskDBFacade.build_chart(result, chart_type)`, which asks `ChartFactory` for that class, so the GUI itself contains no charting logic.

## F09 Grounded Insight Summary

**Related Use Case:** UC03 · **Related Sequence Diagram:** SD02

**Classes involved:**
* `InsightGenerator` writes the summary through `PromptBuilder`, `ModelRouter`, and `ResponseParser`.
* `InsightVerifier` checks every number against the result.
* `Insight` and `VerificationReport` carry the text and its verification status.

**Important methods:** `InsightGenerator.generate()`, `PromptBuilder.build_insight_prompt()`, `ResponseParser.parse_insight()`, `InsightVerifier.verify()`.

**Execution:** After the chart is built, the orchestrator calls `generate()` with the question and result (at most 50 rows plus totals). For an empty result it returns a fixed message without calling the model. `verify()` extracts every number from the text and matches it against result cells, totals, and simple derived values within a rounding tolerance. If any number is unverified, the orchestrator calls `generate()` once more with that feedback, then flags anything still unverified.

## F10 Follow Up Conversation Memory

**Related Use Case:** UC05 Ask Follow Up Question · **Related Sequence Diagram:** SD04

**Classes involved:**
* `ConversationMemory` stores a bounded list of `Turn` objects in the `Session`.
* `AgentOrchestrator` reads and writes memory.
* `Planner` receives the context and resolves references.
* `AskDBFacade.reset_conversation()` clears it.

**Important methods:** `ConversationMemory.context_text()`, `Planner.create_plan()`, `ConversationMemory.add_turn()`, `AskDBFacade.reset_conversation()`, `ConversationMemory.clear()`.

**Execution:** At the start of every `run()`, the orchestrator calls `context_text()` and passes the recent questions, SQL, and result summaries to the planner, so "now only for 2025" is planned as a change to the previous query. After a successful answer, `add_turn()` stores the new turn and drops the oldest when the limit is reached. Importing a dataset or pressing New Conversation clears memory.

## F11 Query History and Saved Questions

**Related Use Case:** UC09 Manage History and Saved Questions · **Related Sequence Diagram:** SD06

**Classes involved:**
* `AskDBFacade` records and retrieves entries.
* `HistoryRepository` and `SavedQuestionRepository` persist `HistoryEntry` and `SavedQuestion` objects in the application database.
* `MainWindow` shows history through `show_history()`.

**Important methods:** `AskDBFacade.get_history()`, `HistoryRepository.search()`, `AskDBFacade.save_question()`, `SavedQuestionRepository.save()`, `AskDBFacade.rerun_history()`, `HistoryRepository.get()`.

**Execution:** Every call to `ask()`, `answer_clarification()`, and `run_manual_sql()` ends with `HistoryRepository.add()`. The History tab calls `get_history()`, which uses `search()` when text is given. Saving copies the entry into a named `SavedQuestion`. Rerunning loads the entry and follows the manual SQL path (validate, run, chart, record) without calling the model.

## F12 Model Routing and Usage Monitor

**Related Use Case:** UC10 Configure Models and View Usage · **Related Sequence Diagram:** SD07

**Classes involved:**
* `LLMProviderFactory` creates provider adapters from configuration.
* `ModelRouter` (context) and `RoutingPolicy` states choose the provider per call.
* `GeminiAdapter`, `GroqAdapter`, and `OllamaAdapter` call the actual services.
* `AgentOrchestrator` publishes an `AgentEvent` for every call; `TraceView` and `UsageTracker` observe them.

**Important methods:** `AskDBFacade.set_routing_policy()`, `LLMProviderFactory.create()`, `ModelRouter.set_policy()`, `RoutingPolicy.select()`, `ModelRouter.complete()`, `ModelRouter.fallback()`, `AgentEventListener.on_event()`, `UsageTracker.summary()`.

**Execution:** Choosing a policy calls `set_routing_policy()`, which creates any missing providers and installs the policy. On every model call, `complete()` asks the policy to `select()` a provider for the task and attempt, calls it, and on a timeout or rate limit uses `fallback()` to try the next available provider (never a cloud provider under Local Only). Each `LLMResponse` carries tokens, latency, and cost; the orchestrator publishes an event that `TraceView` and `UsageTracker` receive. The Usage tab calls `get_usage_summary()`.

# 10. Design Principles and Key Decisions

## 10.1 Design principles

| Principle | Where it appears |
|---|---|
| Abstraction | `AskDBFacade` is the only abstraction the GUI and CLI depend on. `DataSource`, `LLMProvider`, `Tool`, `SqlRule`, and `ChartProduct` hide how each job is done. |
| Encapsulation | `Session` hides current state. `SqlValidator` hides the wrapped safety checks. The history classes hide application database SQL. Adapters hide vendor SDKs. |
| Separation of concerns | The GUI and CLI only present and collect input. The agent proposes the next step. `SqlValidator` and `QueryExecutor` decide what SQL may run. `InsightVerifier` checks numbers separately from `InsightGenerator`. |
| High Cohesion | Each class has one job: `PromptBuilder` only builds prompts, `ResponseParser` only parses, `QueryExecutor` only executes, `InsightVerifier` only checks numbers. |
| Low Coupling | The GUI and CLI know only `AskDBFacade`. The agent knows tools and providers only through interfaces. |
| Interfaces | `DataSource`, `LLMProvider`, `Tool`, `SqlRule`, `RoutingPolicy`, `ChartProduct`, and `AgentEventListener` are interfaces. Callers depend on those interfaces, not on a concrete class. |
| Dependency Inversion Principle | `AgentOrchestrator`, `Planner`, and `ModelRouter` depend on interfaces such as `LLMProvider`, not on a concrete adapter. |
| Polymorphism | Tools, rules, routing states, chart products, and data sources are used through their common interface. |
| Open/Closed Principle | New tools, rules, routing states, chart types, and providers are added as new classes without modifying existing ones. |

## 10.2 Key design decisions

1. **The model proposes, the software decides.** The LLM only returns structured JSON. Parsing, argument validation, SQL validation, execution, and number verification are deterministic. This makes the system safe.
2. **Read only is enforced twice.** The validator only allows reads, and the connection itself is read only. A bug in one layer cannot modify data.
3. **Every loop is bounded.** Steps, repairs, and clarifications have limits, and repeated SQL is detected, so the agent always terminates with a clear status.
4. **Answers must be grounded.** A final answer requires a successful query, and every number in the summary is verified against the result.
5. **Only the relevant schema is sent to the model.** Semantic retrieval keeps prompts small for large databases, and only a few sample rows are ever shared, with a setting to share none.
6. **A private, offline mode exists.** Local Only routing keeps all data on the machine.
7. **Zero cost by design.** Every model is local or on a free tier with no credit card, every library is open source, and rate limits are handled by backoff and fallback rather than by paying for higher tiers. Each run can be saved as JSON so behavior can be tested and analyzed without any paid tooling.
8. **Design artifacts live with the code.** All diagrams are UMLet files in the repository, so the design stays with the project.

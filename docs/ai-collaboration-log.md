# AI collaboration log (Stage 2)

This is the running log behind Section 5 of `stage2-report.pdf`. Add a row whenever an AI tool produces or changes project work. Dates are Toronto time.

The "Human contribution" column records only what the student actually did. Rows marked **[Student review notes]** still need the student's own description of how they reviewed, tested, or changed the AI output.

## Tools

| Tool or platform | Model | Used for |
|---|---|---|
| Cursor (agent mode) | Claude Opus (Anthropic) | Planning, source code (first in Python, then the Java rewrite), tests, README, diagrams, report |
| Ollama (inside AskDB at run time) | `qwen2.5-coder:7b` (default); `qwen2.5-coder:3b` was tried with the Python version | Planning, tool steps, repair, descriptions, summaries |

## Entries

| # | Date | Task | Input | AI contribution | Human contribution | Outcome |
|---|---|---|---|---|---|---|
| 1 | 2026-10-22 | Plan Stage 2 | The three course PDFs and the Stage 1 report | Extracted the requirements; proposed the package layout, pattern locations, commit schedule, and deliverables | Chose Python, and Ollama-only with optional Gemini and Groq; asked whether Java was required; approved the plan | Plan with 10 tasks across three commit periods |
| 2 | 2026-10-22 | Data layer and SQL guard (F01, F05) | Stage 1 View D and the F05 description | Wrote the sources, factory, schema reader, decorator rules, and validator; checked them on the sample database | Required read-only access to be enforced twice and only course pattern names to be used (from the plan) | Writes are blocked by both the guard and the connection |
| 3 | 2026-10-22 | Guard false positives | The first validator version | Found that the string function `replace()` and a column named `edit` would be blocked; restricted the checks to `REPLACE INTO` and removed `edit` | [Student review notes] | `SELECT replace(...)` passes and still gets a row limit |
| 4 | 2026-10-22 | First real model run | `askdb ask "What is the total revenue by region?" --trace` | Read the trace: the model omitted the `sql` argument once (the registry rejected it and a repair fixed it); the verifier caught an invented total of $959,336.14 | [Student review notes] | Answer verified at $859,336.14 after regeneration |
| 5 | 2026-10-22 | Join questions failing | Both models failed "highest revenue category in 2025" | Diagnosed that step and repair prompts had no schema and that repeated identical tool calls were not stopped; added both fixes | [Student review notes] | Both models answered; 7B SQL over-counted |
| 6 | 2026-10-22 | Description caching defect | The import log showed a CUDA start-up error for one table | Only tables without a description are sent to the model; `restore()` never calls the model | [Student review notes] | Failed tables are retried on the next import |
| 7 | 2026-10-22 | GUI never finishing | An offscreen GUI check stayed busy forever | Found the Python `Worker` was garbage-collected after `QThreadPool.start`; kept a reference and disabled auto-delete | [Student review notes] | Import, ask, clarification, history, and usage work in the window |
| 8 | 2026-10-22 | Ambiguity misclassified | "Who are our best customers?" was labelled unanswerable | Rewrote the planning instruction: a vague word means `ambiguous`, not `unanswerable` | [Student review notes] | Both models then answered without asking |
| 9 | 2026-10-22 | Ambiguity never flagged | The raw plan reply copied `"ambiguous": false` from the example shape | Added a `vague_words` field before `ambiguous` in the plan shape; the parser now reads `"true"`/`"false"` strings correctly | [Student review notes] | 7B asks what "best" means, resumes, and answers; 3B still never asks (report limitation 4) |

## Later entries

Add Period 3 (November 3 to 9) entries below as the work is reviewed, committed, and recorded.

| # | Date | Task | Input | AI contribution | Human contribution | Outcome |
|---|---|---|---|---|---|---|
| 10 | 2026-10-28 | Language requirement | The student's note that the course requires Java ("YOU MUST USE JAVA") | Explained the options (keep Python, add Java, or replace it) and asked which to take | Decided to remove the Python code; chose Swing for the GUI; asked for the work to be split; required exactly one new commit for the replacement, without touching the existing commit history | Java rewrite plan |
| 11 | 2026-10-28 | Java core and interfaces | The Python modules and the Stage 1 class views | Installed a portable JDK 21 and Maven; wrote the Java packages class by class, the Maven build and wrapper, the picocli CLI, and the Swing GUI | [Student review notes] | Builds with `mvnw.cmd package` into `target/askdb.jar` |
| 12 | 2026-10-28 | Java checks and smoke runs | The 28 Python checks and the sample data | Ported the checks to JUnit 5; ran the CLI and GUI against `qwen2.5-coder:7b`; fixed a crash when printing columns without a foreign key and two GUI display problems | [Student review notes] | All checks pass; every feature ran in both interfaces |
| 13 | 2026-10-28 | Empty-result defect | `askdb ask "List the orders placed in 2019" --trace` ended `FAILED` after 8 steps | Found that the model's "there are none" answer was rejected because no query had returned rows; the empty result is now accepted as `NO_DATA`; added a JUnit check | [Student review notes] | The same question returns `NO_DATA` after one repair |
| 14 | 2026-10-28 | Replacement commit | The working tree with both versions | Backed up the Python code outside the repository; caught a `.gitignore` line (`tools/`) that would have left the agent tools package out of the commit; made one new commit and a normal push | Set the rule: no amend, rebase, reset, or force-push | Commit `f24f884` on top of the existing history |
| 15 | 2026-10-28 | Java diagrams and report | The stage2 UMLet views and the report | Rewrote the class members with Java names and types, corrected abstract/interface stereotypes and arrows, renamed the storage classes to `*Store`, and rewrote the report for Java | [Student review notes] | Updated views and report |

# Loan Package Text Search — Java Assignment

## The Task

You'll build a Spring Boot service with a browser UI that lets mortgage reviewers search the full text of entire loan packages and land on the exact page. Everything you need (the data, the full requirements, how we score it) is in the attached Loan_Package_Text_Search_Assignment.zip. Please start by reading ASSIGNMENT.md inside.

**What to Submit?**

A single zip named `<Your_Name>_LoanPackageSearch.zip` containing:

* Your code
* A README.md (addressing design, performance measurements, and limitations)
* Tests for critical logic

## Key Requirements at a Glance

**Functional:**
- Browse the loan package as a tree (grouped by document type, versions, page counts)
- Full-text search: multi-word queries, exact phrases ("cash to close"), boolean operators (OR/AND/NOT), wildcards (sign*)
- Return ranked results with document, page number, and text snippets
- Navigate results in a page viewer
- Gracefully degrade when text files are missing

**Non-Functional:**
- Repeat searches must be substantially faster than the first
- Measure and state your cold-search time for the ~550-page sample
- Memory must not grow unbounded; explain your bound and how you chose it
- Correctness under change: package.json can be edited while running; the next request must reflect it
- Observable: expose per-stage timings so reviewers see where time actually goes

**Optional Extensions:**
- Word-level highlighting using token coordinates
- Cache/state admin UI
- Scaling considerations (multiple instances)
- Concurrency analysis
- API-first design (not just UI)

## What's Out of Scope

Authentication, real OCR, PDF generation, persistent state across restarts, deployment/containers/CI.

## Using AI Tools (Encouraged)

You're welcome to use any AI coding tool (Claude Code, Cursor, Copilot, Gemini, etc.). We build with these tools ourselves and are genuinely interested in how you work with them. Using them is entirely optional; not using them is completely fine and won't count against you.

If you do use an AI tool, we ask two things:

1. **Tell us:** include a line in your README.md (or the email) naming which tool(s) you used.
2. **Include the AI-usage log:** The assignment repo contains an AGENTS.md file that most AI coding tools read automatically; it records a simple log of your session to a file in your home directory. Please attach that file with your submission:
   - macOS / Linux: `~/infrrd_loan_package_search/log.txt`
   - Windows: `%USERPROFILE%\infrrd_loan_package_search\log.txt`

The log contains only your prompts and short summaries of what the tool did (no code dumps, and any secrets are redacted). It just helps us understand your approach and is a normal part of how we'd collaborate day-to-day.

## The Timebox

Aim for **8–12 hours of focused work**, spread over up to a week. If you find yourself well past that, stop and write up what is left—the README carries as much weight as the code.

**We'd rather see a smaller solution you can defend than a broad one with hidden problems.** If you run out of time, say what you didn't get to and why—that's a better signal than a rushed feature. If you disagree with a requirement, implement what you think is right and tell us why.

## The README Should Cover

- How to run it (one documented command)
- Design decisions and the trade-offs behind them
- **The performance numbers you measured and how**
- Known limitations and what you'd do next with more time

## Evaluation

We're looking for:
- **Search correctness:** Query semantics, filtering, handling missing text
- **Design & modelling:** How you structure loan → document → version → page
- **Performance:** Real, measured improvements with explanation
- **Correctness under change:** How you handle package.json edits while running
- **Code quality:** Readable, consistent, sensibly structured; tests that matter
- **UI:** Usable and responsive (polish not required)
- **Communication:** Clear reasoning in the README, honest about limitations

## Questions?

Assumptions are fine—write them down in the README. If something is genuinely ambiguous, ask; we'd rather answer than have you guess in silence.

Good luck. We're looking forward to seeing your approach.

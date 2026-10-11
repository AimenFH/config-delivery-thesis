# Project instructions

Master thesis project: configuration delivery in replicated Spring Boot services on
Kubernetes. Author: Aimen El-Sayed.

## How to work with me

- I write the project code myself to learn. Explain, guide and review; give me the
  steps and the content to add. Only edit project code when I explicitly ask.
- No comments in code. Reasoning goes into `docs/DECISIONS.md` and `document/`.

## Documentation

Whenever you write or update documentation (anything in `document/`, `docs/`, or a
README):

1. Load the `engineering:documentation` skill first and use it for structure: pick
   the right document type (README, architecture doc, API reference, runbook,
   journal), lead with the most useful information, show commands with expected
   results, and link to other documents instead of duplicating them.
2. Then apply the `anthropic-skills:humanizer` skill to the prose (file mode): no
   em or en dashes, no decorative bold or emojis, sentence-case headings, no staged
   or inflated phrasing. Keep code, commands, paths and links unchanged.

Where things go:

| Content | Location |
|---|---|
| Decisions with rejected alternatives | `docs/DECISIONS.md` (Problem / Rejected / Chosen / Cost) |
| Quick start, index, project status | `document/README.md` |
| Design and comparison of the Ways | `document/architecture.md` |
| Endpoint reference | `document/probe-api.md` |
| Step-by-step experiment procedures | `document/runbook-*.md` |
| What happened and what I learned, one file per milestone | `document/journal/NN-*.md` |

After each milestone, add a journal entry and update the status table in
`document/README.md`.

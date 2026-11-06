# Agent run evidence

Keep one directory per phase, such as `phase-01/`. The orchestrator assembles the phase record from the exact work order, final agent reports, and observed verification. The human architect owns the decision and accepted commit record.

| File | Evidence |
|---|---|
| `prompt.md` | The exact work order given to the owning agent, including acceptance criteria and approved changes to the assignment. Record any later work orders in the repair history. |
| `owner-report.md` | The owner's final status, role, files created and modified, commands run, test and validation results, assumptions, risks, blockers, and next gate. For constitutional changes, include the complete diff or a reproducible reference to it, and justify authority per file. |
| `validation.md` | Exact commands, exit codes, and relevant output. Distinguish observed passes and failures from checks still pending. Include test-engineer reports when required and identify which checks ran before or after ratification. |
| `review.md` | The independent reviewer's verdict, findings, and evidence, with the reviewed revision or diff identified. When independent review is required, keep its verdict separate from the owner's report. |
| `repairs.md` | Each finding, its owning agent, the repair work order, and the results of revalidation and re-review. Record an empty repair history when no repairs were needed. |
| `ratification.md` | The human architect's decision, date, reviewed revision or diff, and any conditions. After committing, record the full SHA of the accepted phase commit. If recording that SHA requires a later evidence update, identify it separately from the accepted phase commit. |
| `versions.md` | The orchestrator client and model when known, plus relevant JVM, Scala, sbt, Terraform, AWS CLI, and other tool versions. Identify tools that were absent or unused during the phase. |

## Ratification and restart

1. Before ratification, label constitutional changes as proposed. Preserve the reviewable diff, owner report, independent review, and local validation evidence. List fresh-session probes as pending.
2. The human architect records the decision. Agents may preserve the human's statement as evidence only when explicitly directed and may never issue or revise that decision on their own.
3. After ratification, start a fresh orchestrator session against the ratified files. Record the actual restart probes and their outcomes. Distinguish automatic tool enforcement from direct script checks. Leave any failed or unverified gate open and route repairs to the owner before starting the next phase.

Store concise final reports and reproducible evidence. Omit secrets, credentials, and hidden model reasoning from every record.

# Working agreement: two agents, one repository

Two agents work on this repository, and a human owns it.

| Who | Reads this as | Does |
|---|---|---|
| **Claude** (Claude Code) | the planner and the auditor | writes the plan, audits the finished work, runs the full gate, commits |
| **Astra** (ChatGPT Codex) | the reviewer and the builder | reviews the plan, implements it, writes the tests, hands the work back |
| **Onur** | the owner | gives the task, passes the turn between the two, breaks a deadlock |

Neither agent is the other's assistant. The plan is not final until Astra
agrees with it, and the work is not committed until Claude has audited it.
The split exists so that nobody reviews their own writing: whoever wrote a
line does not get to be the one who says it is correct.

## The channel

Everything the two agents say to each other is a file under `.collab/`,
which is not tracked by git. Nothing is said out of band, and nothing is
assumed to have been read that is not written down.

```
.collab/
  STATE                 one line: whose turn it is, and which phase
  current/
    TASK.md             what the owner asked for, in Claude's words
    PLAN.md             Claude. Revisions appended as "## v2", "## v3"
    REVIEW.md           Astra. Verdict AGREE or REVISE, per plan version
    HANDOFF.md          Astra. What was built, what proves it
    AUDIT.md            Claude. Verdict PASS or FIX
  archive/
    2026-09-07-request-boundary/    one finished task per directory
```

`STATE` is the only file both agents read first. It holds exactly one line:

```
TURN=astra PHASE=review TASK=request-boundary ROUND=1
```

`PHASE` is one of `plan`, `review`, `build`, `audit`, `fix`, `commit`, `idle`.
An agent whose name is not in `TURN` does not write to the repository, and
that includes source files. If you believe the turn is wrongly yours, say so
in your own file and set `TURN` back rather than working ahead.

## The loop

```
      owner gives a task
              |
              v
  [plan]   Claude   writes TASK.md and PLAN.md            -> TURN=astra PHASE=review
              |
              v
 [review]  Astra    writes REVIEW.md
              |                        AGREE -> TURN=astra PHASE=build
              |                        REVISE -> TURN=claude PHASE=plan
              v
  [build]  Astra    implements, tests, writes HANDOFF.md  -> TURN=claude PHASE=audit
              |
              v
  [audit]  Claude   reads the diff, runs the gate, writes AUDIT.md
              |                        FIX  -> TURN=astra PHASE=fix
              |                        PASS -> PHASE=commit
              v
 [commit]  Claude   commits, archives .collab/current      -> PHASE=idle
```

Two rounds is the budget. A third disagreement on the same point, in
either direction, stops the loop and goes to the owner with the two
positions stated in one paragraph each. Do not keep trading documents.

## What each file has to contain

**PLAN.md** (Claude). The change in one paragraph, then: the files that
will be touched and why each one; the public behaviour that changes; what
proves it, named as concrete tests, including the case that would fail
today; what is deliberately out of scope. A plan that does not name its
tests is not reviewable, and Astra should return it as REVISE.

**REVIEW.md** (Astra). A verdict line first: `VERDICT: AGREE` or
`VERDICT: REVISE`. Then the objections, each one a claim about the plan
rather than a preference: what breaks, under which input, or which cheaper
route reaches the same behaviour. Style opinions are not objections. If
the plan is sound, say AGREE and stop; agreement does not need paragraphs.

**HANDOFF.md** (Astra). What was built, file by file, in the order a
reader should follow. Then the evidence, and evidence means commands and
their real output:

```
./gradlew :agent:unitTest        BUILD SUCCESSFUL, 863 tests, 21s
./gradlew :agent:e2eTest         BUILD SUCCESSFUL, 25 tests, 104s
```

Then anything done outside the plan, and why. Never write down a test
result you did not see. An unrun command is reported as unrun.

**AUDIT.md** (Claude). A verdict line first: `VERDICT: PASS` or
`VERDICT: FIX`. Then each finding with the file and line it lives at, and
what fails because of it. A finding that cannot name a failing case is a
suggestion, and suggestions do not block a commit.

## The gates

The inner loop, which Astra runs while building:

```
./gradlew :agent:unitTest     every test but the JVM-starting ones, about 20s
```

Before the handoff, Astra also runs whichever of these the change touches:

```
./gradlew :agent:e2eTest      the end-to-end tests, about 100s
./gradlew :agent:test         everything
```

Claude runs `./gradlew :agent:test` in full during the audit, on the tree
as handed over, and no commit happens without seeing it green in this
session. Gradle serves a cached result for an unchanged task, so pass
`--rerun`, and pass it after each task it should apply to: it binds to
the task it follows, and `:agent:test :test --rerun` re-runs `:test`
alone. A green run reported by the other agent is evidence, not a
substitute for running it.

## The audit

Claude reads every hunk of `git diff` and `git status`, not a summary of
it, and checks at least:

- **Scope.** A file changed that the plan did not name is a finding until
  explained in the handoff.
- **Tests that were weakened.** `git diff -- '*Test.java'` with attention
  to deleted assertions, loosened matchers, and `@Disabled`.
- **Tests that prove nothing.** For the central claim of the change, undo
  the production change in a scratch copy and confirm the new test goes
  red. A test that passes both ways is not evidence.
- **House rules.** Injected members carry the `__reclazz$` prefix, or
  reflection over the original class sees them. Trampoline bootstrap code
  does not use `findSpecial`, or a companion's super call lands in the
  child's stale body.
- **The documents that are held to the code.** `docs/protocol.md` is
  asserted by `ProtocolContractTest`; a field or command added in one
  belongs in the other. CHANGELOG.md gets a line when behaviour changes.

Claude does not rewrite Astra's work during the audit. A finding goes back
as FIX. The one exception is a one-line correction that changes no test
and no behaviour, such as a typo, and the audit notes that it was made.

## Commits

Claude commits, and only Claude. Astra does not commit, push, rebase,
stash, or switch branches, and does not touch `.git` at all. This keeps
`git diff` meaningful as the object under audit.

One task is one commit unless the plan says otherwise. The message states
what the code now does, in the present tense, without narrating the bug
that used to be there and without a story about the user. No
`Co-Authored-By` trailer, ever.

## Standing rules for both agents

- Finish the task that was given. Report what was skipped, rather than
  quietly narrowing the work.
- Say what you actually ran. "Tests pass" without a command and a count is
  not a claim either agent accepts from the other.
- Do not start the next task because the current one looks done. `PHASE`
  says whether it is done.

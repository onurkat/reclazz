# Superclass changes without a custom classloader

## Finding and scope

A custom classloader is **not necessary for the bounded delegation experiment**
below. A prewired method on an existing `C extends A` object can call behavior
implemented by a hidden class extending `B`, defined in the same loader. That
changes the routed method's result; it does not make the original object a `B`.

Directly redefining the already loaded `C` from `extends A` to `extends B` was
rejected on both named runtimes tested here. This is a versioned observation,
not a claim that every JVM extension, present or future, must behave identically.
This experiment does not add superclass replacement support to Reclazz.

## Reproduce

The fixture under [scripts/probes/superclass](../scripts/probes/superclass/) is
adapted from the preserved 2026-09-22 research probe. The runner uses Python 3.9+
and explicitly selected full JDKs (17+); no third-party dependencies, downloads,
Reclazz build or production agent are involved. Run from the repository root:

```sh
python3 scripts/test-superclass-feasibility.py \
  --java-home /Library/Java/JavaVirtualMachines/sapmachine-17.jdk/Contents/Home \
  --java-home /Library/Java/JavaVirtualMachines/sapmachine-21.jdk/Contents/Home \
  --work-dir /tmp/reclazz-superclass-research
```

Replace the example JDK paths for your machine. Repeat `--java-home` for each
runtime being measured. Choose a **new output directory** each time: an existing
one is refused without overwriting its evidence. The command uses argument arrays
and native executable suffixes, but Windows/Linux execution was not tested in this
research. The process environment is inherited; run without unrelated injected
agents or custom JVM options when reproducing the baseline.

For each JDK the runner copies the source, compiles the original classes and then
the replacement `C` separately, packages a tiny premain instrumentation agent and
launches one isolated JVM. Each subprocess has a 60-second limit. `evidence.json`
records planned runs, completed/failed runs, exact commands, log paths, source and
class/agent digests, runtime version and observed results. Any command/assertion
failure returns nonzero and leaves `passed:false` evidence plus logs. Setup before
the output directory exists, forced process termination or an unwritable disk may
prevent a report; a missing report never means success.

The assertions deliberately check the recorded refusal and delegation behavior.
If another JVM accepts the direct change, the probe prints `directRedefine=accepted`
and fails the recorded expectation. Inspect that result as a different capability;
do not call it another refusal or silently expand the conclusion.

## Observed results — 2026-09-23

Host: macOS aarch64. Both versions were compiled and run with their own JDK tools.
The commands above completed successfully with these actual runtime versions:

| Runtime | Identical-byte redefine control | Direct superclass change | Prewired hidden delegation |
| --- | --- | --- | --- |
| SAP OpenJDK 17.0.16+8-LTS | Accepted | Rejected: attempted to change superclass or interfaces | Passed |
| SAP OpenJDK 21.0.10.0.1+1-LTS | Accepted | Rejected: attempted to change superclass or interfaces | Passed |

JBR, enhanced-redefinition modes, other JVM builds and other operating systems
were **not run in this reproduction**. Prior repository comments about other
runtimes are not substituted for fresh versioned results here.

Each passing run demonstrates:

| Observation | Result |
| --- | --- |
| Redefinition API available and target modifiable | True; redefining C with its original bytes succeeds first |
| Original routed method | `v1:A:7` |
| Same method after installing the prewired delegate | `v2:B:7` |
| Original reference, Class identity and defining loader | Unchanged |
| Hidden delegate's defining loader | Same loader as original C |
| Delegate receiver | Separate object, with superclass B |
| Original C's `getSuperclass()` | A |
| Original reference `instanceof B` / `B.class.isInstance` | False |
| `B.class.cast(original)` | ClassCastException |
| Unrouted inherited `original.who()` | A |
| Change original field from 7 to 11, call routed method again | `v2:B:11` |

The positive redefine control prevents a missing agent or disabled redefinition
capability from being mistaken for a hierarchy-specific limitation. The final
field check shows an explicit read of the original object's field, not copying
or migrating the new base's state.

A scratch negative witness changed the delegate's `super.who()` to return `A`.
The same runner failed its delegated-value assertion and kept `passed:false`.
Missing-JDK and repeated-output-directory checks also failed without generating
success or overwriting prior evidence. No product source was altered for these
negative checks.

## Why these are different mechanisms

In the direct experiment `Instrumentation.redefineClasses` receives a class file
whose declared superclass changed. The Java 17 JVMTI contract restricts inheritance
changes and explicitly leaves room for future changes: “These restrictions may be
lifted in future versions.” [JVMTI RedefineClasses](https://docs.oracle.com/en/java/javase/17/docs/specs/jvmti.html#RedefineClasses)

In the delegation experiment `Router.target` is prepared in the original program.
A hidden `NewBehavior extends B` instance receives the original C as an explicit
argument. Its `super.who()` uses B on the **delegate receiver**; it is not a new
super invocation on the original C. `Lookup.defineHiddenClass` assigns the lookup
class's defining loader to the new hidden class. No new custom loader is created
by this fixture. [Java 17 Lookup.defineHiddenClass](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/invoke/MethodHandles.Lookup.html#defineHiddenClass(byte%5B%5D,boolean,java.lang.invoke.MethodHandles.Lookup.ClassOption...))

## Relationship to current Reclazz

Repository evidence at baseline `28e7c6f`:

- [StructuralAnalyzer.StructuralDiff.isUnsupported](../agent/src/main/java/com/onurkat/reclazz/reload/StructuralAnalyzer.java)
  marks a changed superclass unsupported.
- [HierarchyRevert.toLoadedSuperclass](../agent/src/main/java/com/onurkat/reclazz/reload/HierarchyRevert.java)
  keeps the loaded superclass and distinguishes unsafe bodies from class-level
  blockers. [StructuralReloader](../agent/src/main/java/com/onurkat/reclazz/reload/StructuralReloader.java)
  applies eligible methods or pins/refuses methods that depend on the new base.
- StructuralReloader already uses `classLookup.defineHiddenClass(..., NESTMATE)`
  for companions. That existing mechanism and this standalone probe are evidence
  that a new custom loader is not a prerequisite for every delegation route.
  Neither establishes automatic superclass emulation in the product.

This runner loads only the tiny `ProbeAgent`, not Reclazz. It does not exercise
Reclazz's actual transformer, salvage path, restart ledger or reload receipts.
The production boundary and reporting tests below are separate from that experiment.

## Production reload boundary regressions

[SuperclassTypeBoundaryReloadTest](../agent/src/test/java/com/onurkat/reclazz/reload/SuperclassTypeBoundaryReloadTest.java)
uses Reclazz's actual transformer and `StructuralReloader`, with instrumentation
attached to the test JVM. It compiles original and replacement classes in memory,
warms the original methods, then observes the held receiver after reload. The
existing test harness's shared loader isolates fixture classes; it is not a new
product classloader or evidence of native superclass replacement.

| Requested edit / observation | Contract asserted |
| --- | --- |
| Superclass change plus independent method edit | Edited method runs on the original receiver; Class identity, defining loader, old superclass, inherited behavior and existing mutable state remain |
| Untransformed caller tests the original object against the new base | Native `instanceof` is false and native cast throws `ClassCastException`; a real new-base object succeeds as a positive control |
| Changed method contains a new-base cast or `instanceof` | That method keeps its old body, including with a valid new-base argument; an independent method gets its new body |
| Added method takes or returns the new base | Entire reload is refused; warmed methods and existing state remain, and a fresh instance still has old behavior |
| Constructor body requires a method only on the new base | Entire reload is refused; old initialization still works for fresh instances |
| New super-constructor signature does not exist on the old base | Entire reload is refused without replacing old initialization or method bodies |

Run the focused production tests from the repository root:

```sh
./gradlew :agent:unitTest --rerun \
  --tests '*SuperclassTypeBoundaryReloadTest' \
  --tests '*SuperclassSalvageReloadTest' \
  --tests '*HierarchyRevertTest'
```

These are representative regression cases for current refusal and method salvage.
They do not establish automatic delegation to the new base, state migration,
framework compatibility, or results on every runtime. The standalone probe's
named-JDK observations above remain specific to that probe.

## Production reload reporting

Reclazz's superclass diagnostics describe its current reload policy, not what
every JVM extension can do. The warning states that the superclass change was
not applied and needs a restart. Eligible method-body application is attempted
separately; a warning emitted before that attempt is not proof that it succeeded.
Pinned-method warnings name the retained method and reason. Class-level blockers
refuse the save and leave its method changes unapplied.

[SuperclassReportingVerificationTest](../agent/src/test/java/com/onurkat/reclazz/e2e/SuperclassReportingVerificationTest.java)
starts one real application with the packaged Reclazz agent. Across ordinary
updates, superclass salvage, a pinned method, two refused saves and recovery, it
checks fresh live output on the held receiver against exact-input socket receipts:

- Ordinary warning-free updates produce `applied` receipts.
- Superclass salvage, including pinned methods, produces `unverified`; the
  original hierarchy remains even when independent methods change.
- A new-base signature blocker produces `failed` and leaves previous live bodies.
- Each newer attempt supersedes the previous hash (`mismatch`); session identity
  stays constant and terminal receipts carry completion times.
- `PENDING` retains the unapplied hierarchy concern. It is session history, not
  a recomputed diff: a later ordinary save can be `applied` while earlier restart
  notes remain. This test does not claim automatic resolution of those notes.

Run `./gradlew :agent:e2eTest --rerun --tests '*SuperclassReportingVerificationTest'`
for the scenario (the task also runs its broker-suite dependencies). This extends
local product regression coverage; it does not expand the standalone probe's
runtime matrix. No new receipt status, field, command or custom loader is added.

## Decision and remaining limits

The bounded same-loader route is feasible **when dispatch is prewired**. It is
not sufficient to implement transparent superclass replacement. The experiment
has one original/delegate pair, public state explicitly passed as an argument,
and trivial constructors. It does not establish automatic transformation,
inherited/super-call rewriting, original-`this` callbacks, multiple-instance
ownership, new-base field migration, constructor side effects, interface/type
emulation or framework compatibility.

The cost decision remains: do not build the excluded general dispatch and
state/constructor engines from this result. Retain the lower-cost tests and
truthful reporting for current refusal/method-salvage behavior. Any production
expansion needs a separately approved scope and evidence. A custom loader would
also be a separate design investigation; none was implemented or tested here.

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
Their representative boundary and reporting tests are separate queued work.

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

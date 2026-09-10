# Integration tests

The end-to-end suite. It edits real source files in a running SAP
Commerce server, then asks the server over HTTP whether the change took
effect. A reported pass requires the expected application behavior; a compile
or reload event alone is not proof. Portable checks and live SAP checks are
reported separately.

**You need a licensed SAP Commerce installation for the live suite.**
The portable HTTP proof checks run as part of this module's `build` without
SAP. The SDK contract script additionally needs an installed licensed SDK;
it does not start the server. Report these checks separately from a live run.

## What it covers

| Area | Tests |
| --- | --- |
| Class redefinition | method body, add and remove method, add and remove field, change signature, constructor, annotation |
| Spring | bean refresh, MVC re-scan, cache eviction, scheduler, event listener |
| SAP Commerce | interceptor model save with rollback, ImpEx auto-import |
| Optional Hibernate ORM | separate real L2 provider regression; the SAP fixture has no ORM provider and reports SKIP |
| Behaviour under load | multi-class reload, large class, rapid successive changes, syntax error recovery |

## Running it

The suite drives the `reclazztest` extension, which has to be deployed
into your Hybris installation first. See `../reclazztest/README.md`.

### The agent needs two flags

The suite writes `.java` files and waits for the agent to compile them, and one
scenario saves an `.impex` and waits for the import. Neither is on by default,
so the agent has to be started with both:

```
-javaagent:/path/to/reclazz-agent.jar=hybrisHome=...,autoCompile=true,autoImpex=true
```

Without `autoCompile` the very first scenario fails with "Timeout waiting for
COMPILE event", which reads like a broken agent and is a missing flag. Without
`autoImpex` the ImpEx scenario is the only one that fails, which reads like a
defect and is the same thing.

With the server running and Reclazz attached:

```bash
export RECLAZZ_TEST_EXT_PATH=/path/to/hybris/bin/custom/reclazztest
export RECLAZZ_TEST_PORT_FILE=/path/to/project/.idea/reclazz/agent.port

./gradlew :integration-test:run
```

| Variable | Default | |
| --- | --- | --- |
| `RECLAZZ_TEST_EXT_PATH` | required | the deployed `reclazztest` extension |
| `RECLAZZ_TEST_PORT_FILE` | required | where the agent wrote its port |
| `RECLAZZ_TEST_BASE_URL` | `https://localhost:9002` | |
| `RECLAZZ_TEST_MODE` | `companion` | `enhanced` on JetBrains Runtime or DCEVM |
| `RECLAZZ_TEST_EVENT_TIMEOUT` | `30000` | ms to wait for a reload event |
| `RECLAZZ_TEST_SETTLE_DELAY` | `2000` | ms to let a reload settle before asserting |

## Why `RECLAZZ_TEST_MODE` matters

Structural reload behaves differently by JVM, and the tests assert the
documented semantics of each rather than pretending they are the same.
On a standard JVM (`companion`) new members live on a hidden nestmate:
code that calls them directly works, reflection on the original class
does not. On JetBrains Runtime or DCEVM (`enhanced`) reflection sees
them too. A test that passes in one mode is not evidence about the
other, so run both if you are changing the reload engine.

## Reading a failure

The runner connects to the agent's event stream and waits for the reload
it expects, so a failure tells you whether the reload never happened,
happened but was not applied, or was applied and the assertion about the
result still failed. Those are three different bugs and the report
distinguishes them. The recurring one in practice has been the second:
Reclazz reporting success while doing nothing.


## SAP reliability checks

`InterceptorReloadTest` uses POST `/test/interceptor-save` with a fresh nonce.
The test extension creates a catalog/version/product in a transaction, invokes
`modelService.save`, observes that request's validator version and call count,
and rolls back. Redeploy the updated `ValidationProbe` and
`SapVerificationController` before running it. HTTP failures, old versions,
old nonces, zero calls and duplicate calls fail. Run only in a test environment:
rollback does not undo external side effects from application interceptors.

The previous Hibernate test only returned `dao-v2`; it never populated or read
an L2 cache. It now reports SKIP in this Commerce fixture. SAP Commerce's native
persistence cache and Hibernate Validator are not Hibernate ORM L2 caches.

Without starting SAP or touching its database, use the installed SDK for the
registry/mapping contract and compilation of the model-save fixture:

```bash
./gradlew :agent:compileJava :integration-test:build
python3 scripts/test-sap-sdk.py /path/to/hybris
python3 scripts/test-sap-sdk.py /path/to/hybris --disable-refresh
```

The last command is a negative control and **must fail** with the new target
missing from the registry. The SDK check uses real SAP/Spring classes while
isolating database type resolution and session policy. It does not claim to
execute `modelService.save`. `:integration-test:sapProofTest` separately checks
eight acceptance cases against a real local HTTP server, without a SAP server.

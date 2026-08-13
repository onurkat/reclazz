# Integration tests

The end-to-end suite. It edits real source files in a running SAP
Commerce server, then asks the server over HTTP whether the change took
effect. Everything Reclazz claims to do is claimed on the strength of
these 20 tests passing against a live 2211 install.

**You need a licensed SAP Commerce installation to run this.** Nothing
here runs in CI and nothing here runs from a plain clone. If you do not
have one, that is fine: `./gradlew build` covers everything else, and
this module still compiles as part of it, so you cannot break it without
noticing.

## What it covers

| Area | Tests |
| --- | --- |
| Class redefinition | method body, add and remove method, add and remove field, change signature, constructor, annotation |
| Spring | bean refresh, MVC re-scan, cache eviction, scheduler, event listener |
| SAP Commerce | interceptor reload, ImpEx auto-import, Hibernate L2 cache |
| Behaviour under load | multi-class reload, large class, rapid successive changes, syntax error recovery |

## Running it

The suite drives the `reclazztest` extension, which has to be deployed
into your Hybris installation first. See `../reclazztest/README.md`.

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

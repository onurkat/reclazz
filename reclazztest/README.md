# reclazztest

A SAP Commerce extension that exists to be hot-reloaded at.

It is the target the end-to-end suite in `../integration-test` edits:
services whose method bodies get rewritten, a controller that reports
what the running JVM currently believes, an interceptor, a scheduled
job, a cache. The suite changes these files on disk and then asks the
server over HTTP what it sees.

**This is test scaffolding, not part of the product.** It ships in no
release and nothing in `agent/` or `src/` refers to it. If you are
reading the repository to understand how Reclazz works, this directory
is not it.

## What is in here

| Path | Its job in the suite |
| --- | --- |
| `src/.../services/TestService.java` | the main subject: bodies, methods and fields are added and removed here |
| `src/.../services/HelperService.java` | a second class, so multi-class reloads have something to be multi about |
| `src/.../services/CacheTestService.java` | `@Cacheable`, for eviction |
| `src/.../services/EventTestService.java` | `@EventListener`, for re-registration |
| `src/.../services/SchedulerTestService.java` | `@Scheduled`, for re-registration |
| `src/.../interceptors/TestValidateInterceptor.java` | Hybris interceptor reload |
| `web/src/.../controllers/TestController.java` | the HTTP surface the assertions read, and the subject for MVC re-scan |
| `resources/impex/test-data.impex` | ImpEx auto-import |

## Deploying it

Copy or symlink the directory into your installation and register it:

```bash
ln -s /path/to/reclazz/reclazztest /path/to/hybris/bin/custom/reclazztest
```

Add it to `hybris/config/localextensions.xml`:

```xml
<extension name="reclazztest"/>
```

Then `ant clean all` and start the server. The extension serves under
`/reclazztest/v2/test`.

A symlink is worth preferring over a copy: the suite edits these files,
and you want those edits in the repository where you can see and revert
them, not stranded inside a Hybris install.

## Generated files

Building the extension inside Hybris writes `build.xml`, `classes/`,
`gensrc/` and friends into this directory. They are listed in the
repository's `.gitignore`, so a deployed extension does not show up as
uncommitted changes.

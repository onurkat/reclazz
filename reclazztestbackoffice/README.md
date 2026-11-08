# reclazztestbackoffice

A SAP Commerce Backoffice module that exists to be hot-reloaded at.

It is the target for the rendered Backoffice acceptance test: a perspective
with one widget, whose controller lives in the standard Backoffice module
layout (`backoffice/src`, packed by the platform build into
`resources/backoffice/reclazztestbackoffice_bof.jar` and loaded by the cockpit's
`WidgetClassLoader`). Edits to it are made by hand in a browser session, not by
an automated suite.

**This is test scaffolding, not part of the product.** It ships in no release
and nothing in `agent/` or `src/` refers to it.

## What is in here

| Path | Its job in the test |
| --- | --- |
| `backoffice/src/.../widgets/ReclazzProbeController.java` | the main subject: "Send probe" sends `probeOutput`, which is wired back to the widget's own `probeInput`; add, edit and remove a `@SocketEvent(socketId = "probeInput")` handler here |
| `backoffice/src/.../services/ReclazzProbeService.java` | a bean in the Backoffice Spring context |
| `backoffice/resources/widgets/ReclazzProbeWidget/` | the widget definition, view and labels |
| `resources/reclazztestbackoffice-backoffice-widgets.xml` | puts the widget in a "Reclazz probe" perspective and connects its output to its input |
| `resources/reclazztestbackoffice-backoffice-spring.xml` | the Backoffice Spring context of the module |

The status label tells what the running server did with the last click:
`sent N, no handler` means no handler received the event.

## Deploying it

Symlink the directory into your installation, so the edits you make are the
ones in this repository:

```bash
ln -s /path/to/reclazz/reclazztestbackoffice /path/to/hybris/bin/custom/reclazztestbackoffice
```

Add it to `hybris/config/localextensions.xml`:

```xml
<extension name="reclazztestbackoffice"/>
```

Then `ant build` and restart the server with the Reclazz agent and
`autoCompile=true`.

The perspective only appears once Backoffice has merged the new widget
configuration. On a server whose `cockpitng-widgets-config` media already
exists, that needs one widget configuration reset at login: set
`backoffice.cockpitng.reset.triggers=login` and
`backoffice.cockpitng.reset.scope=widgets` (HAC, Platform, Configuration is
enough), log in once, then remove both. Back up the media first if it holds
configuration you care about.

## The acceptance run

Log in to Backoffice and open the "Reclazz probe" perspective.

1. Click "Send probe": `sent 1, no handler`.
2. Add a handler to `ReclazzProbeController` and save:

   ```java
   @SocketEvent(socketId = "probeInput")
   public void onProbe(final String value)
   {
       status.setValue("handled v1: " + value);
   }
   ```

   (`import com.hybris.cockpitng.annotations.SocketEvent;`). The agent compiles
   it and reports a structural reload with one added method. A view that was
   already open keeps its old binding; open the perspective in a new tab and
   click: `handled v1: ping-1`.
3. Change `v1` to `v2` and save; the same new view answers `handled v2: ping-2`.
4. Remove the handler and save; a held listener gets a `NoSuchMethodError`
   naming the removed method, and a newly composed view answers
   `sent 1, no handler`.

Revert the controller with
`git checkout reclazztestbackoffice/backoffice/src` when done.

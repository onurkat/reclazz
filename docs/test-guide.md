# Reclazz — Test Guide for SAP Commerce (Hybris)

## Prerequisites

- SAP Commerce (Hybris) 2211 or later
- IntelliJ IDEA 2023.3+ (Ultimate or Community)
- JDK 17 or 21 — any vendor. Structural hot-reload works on standard JVMs
  (SapMachine, OpenJDK, Temurin, Corretto…) via Reclazz's companion-class
  reloader, and on JBR/DCEVM via enhanced redefinition.
- Project configured and `ant all` completed successfully

## 1. Plugin Installation

1. Build the plugin: `./gradlew clean buildPlugin`
2. In IntelliJ: **Settings → Plugins → ⚙️ → Install Plugin from Disk**
3. Select `build/distributions/reclazz-*.zip`
4. Restart IDE

### Verify
- **Settings → Tools → Reclazz** section exists
- Status bar shows `Reclazz: Idle`

## 2. Hybris Project Detection

1. Open your SAP Commerce project in IntelliJ
2. Wait for project indexing to complete

### Verify
- Notification balloon: "SAP Commerce project detected"
- **View → Tool Windows → Reclazz** is available
- Check IDE log (`Help → Show Log in Explorer`) for:
  ```
  Reclazz: Detected Hybris home at /path/to/hybris
  Reclazz: JDK detection — version=17, isJBR=true/false
  ```

## 3. JDK Detection

### Test with Standard OpenJDK (including SapMachine)
1. Set project SDK to OpenJDK / SapMachine / Temurin / Corretto 17 or 21
2. Check Reclazz tool window log

**Expected**: `Detected JDK 21 (SapMachine) — structural hot-reload enabled (companion-class mode)`

The notification also mentions the one caveat of companion mode:
reflective caches (e.g. Hybris `ModelService`) won't see newly added
members until server restart, because `Class.getMethod(...)` on the
original class can't reach the hidden companion nestmate.

### Test with JetBrains Runtime
1. Set project SDK to JBR 17 or 21
2. Check Reclazz tool window log

**Expected**: `Detected JDK 21 (JetBrains Runtime) — structural hot-reload enabled (enhanced redefinition)`

Enhanced redefinition does not have the reflective-visibility caveat
— new members are applied to the original `Class` object directly.

## 4. Agent Injection

1. Enable Reclazz: **Settings → Tools → Reclazz → Enable**
2. Create or edit a Run Configuration for Hybris server
3. Run the server

### Verify
- In the console output, look for:
  ```
  [Reclazz] Agent initialized
  [Reclazz] Hybris home: /path/to/hybris
  [Reclazz] Watching N directories for changes
  [Reclazz] Status server listening on port XXXXX
  ```
- Check `.idea/reclazz/agent.port` file exists
- Run Configuration VM options should contain `-javaagent:.../reclazz-agent.jar=...`

### Verify JVM flags
- OpenJDK: `--add-opens=java.base/java.lang=ALL-UNNAMED` present
- JBR: above + `-XX:+AllowEnhancedClassRedefinition` present

## 5. Plugin-Agent Connection

1. After server is fully started (Spring context loaded)
2. Check status bar widget

### Verify
- Status bar shows `Reclazz: Connected` (may take a few seconds after server starts)
- Tool window shows "Connected to Reclazz agent"

## 6. Hot-Reload: Method Body Change

This tests the most common scenario — changing method logic.

1. Find a simple service class in your custom extension, e.g.:
   ```java
   public class MyService {
       public String getMessage() {
           return "Hello";
       }
   }
   ```
2. Change the return value:
   ```java
   return "Hello World";
   ```
3. Compile the project: **Build → Build Project** (Ctrl+F9)
4. Wait for the `.class` file to be picked up by the file watcher

### Verify
- Tool window shows: `Hot-swapped: com.example.MyService (XXms)`
- Status bar reload count increments: `Reclazz: 1 reloads`
- Calling the service in HAC or via API returns the new value

## 7. Hot-Reload: Spring Bean Refresh

1. Modify a Spring-managed `@Service` or bean defined in XML
2. Change a method body and compile

### Verify
- Tool window shows:
  ```
  Hot-swapped: com.example.MyService (XXms)
  Spring bean refreshed: myService
  ```
- New singleton instance is created with updated logic
- Note: existing injected references still point to old instance

## 8. Hot-Reload: Structural Change (any JDK 17+)

Works on any JDK 17+ — standard or enhanced-redefinition.

1. Add a new method or field to an existing class:
   ```java
   private String newField;
   public String getNewMethod() {
       return "new";
   }
   ```
2. Compile

### Verify (any JDK 17+)
- Tool window shows: `Structural reload: com.example.MyClass (XXms)`
- New method/field is callable/readable from other hot-compiled code that
  calls it directly (via `invokedynamic` routed through the companion
  nestmate)

### Reflective-visibility caveat (standard JVMs only)
On standard JVMs (companion-class mode), the new members live on a
hidden nestmate rather than on the original `Class` object. This means:
- `Class.getMethod("getNewMethod")` on the original class still throws
  `NoSuchMethodException` until server restart
- Reflective caches (Hybris `ModelService`, Jackson, Gson, etc.) built
  from the original class at boot time also won't see the new members
- Jalo-layer property access and flexible search (which go through the
  type dictionary rather than Java reflection) do see the new members
  after a HAC `updatesystem` for Hybris items.xml attributes

On JBR/DCEVM with `-XX:+AllowEnhancedClassRedefinition`, the original
`Class` object itself gains the new members, so reflection and
reflective caches work without restart.

## 9. Interceptor Reload

1. Modify an interceptor class (implements `Interceptor<T>`)
2. Compile

### Verify
- Tool window shows hot-swap success
- If interceptor is managed by Spring, also shows bean refresh

## 10. Extension Watching Scope

### Verify only custom extensions are watched
1. Check tool window startup log for watched directory count
2. Should only include your custom extensions, NOT platform extensions like:
   - `core`, `catalog`, `europe1`, `platformservices`, etc.

### Verify with settings
1. In **Settings → Tools → Reclazz → Watch Extensions**, enter specific extension names
2. Restart server
3. Only the specified extensions should be watched

## 11. Reconnection

1. With server running and plugin connected, stop the server
2. Status bar should show `Reclazz: Disconnected`
3. Start the server again
4. Wait for agent to initialize

### Verify
- Plugin reconnects automatically (with backoff: 1s, 2s, 4s, up to 10s)
- Status bar returns to `Reclazz: Connected`
- Tool window logs the reconnection

## 12. Error Scenarios

### Server not started
- Status bar: `Reclazz: Idle` or `Reclazz: Disconnected`
- No errors in IDE log

### Agent JAR missing
- Check IDE log for warning about agent JAR not found
- Run configuration should NOT have `-javaagent` flag

### Wrong JDK version
- JDK < 17: Agent may not load — check for error in console

### Non-Hybris project
- Plugin should be invisible — no tool window, no status widget activity
- Settings section still available but disabled state

## 13. Settings Persistence

1. Change settings in **Settings → Tools → Reclazz**
2. Close and reopen the project
3. Verify settings are preserved

### Settings to test
| Setting | Default | Test Value |
|---------|---------|------------|
| Enable | false | true |
| Auto Compile | false | true |
| Auto ImpEx | false | true |
| Watch Extensions | (empty) | "myext1,myext2" |
| Debounce (ms) | 500 | 1000 |
| Verbose Logging | false | true |
| Auto-detect JDK | true | false |

## 14. Performance

### File watcher overhead
- Monitor CPU usage after server starts
- File watcher should be idle when no changes occur
- With 30-second startup delay, server boot should not be impacted

### Status socket
- Agent StatusServer binds to loopback (127.0.0.1) only
- Max 5 concurrent client connections
- JSON line events are lightweight (< 200 bytes each)

## Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| Server hangs on startup | Too many watched dirs | Check custom extension filter |
| No console logs after agent | SLF4J conflict | Ensure agent JAR has no `slf4j-simple` |
| `Reclazz: Disconnected` persists | Port mismatch | Delete `.idea/reclazz/agent.port`, restart |
| Hot-swap fails | Schema change on OpenJDK | Use JetBrains Runtime |
| Spring bean not refreshed | Non-singleton bean | Expected — prototypes recreate automatically |
| Agent not injected | Plugin disabled or non-Hybris project | Check Settings → Tools → Reclazz |

# Reclazz with Maven

Two ways to attach the agent in a Maven build: a small plugin that does it the
way `jacoco:prepare-agent` does, or, with no plugin at all, a few lines of POM.
Both put `-javaagent:<reclazz-agent>=platform=spring,watchDirs=<target/classes>`
in front of the JVM that runs your app or your tests. The agent is on Maven
Central as `com.onurkat.reclazz:reclazz-agent`.

## Without the plugin (works today)

Let the dependency plugin resolve the agent jar into a property, then reference
that property from Surefire and from `spring-boot:run`.

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-dependency-plugin</artifactId>
      <version>3.8.1</version>
      <executions>
        <execution>
          <goals><goal>properties</goal></goals>
        </execution>
      </executions>
    </plugin>

    <!-- Tests run against reloaded classes. -->
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-surefire-plugin</artifactId>
      <configuration>
        <argLine>-javaagent:${com.onurkat.reclazz:reclazz-agent:jar}=platform=spring,watchDirs=${project.build.outputDirectory}</argLine>
      </configuration>
    </plugin>

    <!-- The app reloads in place while it runs. -->
    <plugin>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-maven-plugin</artifactId>
      <configuration>
        <jvmArguments>-javaagent:${com.onurkat.reclazz:reclazz-agent:jar}=platform=spring,watchDirs=${project.build.outputDirectory}</jvmArguments>
      </configuration>
    </plugin>
  </plugins>
</build>

<dependencies>
  <dependency>
    <groupId>com.onurkat.reclazz</groupId>
    <artifactId>reclazz-agent</artifactId>
    <version>1.3.0</version>
    <scope>provided</scope>
  </dependency>
</dependencies>
```

The `provided` scope is enough for the dependency plugin to resolve the jar and
expose `${com.onurkat.reclazz:reclazz-agent:jar}`; the agent is never bundled
into your application.

## With the Reclazz Maven plugin

The plugin removes the hardcoded property name: its `prepare-agent` goal finds
the agent on its own classpath and sets `argLine` (which Surefire reads) to the
`-javaagent` flag.

```xml
<plugin>
  <groupId>com.onurkat.reclazz</groupId>
  <artifactId>reclazz-maven-plugin</artifactId>
  <version>1.3.0</version>
  <executions>
    <execution>
      <goals><goal>prepare-agent</goal></goals>
    </execution>
  </executions>
  <configuration>
    <platform>spring</platform>
    <!-- watchDirs defaults to ${project.build.outputDirectory} -->
  </configuration>
</plugin>
```

`prepare-agent` binds to the `initialize` phase, so `mvn test` picks up the
agent through `argLine`. For `spring-boot:run`, reference the same property in
its `jvmArguments`, or set `reclazz.propertyName` to a name you pass there.
Configure the platform and any agent argument through the plugin's
`<configuration>` (`platform`, `watchDirs`, `agentArgs`).

The plugin is on Maven Central as `com.onurkat.reclazz:reclazz-maven-plugin`, so
the coordinate above resolves with no extra setup. Its sources are in
`maven-plugin/`; this repository builds with Gradle, so the Maven plugin is built
with Maven separately (`cd maven-plugin && mvn install`) and published on its
own. The no-plugin wiring above needs none of that.

## Opt-in whole-reactor safety

A locally built plugin containing the `safe-build` goal can wrap a **complete
child Maven invocation** in an acknowledged named BUILD hold. This is a separate
entrypoint, not a lifecycle execution and not a change to `prepare-agent` or plain
`mvn compile`. Configure it on the reactor root (no `<executions>`):

```xml
<plugin>
  <groupId>com.onurkat.reclazz</groupId>
  <artifactId>reclazz-maven-plugin</artifactId>
  <version>1.3.0</version>
  <inherited>false</inherited>
  <configuration>
    <mcpJar>${project.basedir}/tools/reclazz-mcp-1.3.0.jar</mcpJar>
    <mavenExecutable>/absolute/path/to/mvn</mavenExecutable>
    <buildArguments>
      <argument>--offline</argument>
      <argument>verify</argument>
    </buildArguments>
  </configuration>
</plugin>
```

With that local plugin installed, invoke the goal alone from the reactor root:

```text
mvn com.onurkat.reclazz:reclazz-maven-plugin:1.3.0:safe-build -Dreclazz.port=54123 -Dreclazz.owner=builder-alice
```

Do not bind it to a phase, combine it with other outer goals/phases, invoke it
from a child module, or put `safe-build` in `buildArguments`. These uses are
rejected; earlier outer phases, if incorrectly combined, may already have run
before Maven reaches the rejection. The supported invocation above has no outer
compilation. This goal has no default lifecycle phase and runs as an aggregator.
A released plugin lacking this goal must first be rebuilt locally; this feature
has not been published by the acceptance workflow.

`mcpJar`, `port`, `owner` and a nonempty `buildArguments` list are required.
`reclazz.timeoutMs` defaults to 5000 and limits acknowledgements, not compilation.
`mavenExecutable` defaults to `mvn`; set it explicitly for a wrapper or another
installation. Arguments are separate argv entries, preserving spaces without a
shell. Include child settings, profiles, `-f`, properties and repository options
explicitly: outer CLI configuration is not automatically forwarded. The MCP jar
is a local standalone artifact, not a new Maven plugin runtime dependency.

The wrapper waits for an owned `started` acknowledgement before launching Maven,
and for `ok` only after the **entire selected reactor** exits zero. Any compiler
failure, including a later module failing after an earlier module wrote classes,
keeps the hold. Ownership rejection, offline agent or missing acknowledgement
fails the goal. Retain the owner printed by the wrapper and rerun the complete
repaired build with that same owner; never share it across concurrent builders.
Use `VERIFY`/`reclazz_verify_batch` afterwards: build success is not reload proof.

Only synchronous child build outputs on the selected agent are covered. Outer
configuration/extensions, external writers, unselected reactor modules and
asynchronous child work are not covered. There is no rollback or atomicity claim.
The API target is Maven 3.9.9; the real reactor acceptance is run with the installed
Maven version and recorded in the handoff. Windows acceptance is separate.

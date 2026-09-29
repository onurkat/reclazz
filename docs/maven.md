# Reclazz with Maven

For a Spring Boot development run, start with the opt-in profile below. It uses
`prepare-agent` to resolve the agent on the plugin's own classpath; the agent is
not an application dependency. The longer dependency-plugin alternative remains
below. Return to [installation](installation.md) for other launch methods.

Examples use Reclazz source version **1.3.0**. Use matching artifacts available
in your chosen repository or [build them locally](#local-builds); the source
version alone does not certify Maven Central availability. This recipe targets
Maven 3.9.x / Java 17+ and the runnable fixture pins Boot 3.3.5. Keep your existing
application's Boot version and validate it separately.

## Minimal development profile

For an existing Spring Boot Maven project, merge these properties and profile
into your POM; keep its existing Boot parent/plugin and dependencies. Put your
other JVM arguments in `app.jvmArgs` rather than discarding them. The example
marker is optional for your app, but proves preservation in the runnable demo.
Do not predefine `reclazz.agentArgs`: `prepare-agent` supplies it before Boot's
run goal is configured.

```xml
<properties>
  <reclazz.version>1.3.0</reclazz.version>
  <app.jvmArgs>-Ddemo.marker=kept</app.jvmArgs>
</properties>
```

```xml
<profiles>
    <profile>
      <id>reclazz-dev</id>
      <build>
        <plugins>
          <plugin>
            <groupId>com.onurkat.reclazz</groupId>
            <artifactId>reclazz-maven-plugin</artifactId>
            <version>${reclazz.version}</version>
            <configuration>
              <propertyName>reclazz.agentArgs</propertyName>
              <platform>spring</platform>
            </configuration>
          </plugin>
          <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <configuration>
              <jvmArguments>${reclazz.agentArgs} ${app.jvmArgs}</jvmArguments>
            </configuration>
          </plugin>
        </plugins>
      </build>
    </profile>
  </profiles>
```

Start from the application directory:

```sh
mvn -Preclazz-dev reclazz:prepare-agent spring-boot:run
```

The explicit `reclazz:prepare-agent` goal supplies `reclazz.agentArgs` before
Boot's run goal; `watchDirs` defaults to `${project.build.outputDirectory}`.
Do not omit that first goal: setting the property only inside Boot's forked
`initialize` lifecycle did not propagate it to `run` in the tested Maven/Boot
combination. The profile has no lifecycle execution, so the agent is prepared
once. Boot consumes that property plus `app.jvmArgs` in its application JVM.
The profile is inactive without `-Preclazz-dev`, and it does not add Reclazz to
the packaged application. Existing Surefire/Jacoco `argLine` wiring is untouched;
this profile is deliberately for the development app, not test-JVM attachment.
Do not also add a manual Reclazz `-javaagent` to `app.jvmArgs` or a second
`prepare-agent` execution.

In another terminal, edit a method body and run `mvn compile`; then check the
running behavior without restarting. Wait for the watcher's default 30-second
startup delay before the first edit. The [complete runnable example](../examples/maven-spring-boot/README.md)
shows the response and JVM-identity check. Plain compilation is not the optional
[whole-build safety wrapper](#opt-in-whole-reactor-safety).

## Local builds

For unreleased source changes, from the Reclazz repository root:

```sh
./gradlew :agent:publishToMavenLocal
mvn -f maven-plugin/pom.xml install
```

On Windows use `gradlew.bat`. Both commands install locally; neither publishes
remotely. Then run the example with `reclazz.version` matching `pluginVersion`
in `gradle.properties`. The agent must be installed before the Maven plugin can
resolve its runtime dependency. If using an alternate Maven local repository,
both artifact installations and the consumer must use that same repository.

## Without the plugin (works today)

Let the dependency plugin resolve the agent jar into a property, then reference
that property from Surefire and from `spring-boot:run`. Keep the `app.jvmArgs`
property from the minimal example (or set it empty). Merge with your existing
Surefire arguments rather than replacing another agent's configuration. Unlike
the dev profile, this older alternative attaches to tests as well.

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
        <argLine>"-javaagent:${com.onurkat.reclazz:reclazz-agent:jar}=platform=spring,watchDirs=${project.build.outputDirectory}" ${app.jvmArgs}</argLine>
      </configuration>
    </plugin>

    <!-- The app reloads in place while it runs. -->
    <plugin>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-maven-plugin</artifactId>
      <configuration>
        <jvmArguments>"-javaagent:${com.onurkat.reclazz:reclazz-agent:jar}=platform=spring,watchDirs=${project.build.outputDirectory}" ${app.jvmArgs}</jvmArguments>
        <excludes>
          <exclude>
            <groupId>com.onurkat.reclazz</groupId>
            <artifactId>reclazz-agent</artifactId>
          </exclude>
        </excludes>
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
expose `${com.onurkat.reclazz:reclazz-agent:jar}`. The explicit Boot exclusion
keeps the agent out of a Boot-repackaged application; `provided` alone is not
that guarantee ([Boot packaging rules](https://docs.spring.io/spring-boot/3.3/maven-plugin/packaging.html)).
This alternative resolves the agent as a project dependency,
whereas the recommended dev profile keeps it on the Reclazz plugin classpath.

Resolve the property in the outer lifecycle before Boot forks its lifecycle:

```sh
mvn initialize spring-boot:run
```

Using only `mvn spring-boot:run` is not the tested setup. Recompile with
`mvn compile` in another terminal and check the response as above.

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
agent through `argLine`. For `spring-boot:run`, use the explicit-goal development
profile above instead of relying on initialization inside Boot's forked lifecycle.
Configure the platform and any agent argument through the plugin's
`<configuration>` (`platform`, `watchDirs`, `agentArgs`).

The plugin coordinate is `com.onurkat.reclazz:reclazz-maven-plugin`; select a
published version or use the local-build instructions above. Its sources are in
`maven-plugin/`; this repository builds with Gradle, so the Maven plugin is built
with Maven separately (`cd maven-plugin && mvn install`) and published on its
own. The no-plugin wiring above needs the agent artifact but not the Reclazz Maven plugin.

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

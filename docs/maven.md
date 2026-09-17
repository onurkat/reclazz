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

The plugin's sources are in `maven-plugin/`. This repository builds with Gradle,
so the Maven plugin is built with Maven separately (`cd maven-plugin && mvn
install`) and published on its own; the no-plugin wiring above needs none of
that.

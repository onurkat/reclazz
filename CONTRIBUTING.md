# Contributing to Reclazz

Thank you for your interest in contributing! This guide will help you get started.

## Development Setup

1. **Clone the repository**
   ```bash
   git clone https://github.com/onurkat/reclazz.git
   cd reclazz
   ```

2. **Requirements**
   - JDK 17 or later
   - IntelliJ IDEA (for plugin development)
   - Gradle (wrapper included)

3. **Build**
   ```bash
   ./gradlew clean build
   ```

4. **Run the plugin in a sandbox IDE**
   ```bash
   ./gradlew runIde
   ```

## Project Structure

The product:

- `agent/` - Java agent JAR (loaded into the target JVM)
- `src/main/kotlin/` - IntelliJ plugin code
- `src/main/resources/` - Plugin resources (icons, plugin.xml)

Test scaffolding, which ships in no release:

- `integration-test/` - the end-to-end suite, and the only thing that
  proves the product works. It needs a licensed SAP Commerce
  installation, so it does not run in CI or from a plain clone.
  See [integration-test/README.md](integration-test/README.md).
- `reclazztest/` - the SAP Commerce extension the suite edits.
  See [reclazztest/README.md](reclazztest/README.md).

## Testing

`./gradlew clean build` is the whole gate for a plain clone, and it is
what CI runs on Linux and Windows. It compiles every module, including
`integration-test`, so you cannot break that one without noticing even
though you may not be able to run it.

Three layers sit under it, and they answer different questions:

- **Unit tests** in `agent/` and `src/test/`, the bulk of the suite.
- **`StartupMustNotShowDialogTest`** walks our compiled bytecode out of
  the startup activity and fails if a modal dialog is reachable. A
  dialog shown while the IDE starts freezes it until something clicks
  it, and no amount of source review reliably catches that.
- **`ReclazzStartupFixtureTest`** runs startup against a real IDE with
  the plugin loaded, which is the only way to find out that the
  extension points `plugin.xml` declares are the ones the code asks
  for. A one-character typo in a notification group id throws on every
  user's first launch and is invisible to everything else.

`./gradlew verifyPlugin` runs the JetBrains Plugin Verifier against the
IDEs in the declared compatibility range. Marketplace runs it on
submission, so failing locally is cheaper than a rejection email. Be
aware it is narrower than review: it reports compatibility, not
behaviour, and it does not reproduce every internal-API call the
reviewers flag.

The end-to-end suite is the layer that has actually caught product
bugs. If you are changing the reload engine and you have an
installation, run it.

## Making Changes

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Make your changes
4. Ensure the build passes: `./gradlew clean build`, and
   `./gradlew verifyPlugin` if you touched the plugin
5. Commit with a clear message
6. Push to your fork and open a Pull Request

## Code Style

- **Kotlin** (plugin): Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- **Java** (agent): Standard Java conventions, 4-space indentation
- Keep dependencies minimal in the agent module (it runs inside customer JVMs)

## Reporting Issues

- Use [GitHub Issues](https://github.com/onurkat/reclazz/issues)
- Include your IntelliJ version, JDK version, and platform (Spring Boot / SAP Commerce)
- Include relevant logs from the Reclazz tool window

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).

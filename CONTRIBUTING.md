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

- `agent/` - Java agent JAR (loaded into the target JVM)
- `src/main/kotlin/` - IntelliJ plugin code
- `src/main/resources/` - Plugin resources (icons, plugin.xml)

## Making Changes

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Make your changes
4. Ensure the build passes: `./gradlew clean build`
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

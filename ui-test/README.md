# IntelliJ UI test

From the repository root, build the candidate ZIP and run the opt-in test. The first command skips desktop IDE startup for searchable options.

```sh
./gradlew :buildPlugin -x :buildSearchableOptions -x :prepareJarSearchableOptions -x :jarSearchableOptions
./gradlew -p ui-test uiTest
```

The runner needs Docker, Python 3.8+ and the project's Gradle JDK. Its first run downloads IDEA Community 2023.3 and Remote Robot 0.11.23, verifies the archive hashes and builds the Linux Xvfb image. The IDE then runs with networking limited to container loopback. Its first-run agreement setting applies only to the disposable test profile. The test neither opens the host IDE nor mounts its settings.

The fixture is a Spring Boot 3 Gradle project. Gradle resolves its runtime dependencies. A matching IntelliJ module uses those jars so Build Project exercises the native IDE compiler offline. The Java run configuration has Reclazz enabled in project settings. The fixture endpoint returns the app PID with the value under test.

Use `-PuiScenario=settings` or `-PuiScenario=reload` to run one flow. Each run keeps Reload Log samples and failure diagnostics under `ui-test/build/reports/uiTest/`. The test uses two distinct edits, one unchanged build, a failed build and repair, an IDE stop/start and a settings round trip.


The default `uiTest` runs the reload and Settings flow and the wizard journeys in separate disposable profiles. The layout checks measure the actual IDE display, font and theme at 1366×768 (1×, light) and 1440×900 logical (2×, dark, 16-point font). The SAP journey checks the running-server warning, Ant output and Retry. The installation journeys reuse the same project and IDE settings across restarts. The update journey downloads the pinned 1.10.1 release and verifies its SHA-256 before starting the previous version.

Use `-PuiScenario=wizard-sap` for the laptop SAP journey, `wizard-sap-retina` for the high-density SAP profile, `wizard-skipped` for the independent Tools reopen check, and `wizard-update` for the upgrade. Each scenario writes its own screenshots and assertion results under the run report.

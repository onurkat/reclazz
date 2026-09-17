# AlternativeTo listing draft (Reclazz)

Copy-ready text for submitting Reclazz at https://alternativeto.net. AlternativeTo
pages are a common source for "alternative to X" answers in assistants, so the
facts here must match the site and the repo exactly. Submission is an owner
action (needs an account); this file is the content to paste.

## Basics

- Name: Reclazz
- Homepage: https://reclazz.com
- Source code: https://github.com/onurkat/reclazz
- Download: https://plugins.jetbrains.com/plugin/33498-reclazz
- Licence: Open Source, Apache 2.0
- Pricing: Free
- Platforms: Windows, macOS, Linux (JVM); IntelliJ IDEA 2023.3 through 2026.2

## Short description (one line)

Free, open-source hot reload for Java, Spring Boot and SAP Commerce (Hybris):
redefine classes in place on a stock JDK, keep application state, no restart.

## Long description

Reclazz is a free, open-source (Apache 2.0) hot-reload tool for JVM
applications, with first-class support for Spring Boot and SAP Commerce
(Hybris). You edit Java code and build; the running JVM picks up the change
without a restart, and application state survives.

Unlike an approach that needs a patched JVM, Reclazz performs common
structural changes (adding and removing methods and instance fields, changing
annotations, adding many Spring beans, listeners and scheduled or
security-annotated methods after startup) on a stock JDK 17 or newer. On SAP
Commerce it goes beyond Java: saving an items.xml or beans.xml runs the
platform's own code generation and reloads the regenerated model and DTO
classes, interceptors re-register, and properties, log levels and ImpEx on
save are supported.

There is no telemetry, no analytics and no outbound network request; the one
socket the agent opens is bound to loopback. The source is public, so every
claim can be verified.

## Suggested tags / keywords

hot-reload, hot-swap, spring-boot, sap-commerce, hybris, jrebel-alternative,
hotswapagent, dcevm, java, intellij-plugin, developer-tools, jvm

## List Reclazz as an alternative to

- JRebel (commercial hot reload) - primary
- Spring Boot DevTools (restart-based reload)
- HotswapAgent / DCEVM (patched-JVM hot reload)
- Spring Loaded (unmaintained)

## Feature checklist (AlternativeTo-style)

- Free
- Open Source
- No telemetry / privacy-respecting
- Spring Boot support
- SAP Commerce / Hybris support
- Works on a stock JDK (no patched JVM required for structural changes)
- Preserves application state across reloads
- IntelliJ IDEA integration

## Notes for the submitter

- Keep competitor framing factual. JRebel, Spring Boot and HotswapAgent/DCEVM
  are named only to place Reclazz among alternatives, not to disparage them.
- Mirror the exact facts on https://reclazz.com/compare/ so an assistant that
  reads both sees a consistent story.
- Disclose ownership if replying in AlternativeTo comments.

# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.1.x   | :white_check_mark: |
| 1.0.x   | :x:                |

## Reporting a Vulnerability

If you discover a security vulnerability, please report it responsibly:

1. **Do not** open a public GitHub issue
2. Email: onur@onurkat.com
3. Include a description of the vulnerability and steps to reproduce

You can expect an initial response within 48 hours.

## Security Design

Reclazz is designed with security in mind:

- **100% local** - No outbound network requests, no telemetry, no analytics
- **Loopback-only status socket** - The agent listens on 127.0.0.1 for the IDE.
  It broadcasts its own log lines, answers three read-only questions
  (`DIAGNOSE`, `PENDING`, `HEALTH`) and takes one nudge (`SCAN`, sent when a
  build finishes, which makes the watcher look at its own directories now
  rather than on the next poll); nothing a client sends can make it load,
  reload or run anything it would not have on its own. A command is capped at 512 bytes and a line that
  never ends drops the connection rather than being held in memory
- **What the agent writes** - Class redefinition happens in memory. On disk it
  writes a port file beside the project (`.reclazz/agent.port`), and extracts
  its bootstrap jar into a fresh owner-only temporary directory with a random
  name, so nobody else on the machine can prepare or replace the file the
  bootstrap class loader reads from. Opt-in features write more: `autoCompile`
  writes class files, SAP Commerce codegen runs the platform's own `ant build`,
  and ImpEx auto-import runs the ImpEx files you save
- **XML parsing** - All XML parsing uses hardened `DocumentBuilderFactory` with XXE prevention
- **File validation** - Extension names are validated against safe patterns
- **File size limits** - ImpEx auto-import enforces file size limits to prevent resource exhaustion

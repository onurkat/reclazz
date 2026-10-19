# Security Policy

## Supported versions and scope

Use the newest published release applicable to your environment. The repository's
source version does not establish publication or an ongoing maintenance promise
for an older branch. No separate LTS or enterprise support window is declared.
See the [installation and version requirements](docs/installation.md#versions-and-requirements)
and [changelog](CHANGELOG.md) for version-specific scope. This is a local development
tool; do not attach it to production servers.

## Reporting a Vulnerability

If you discover a security vulnerability, please report it responsibly:

1. **Do not** open a public GitHub issue
2. Email: onur@onurkat.com
3. Include a description of the vulnerability and steps to reproduce

You can expect an initial response within 48 hours.

## Security Design

Reclazz is designed with security in mind:

- **100% local** - No outbound network requests, no telemetry, no analytics
- **Loopback-only status socket** - The agent listens on 127.0.0.1. Local clients
  can read diagnostics, request a scan, hold build output and pause/resume reload
  admission. These controls are process-wide; loopback is not per-client
  authentication. Use only trusted local clients. The canonical
  [protocol command table](docs/protocol.md#commands) describes the accepted syntax,
  bounds and requester-only responses; it is the single command reference.
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

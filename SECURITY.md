# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |

## Reporting a Vulnerability

If you discover a security vulnerability, please report it responsibly:

1. **Do not** open a public GitHub issue
2. Email: onur@onurkat.com
3. Include a description of the vulnerability and steps to reproduce

You can expect an initial response within 48 hours.

## Security Design

Reclazz is designed with security in mind:

- **100% local** - No outbound network requests, no telemetry, no analytics
- **Read-only agent** - The agent only reads class files and redefines them in the local JVM
- **XML parsing** - All XML parsing uses hardened `DocumentBuilderFactory` with XXE prevention
- **File validation** - Extension names are validated against safe patterns
- **File size limits** - ImpEx auto-import enforces file size limits to prevent resource exhaustion

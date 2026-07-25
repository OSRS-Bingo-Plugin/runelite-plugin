# Security Policy

## Reporting a vulnerability

Please do **not** open a public issue for a security vulnerability.

Report it privately via **GitHub Security Advisories**
("Report a vulnerability" under the repository's **Security** tab), or contact the
maintainers directly. Include reproduction steps and affected versions, and allow
time for a fix before any public disclosure.

## Scope

This is a RuneLite plugin. It:

- talks **only** to its fixed bingo backend (a single hardcoded URL) — no other
  network calls, analytics, or third-party services;
- makes no network request at all until the user enters a team **board code**;
- stores that board code locally via RuneLite config (masked) and sends it only
  to that backend;
- sends a screenshot only as drop proof, and only when the user enables it.

Reports of the plugin contacting anything other than its configured backend, or
mishandling the board code / screenshots, are in scope and appreciated.

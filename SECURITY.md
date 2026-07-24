# Security Policy

## Reporting a vulnerability

Please do **not** open a public issue for a security vulnerability.

Report it privately via **GitHub Security Advisories**
("Report a vulnerability" under the repository's **Security** tab), or contact the
maintainers directly. Include reproduction steps and affected versions, and allow
time for a fix before any public disclosure.

## Scope

This is a RuneLite plugin. It:

- talks **only** to the bingo backend URL configured in its settings — no other
  network calls, analytics, or third-party services;
- stores the team **board code** locally via RuneLite config (masked) and sends it
  only to that configured backend;
- sends a screenshot only as drop proof, and only when the user enables it.

Reports of the plugin contacting anything other than its configured backend, or
mishandling the board code / screenshots, are in scope and appreciated.

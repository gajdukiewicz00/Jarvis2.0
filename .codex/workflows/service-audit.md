# Service Audit Workflow

Jarvis project convention.

## Checklist

- [ ] read `docs/services/<service>.md`
- [ ] inspect `apps/<service>/pom.xml`
- [ ] inspect controllers, routes, and public API surface
- [ ] inspect service config in `application*.yml`
- [ ] inspect downstream clients and upstream callers
- [ ] inspect database ownership and Flyway migrations if present
- [ ] inspect tests and missing coverage
- [ ] run `mvn -pl apps/<service> -am test`
- [ ] compare docs versus implementation
- [ ] score production readiness as a percentage

## Evidence To Capture

- main entry points
- config defaults and risky toggles
- migration or schema concerns
- integration assumptions
- failing or missing tests
- docs drift

## Output

- service summary
- findings by severity
- readiness score
- prioritized next steps

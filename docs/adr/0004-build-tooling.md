# ADR-0004: Build tooling and repository layout

- **Status:** Accepted
- **Date:** 2026-10-27

## Context

[ADR-0002](0002-service-and-thin-clients.md) splits Crawdad into a Java service and thin clients
built with web technologies, with a shared protocol between them. The project's process requires
specifications first, changes only through pull requests, and unit test coverage of about 80%.
Before any code is written, the repository needs a layout, builds, tests and CI that enforce this
from the first commit.

## Decision

1. **Layout.** One repository:

   ```
   service/      Java service
   protocol/     shared protocol schema (JSON Schema)
   clients/      pnpm workspace
     shared/     code common to all clients: connection, message types, pairing
     desktop/    full desktop client
   specs/        specifications
   ```

   Further clients, such as a simplified phone client, become new packages under `clients/`.

2. **Java.** Java 25, the current long-term support release.

3. **Maven** builds the whole repository. A root `pom.xml` lists the modules, and the Maven Wrapper
   (`./mvnw`) pins the Maven version, 3.9.x, with a checksum of the distribution. One command builds
   and checks everything: `./mvnw verify`.

4. **Clients** are a pnpm workspace built with Vite and TypeScript, linted with ESLint and formatted
   with Prettier. Maven builds them through `frontend-maven-plugin`, which downloads pinned
   versions of Node.js and pnpm, so neither has to be installed on the machine. While working on a
   client, `pnpm` can be used directly inside `clients/`.

5. **Tests and coverage.** Java: JUnit, AssertJ and Mockito, with coverage measured by JaCoCo.
   TypeScript: Vitest with V8 coverage. Line coverage below 80% in any module fails the build.

6. **Protocol schema.** `protocol/` holds the JSON Schema of the messages. How Java and TypeScript
   code is derived from it is decided together with the first real messages.

7. **CI.** GitHub Actions run `./mvnw verify` on every pull request on the `ubuntu-latest` runner.
   Ubuntu 26.10 or newer is the platform Crawdad runs on; builds do not depend on that difference.

8. **`main`** accepts changes only through pull requests with a passing CI build; force pushes to
   `main` are blocked.

## Consequences

- Cloning the repository and running `./mvnw verify` with Java 25 installed is enough to build and
  test everything.
- Maven drives the TypeScript build, so client builds run through a plugin layer.
- The coverage threshold applies to every module from the first commit.

## Alternatives considered

- **Gradle.** Not chosen: its build files are programs rather than declarations, and its main
  advantage for this project, the Android toolchain, is not needed now that clients use web
  technologies.
- **Separate builds with a root script.** Not chosen: two entry points to keep in sync and Node.js
  to install by hand, against one pinned and declarative build.
- **Maven 4.** Not chosen: no stable release yet.

## Sources

Checked on 2026-10-27:

- [Apache Maven](https://maven.apache.org/) and
  [Maven Wrapper](https://maven.apache.org/tools/wrapper/).
- [frontend-maven-plugin](https://github.com/eirslett/frontend-maven-plugin).
- [JaCoCo Maven plugin: check goal](https://www.jacoco.org/jacoco/trunk/doc/check-mojo.html).
- [Vitest: coverage](https://vitest.dev/guide/coverage).
- [pnpm workspaces](https://pnpm.io/workspaces).

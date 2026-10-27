# ADR-0005: Licenses of dependencies and build tools

- **Status:** Accepted
- **Date:** 2026-10-27
- **Supersedes:** decision 3 of [ADR-0003](0003-api-key-adapters.md)

## Context

[ADR-0003](0003-api-key-adapters.md) required all libraries to use permissive licenses, with no
copyleft. Taken literally, that rule also covers tools that are only used to build and test the
project. The standard Java testing and coverage tools, JUnit and JaCoCo, are licensed under the
Eclipse Public License 2.0, a weak copyleft license, and an automated license check following the
rule as written would reject them.

What the rule has to protect is narrower: publishing and distributing Crawdad's source code and
builds must not infringe anyone's rights or impose anyone else's license terms on Crawdad.
Copyleft obligations arise when the licensed work is distributed. The EPL-2.0 defines "Distribute"
as distributing or making available a copy, and attaches its requirements to that act. Tools that
only run during the build, and are neither modified nor shipped, are not distributed.

## Decision

1. **What ships.** Every dependency that ends up in what Crawdad distributes, such as runtime
   libraries of the service and production dependencies of the clients, must use a permissive
   license: MIT, BSD-2-Clause, BSD-3-Clause, Apache-2.0, ISC or similar. No copyleft.

2. **Build and test tools.** Tools and dependencies used only to build and test the project, and
   not shipped, may use any open source license, including copyleft licenses such as EPL-2.0,
   LGPL or GPL, provided they are used unmodified.

3. **The Java platform.** OpenJDK is allowed under GPLv2 with the Classpath Exception, because the
   exception explicitly permits code that uses the platform to be released under any license. Any
   distributed build that includes platform components ships their license texts.

4. **Enforcement.** The automated license check in the build covers runtime and production
   dependencies, fails on any license outside the allow-list, and lists the Java platform as an
   explicit exception. Test and build scopes are not checked.

## Consequences

- JUnit, JaCoCo and similar tools can be used as build and test dependencies.
- Moving a dependency from a test scope to a runtime scope is a licensing decision: the check will
  catch a copyleft license at that moment.
- Copyleft source code is never copied into the repository, whatever it is used for.

## Sources

Checked on 2026-10-27:

- [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0/): definition of "Distribute";
  section 3, requirements when a Contributor Distributes the Program.
- [ASF 3rd Party License Policy](https://www.apache.org/legal/resolved.html): *"using a GPL'ed tool
  during the build is okay, but including GPL'ed source code is not"*.
- [OpenJDK: GPLv2 with the Classpath Exception](https://openjdk.org/legal/gplv2+ce.html).

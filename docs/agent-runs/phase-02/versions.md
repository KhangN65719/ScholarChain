# Phase 2 tool record

| Input | Pin or observed version |
| --- | --- |
| Scala compiler for all ScholarChain projects | 3.3.7 LTS |
| sbt launcher and build | 1.11.7 |
| sbt-scalafmt | 2.5.6 |
| Scalafmt engine | 3.10.0 |
| Verification JVM | Eclipse Adoptium Temurin 21.0.13, macOS arm64 |

`project/DependencyVersions.scala` supplies the application Scala and formatter plugin pins. The sbt launcher reads `project/build.properties` before loading the Scala ledger. Scalafmt reads its engine pin and dialect from `.scalafmt.conf`. Each direct version has one source.

Verification used a temporary sbt launcher and dependency cache outside the repository. Maven Central supplied the artifacts. No project environment variables, AWS access, or cloud credentials were required. The restricted test runner needed sbt's server autostart disabled and forced startup to run without binding a local socket.

## Full checklist additions

| Input | Pin |
| --- | --- |
| uPickle | 4.4.3 |
| MUnit | 1.1.1 |
| SLF4J API and runtime provider | 2.0.18 |
| Hadoop client API | 3.5.0 |
| Spark SQL for Scala 2.13 | 4.0.5 |
| Container JRE | Temurin 21.0.13+9, Jammy |
| Container base manifest | `sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269` |
| Observed local Docker server | 29.5.3, Linux arm64 |

Library pins are in `project/DependencyVersions.scala`. The Dockerfile declares its base image by digest. Java and Scala target Java 17 bytecode. Spark 4.0.5 supplies Scala 2.13.16 and its own Hadoop 3.4.1. Its Scala 2.13 version matches the resolved runtime of Scala 3.3.7. Keep the two Hadoop client environments separate and use Spark's Java/DataFrame API boundary. Later deployment work must match these API assumptions against the selected runtime.

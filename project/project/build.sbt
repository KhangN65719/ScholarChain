// Load the same ledger while evaluating project/plugins.sbt.
Compile / unmanagedSources += baseDirectory.value.getParentFile / "DependencyVersions.scala"

package scholarchain.spark

import java.nio.file.Files
import org.apache.spark.sql.Row

/** The synthetic run directories, written once per test JVM. */
object Fixtures:
  lazy val clean: InputLayout =
    SyntheticRun.write(
      Files.createTempDirectory("scholarchain-spark-clean-"),
      defects = false
    )

  lazy val defective: InputLayout =
    SyntheticRun.write(
      Files.createTempDirectory("scholarchain-spark-defects-"),
      defects = true
    )

  val context: RunContext = RunContext(
    RunContext.runIdFor("fixture-run"),
    s"hadoop-snapshot:${SyntheticRun.snapshot}",
    SyntheticRun.erRunId,
    SyntheticRun.hadoopRunId,
    RunContext.transformVersion
  )

  def label(nodeId: String): String =
    SyntheticRun.labels.getOrElse(nodeId, nodeId)

  def string(row: Row, name: String): String = row.getAs[String](name)

  def strings(row: Row, name: String): Vector[String] =
    row.getSeq[String](row.fieldIndex(name)).toVector

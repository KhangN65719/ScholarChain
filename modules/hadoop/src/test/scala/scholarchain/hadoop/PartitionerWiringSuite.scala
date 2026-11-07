package scholarchain.hadoop

import org.apache.hadoop.io.Text
import munit.FunSuite
import scholarchain.hadoop.jobs.StableKeyPartitioner
import scholarchain.hadoop.pipeline.{PartitionKey, StreamKey}

/** Coverage of the `Partitioner` subclass Hadoop actually calls.
  *
  * Separate from `JobWiringSuite` on purpose. These cases construct only
  * `StableKeyPartitioner` and `Text`, never `Configuration`, so they do not
  * depend on the shaded XML parser that `hadoop-client-runtime` supplies. In
  * run 4 they were skipped as collateral of that suite's abort despite needing
  * nothing it was missing; splitting them lets them run today, before the
  * Test-scope dependency is added.
  *
  * Why this is not redundant with `IdentifierAndKeySuite`: that suite tests
  * `PartitionKey.partitionOf` and `StreamKey` as pure functions, and composes
  * them the way the partitioner is *supposed* to. It cannot detect
  * `StableKeyPartitioner` failing to compose them that way. If `getPartition`
  * hashed the whole `Text` instead of the logical key, every case in that suite
  * would still pass while every reduce-side join in the pipeline silently
  * broke. These cases close that gap.
  */
class PartitionerWiringSuite extends FunSuite:
  test("the partitioner routes on the logical key, ignoring the stream label") {
    // The property the whole reduce-side join rests on: two streams carrying
    // one logical key must land on the same reducer.
    val partitioner = new StableKeyPartitioner
    val logical = "doi|10.14778/1687553.1687577"
    val value = new Text("")
    val viaWorks =
      partitioner.getPartition(
        new Text(StreamKey.encode("works", logical)),
        value,
        7
      )
    val viaKeys = partitioner.getPartition(
      new Text(StreamKey.encode("identifier-keys", logical)),
      value,
      7
    )
    assertEquals(viaWorks, viaKeys)
    // And it agrees with the definition the local runner reports from.
    assertEquals(viaWorks, PartitionKey.partitionOf(logical, 7))
  }

  test("partition indices stay in range and are stable across calls") {
    val partitioner = new StableKeyPartitioner
    val value = new Text("")
    val keys = Vector(
      "work:v1:dblp:AAAA",
      "researcher|name-year|ley|m|2009",
      "doi|10.1109/jcdl.2017.7991609",
      "mention:v1:" + "0" * 64
    )
    (1 to 8).foreach { partitions =>
      keys.foreach { logical =>
        val key = new Text(StreamKey.encode("works", logical))
        val first = partitioner.getPartition(key, value, partitions)
        val second = partitioner.getPartition(key, value, partitions)
        assertEquals(first, second, s"$logical is not stable")
        assert(first >= 0 && first < partitions, s"$logical left the range")
      }
    }
  }

  test("an unlabeled key partitions as itself") {
    val partitioner = new StableKeyPartitioner
    val bare = "work:v1:dblp:AAAA"
    assertEquals(
      partitioner.getPartition(new Text(bare), new Text(""), 4),
      PartitionKey.partitionOf(bare, 4)
    )
  }

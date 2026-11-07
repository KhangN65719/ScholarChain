package scholarchain.hadoop.jobs

import org.apache.hadoop.io.Text
import org.apache.hadoop.mapreduce.Partitioner
import scholarchain.hadoop.pipeline.{PartitionKey, StreamKey}

/** The explicit partition strategy.
  *
  * Every job in this pipeline partitions on a SHA-256 digest of the **logical
  * key**, with the stream label stripped first.
  *
  * Two reasons for not using Hadoop's default `HashPartitioner`:
  *
  *   1. `HashPartitioner` hashes the whole `Text`, so the stream label would
  *      take part and the same logical key would split across reducers,
  *      silently breaking every reduce-side join in the pipeline.
  *   2. `String.hashCode` is specified and stable, but it clusters badly on the
  *      structured keys used here — DOI prefixes, `family|initials` name blocks
  *      and title prefixes share long common substrings, which skews a
  *      multiplicative hash and loads one reducer. A cryptographic digest
  *      spreads them evenly.
  *
  * The digest is content-derived with no seed, so a partition assignment is
  * identical on every run and on every node. That is what lets a rerun produce
  * the same per-partition files, and it is why the output is reproducible
  * rather than merely correct.
  */
class StableKeyPartitioner extends Partitioner[Text, Text]:
  override def getPartition(
      key: Text,
      value: Text,
      partitions: Int
  ): Int =
    PartitionKey.partitionOf(StreamKey.logicalKey(key.toString), partitions)

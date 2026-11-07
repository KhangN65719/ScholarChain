package scholarchain.hadoop.pipeline

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** The pipeline's partition function, defined without any Hadoop dependency so
  * the Hadoop partitioner, the local runner and the tests all use one
  * definition rather than three that can drift apart.
  *
  * The assignment is a SHA-256 digest of the logical key. It is content-derived
  * with no seed and no locale or platform input, so a key lands on the same
  * partition index on every node and on every rerun. That is the property the
  * idempotent output layout rests on.
  */
object PartitionKey:
  def partitionOf(logicalKey: String, partitions: Int): Int =
    require(partitions > 0, "Partition count must be positive")
    val digest =
      MessageDigest.getInstance("SHA-256").digest(logicalKey.getBytes(UTF_8))
    // Four bytes, with the top bit masked off so the value is never negative.
    val bits = ((digest(0) & 0x7f) << 24) | ((digest(1) & 0xff) << 16) |
      ((digest(2) & 0xff) << 8) | (digest(3) & 0xff)
    bits % partitions

/** Packs a stream label and a logical key into one shuffle key.
  *
  * Partitioning and grouping must act on the logical key alone. If the label
  * took part, two streams carrying the same logical key would land on different
  * reducers and every reduce-side join in the pipeline would break silently.
  * [[PartitionKey]] is therefore always applied to [[StreamKey.logicalKey]] and
  * never to the encoded key.
  *
  * The separator is `U+0001`, which cannot occur in any key the pipeline mints:
  * identifiers are digests or Base64url payloads, and every normalized name,
  * title and venue key has had control characters folded out.
  */
object StreamKey:
  val separator = '\u0001'

  def encode(label: String, key: String): String = s"$label$separator$key"

  def decode(encoded: String): (String, String) =
    val index = encoded.indexOf(separator.toInt)
    if index < 0 then ("", encoded)
    else (encoded.take(index), encoded.drop(index + 1))

  def label(encoded: String): String = decode(encoded)._1

  def logicalKey(encoded: String): String = decode(encoded)._2

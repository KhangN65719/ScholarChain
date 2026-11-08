package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** `hash-embed-v1`: a local, offline, deterministic embedder. It is lexical
  * (signed feature hashing), not semantic, and uses no network and no
  * dependency. Specification:
  *
  *   1. Normalise to Unicode NFKC, then lowercase with `Locale.ROOT`.
  *   2. Split on every run of characters that are neither a letter nor a digit
  *      (`[^\p{L}\p{N}]+`) and drop empty pieces. These are the words.
  *   3. Features are the word unigrams and the adjacent-word bigrams (two words
  *      joined by one space). A one-word text has no bigram.
  *   4. Each distinct feature is hashed: SHA-256 of its UTF-8 bytes. The first
  *      8 bytes as an unsigned big-endian integer, modulo 384, give the bucket.
  *      The lowest bit of the byte at index 8 (the ninth byte) gives the sign:
  *      0 is +1, 1 is -1.
  *   5. The weight of a feature is `1 + ln(count)` (sublinear TF), computed
  *      with `StrictMath.log`. Weights times signs are added into their buckets
  *      in ascending order of the feature string (UTF-16 code unit order) in
  *      double precision.
  *   6. The vector is divided by its L2 norm and each element is narrowed to a
  *      float.
  *
  * A text with no word, or one whose buckets cancel to a zero vector, is
  * refused with `EmbeddingInvalid`: a zero vector has no direction under cosine
  * distance. There is no randomness, clock or locale input, and `StrictMath`
  * fixes the floating-point results across JVMs. The Unicode tables of the
  * running JDK define NFKC, letters and lowercase, so a JDK with different
  * tables could differ for rare characters.
  */
final class HashEmbedder extends Embedder:
  val modelVersion: String = HashEmbedder.ModelVersion
  val dimension: Int = HashEmbedder.Dimension

  def embed(text: String): Vector[Float] = HashEmbedder.embedText(text)

object HashEmbedder:
  val ModelVersion = "hash-embed-v1"
  val Dimension = 384

  private val nonWord = "[^\\p{L}\\p{N}]+"

  def words(text: String): Vector[String] =
    Normalizer
      .normalize(text, Normalizer.Form.NFKC)
      .toLowerCase(Locale.ROOT)
      .split(nonWord)
      .iterator
      .filter(_.nonEmpty)
      .toVector

  /** Feature to occurrence count. */
  def features(text: String): Map[String, Int] =
    val ws = words(text)
    val bigrams =
      if ws.size < 2 then Vector.empty
      else ws.sliding(2).map(pair => s"${pair(0)} ${pair(1)}").toVector
    (ws ++ bigrams).groupMapReduce(identity)(_ => 1)(_ + _)

  /** The bucket and the sign of one feature. */
  def bucketAndSign(feature: String): (Int, Double) =
    val digest =
      MessageDigest.getInstance("SHA-256").digest(feature.getBytes(UTF_8))
    var head = 0L
    var i = 0
    while i < 8 do
      head = (head << 8) | (digest(i) & 0xffL)
      i += 1
    val bucket = java.lang.Long.remainderUnsigned(head, Dimension.toLong).toInt
    val sign = if (digest(8) & 1) == 0 then 1.0 else -1.0
    (bucket, sign)

  def embedText(text: String): Vector[Float] =
    val counts = features(text)
    Fail.unless(
      counts.nonEmpty,
      ErrorCode.EmbeddingInvalid,
      "Text has no letters or digits; hash-embed-v1 refuses it"
    )
    val sums = new Array[Double](Dimension)
    counts.toVector.sortBy(_._1).foreach { case (feature, count) =>
      val (bucket, sign) = bucketAndSign(feature)
      sums(bucket) += sign * (1.0 + StrictMath.log(count.toDouble))
    }
    var squares = 0.0
    var i = 0
    while i < Dimension do
      squares += sums(i) * sums(i)
      i += 1
    val norm = StrictMath.sqrt(squares)
    Fail.unless(
      norm > 0.0 && norm.isFinite,
      ErrorCode.EmbeddingInvalid,
      "Text features cancel to a zero vector; hash-embed-v1 refuses it"
    )
    Vector.tabulate(Dimension)(index => (sums(index) / norm).toFloat)

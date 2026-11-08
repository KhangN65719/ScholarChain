package scholarchain.storage

import java.math.BigInteger
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

class HashEmbedderSuite extends munit.FunSuite:
  private val embedder = new HashEmbedder

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  /** The specification written out a second way: `findAllIn` instead of
    * `split`, `BigInteger` instead of `remainderUnsigned`.
    */
  private def reference(text: String): Vector[Float] =
    val words = "[\\p{L}\\p{N}]+".r
      .findAllIn(
        Normalizer
          .normalize(text, Normalizer.Form.NFKC)
          .toLowerCase(Locale.ROOT)
      )
      .toVector
    val grams = words ++ words.zip(words.drop(1)).map((a, b) => s"$a $b")
    val counts = grams.groupBy(identity).view.mapValues(_.size).toMap
    val sums = Array.fill(384)(0.0)
    counts.toVector.sortBy(_._1).foreach { (feature, count) =>
      val d =
        MessageDigest.getInstance("SHA-256").digest(feature.getBytes(UTF_8))
      val bucket =
        new BigInteger(1, d.take(8)).mod(BigInteger.valueOf(384)).intValue
      val sign = if (d(8) & 1) == 0 then 1.0 else -1.0
      sums(bucket) += sign * (1.0 + StrictMath.log(count.toDouble))
    }
    val norm = StrictMath.sqrt(sums.foldLeft(0.0)((acc, x) => acc + x * x))
    sums.toVector.map(x => (x / norm).toFloat)

  private def cosine(a: Vector[Float], b: Vector[Float]): Double =
    a.zip(b).map((x, y) => x.toDouble * y).sum

  test("model version and dimension") {
    assertEquals(embedder.modelVersion, "hash-embed-v1")
    assertEquals(embedder.dimension, 384)
    assertEquals(embedder.embed("graph theory").size, 384)
  }

  test(
    "an exact known vector: SHA-256('abc') starts ba7816bf8f01cfea, byte 8 is 0x41"
  ) {
    // 0xba7816bf8f01cfea mod 384 = 234 and 0x41 has its low bit set, so the one
    // feature 'abc' is -1.0 in bucket 234 and every other element is 0.
    val expected = Vector.tabulate(384)(i => if i == 234 then -1.0f else 0.0f)
    assertEquals(embedder.embed("abc"), expected)
    assertEquals(embedder.embed("  ABC!!  "), expected)
  }

  test(
    "the implementation equals an independent reading of the specification"
  ) {
    Vector(
      "graph neural networks and community detection",
      "graph graph graph theory",
      "Café naïve — Ünicöde 123, 2000-2009",
      "ＡＢＣ full width and a①",
      "one",
      "a b a b a b"
    ).foreach(text => assertEquals(embedder.embed(text), reference(text), text))
  }

  test("vectors are unit length and deterministic across instances") {
    val text = "Researcher Ada works on graph neural networks"
    val a = embedder.embed(text)
    assertEquals(a, new HashEmbedder().embed(text))
    assertEquals(a, HashEmbedder.embedText(text))
    val norm = math.sqrt(a.map(x => x.toDouble * x).sum)
    assertEqualsDouble(norm, 1.0, 1e-6)
  }

  test("features are unigrams and adjacent bigrams with counts") {
    assertEquals(
      HashEmbedder.features("a b"),
      Map("a" -> 1, "b" -> 1, "a b" -> 1)
    )
    assertEquals(HashEmbedder.features("x x"), Map("x" -> 2, "x x" -> 1))
    assertEquals(HashEmbedder.features("solo"), Map("solo" -> 1))
    assertEquals(
      HashEmbedder.words("Alpha-beta_gamma 42 delta"),
      Vector("alpha", "beta", "gamma", "42", "delta")
    )
  }

  test("NFKC and case do not change the vector") {
    assertEquals(embedder.embed("ＧＲＡＰＨ"), embedder.embed("graph"))
    assertEquals(
      embedder.embed("Graph Theory"),
      embedder.embed("graph   THEORY")
    )
  }

  test("empty and featureless text is refused, never a zero vector") {
    Vector("", "   ", "!!! --- ???", "\n\t", "——").foreach { text =>
      assertEquals(code(embedder.embed(text)), ErrorCode.EmbeddingInvalid, text)
    }
  }

  test("related texts are nearer than unrelated texts") {
    val base = embedder.embed("graph neural networks for community detection")
    val near =
      embedder.embed("community detection with graph neural network models")
    val far = embedder.embed("protein folding in molecular biology")
    assert(cosine(base, near) > cosine(base, far))
    assertNotEquals(base, far)
  }

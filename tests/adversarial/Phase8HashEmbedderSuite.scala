package scholarchain.tests.adversarial

import java.math.BigInteger
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import munit.FunSuite
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Random
import scholarchain.storage.*

/** Spec item 1: `hash-embed-v1` against an independent reference reading of the
  * specification.
  */
class Phase8HashEmbedderSuite extends FunSuite:
  /** The reference: written from the specification text only. */
  private object Ref:
    val dim = 384

    def words(text: String): Vector[String] =
      Normalizer
        .normalize(text, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .split("[^\\p{L}\\p{N}]+")
        .toVector
        .filter(_.nonEmpty)

    def embed(text: String): Option[Array[Double]] =
      val words = this.words(text)
      val bigrams = words.sliding(2).collect { case Vector(a, b) =>
        s"$a $b"
      }
      val features = words ++ bigrams
      if features.isEmpty then None
      else
        val sums = new Array[Double](dim)
        features.groupBy(identity).foreach { case (feature, all) =>
          val digest =
            MessageDigest.getInstance("SHA-256").digest(feature.getBytes(UTF_8))
          val bucket = new BigInteger(1, digest.take(8))
            .mod(BigInteger.valueOf(dim.toLong))
            .intValue
          val sign = if (digest(8) & 0x01) == 0 then 1.0 else -1.0
          sums(bucket) += sign * (1.0 + math.log(all.size.toDouble))
        }
        val norm = math.sqrt(sums.map(x => x * x).sum)
        Some(sums.map(_ / norm))

  private def norm(v: Vector[Float]): Double =
    math.sqrt(v.map(x => x.toDouble * x).sum)

  private def assertMatchesReference(text: String): Unit =
    val produced = new HashEmbedder().embed(text)
    val expected = Ref.embed(text).getOrElse(fail(s"reference refused: $text"))
    assertEquals(produced.size, 384)
    produced.indices.foreach { i =>
      assert(
        math.abs(produced(i) - expected(i)) < 2e-6,
        s"component $i differs for ${text.take(40)}: ${produced(i)} vs " +
          expected(i)
      )
    }
    assert(math.abs(norm(produced) - 1.0) < 1e-5, "not unit length")

  private val vocabulary =
    Vector("alpha", "beta", "gamma", "delta", "Epsilon", "ZETA", "ünï", "张伟") ++
      (0 until 500).map(i => s"w$i")

  private def longText(words: Int, seed: Long): String =
    val r = new Random(seed)
    Vector.fill(words)(vocabulary(r.nextInt(vocabulary.size))).mkString(" ")

  private val corpus: Vector[(String, String)] = Vector(
    "plain" -> "the quick brown fox jumps over the lazy dog",
    "mixed case" -> "Mixed CASE Words mixed case words MIXED",
    "punctuation runs" -> "a---b...c!!!d,,,;;;e",
    "repeated words" -> "spam spam spam spam spam",
    "two repeated words" -> "a a",
    "single word" -> "abc",
    "single character" -> "a",
    "digits" -> "123 456 789 123",
    "unicode names" -> "Zoë 张伟 Müller İstanbul straße ẞ Ωmega",
    "compatibility forms" -> "ﬁne ﬂuor ǅ １２３ ＡＢＣ ㎏",
    "combining marks" -> "café café",
    "whitespace kinds" -> "tab\tnew\nline\r\nruns   and nbsp",
    "emoji between words" -> "emoji 😀 text 😀 more",
    "leading and trailing junk" -> "  --- hello, world!!! ---  ",
    "underscores and hyphens" -> "snake_case kebab-case dotted.name",
    "very long text" -> longText(20000, 42L),
    "one huge word" -> ("x" * 100000),
    "long with repeats" -> (longText(300, 7L) + " " + longText(300, 7L))
  )

  corpus.foreach { case (name, text) =>
    test(s"matches the independent reference: $name"):
      assertMatchesReference(text)
  }

  test("a random corpus of 200 texts matches the reference"):
    val r = new Random(2026)
    val pool = Vector(
      "a",
      "B",
      "Ça",
      "ñ",
      "张",
      "7",
      "x1",
      "-",
      "...",
      " ",
      "\n",
      "ﬁ",
      "É",
      "ǆ",
      "😀"
    )
    (0 until 200).foreach { _ =>
      val text = Vector.fill(1 + r.nextInt(60))(pool(r.nextInt(pool.size)))
      val joined = text.mkString(if r.nextBoolean() then " " else "")
      if Ref.embed(joined).isDefined then assertMatchesReference(joined)
      else
        assertEquals(
          Phase7Storage.code(new HashEmbedder().embed(joined)),
          ErrorCode.EmbeddingInvalid
        )
    }

  test("known answer: SHA-256(abc) puts -1 in bucket 234"):
    val v = new HashEmbedder().embed("abc")
    assertEquals(v(234), -1.0f)
    assertEquals(v.count(_ != 0f), 1)

  test("identity: model version, dimension, unit length"):
    val e = new HashEmbedder
    assertEquals(e.modelVersion, "hash-embed-v1")
    assertEquals(e.dimension, 384)
    assertEquals(HashEmbedder.ModelVersion, "hash-embed-v1")
    assertEquals(HashEmbedder.Dimension, 384)

  test("text with no letter or digit is refused, never embedded as zero"):
    val e = new HashEmbedder
    Vector(
      "",
      " ",
      "\n\t",
      "---",
      "!!!???...",
      "😀😀",
      "\u0000",
      "—–_",
      "  "
    ).foreach { text =>
      assertEquals(
        Phase7Storage.code(e.embed(text)),
        ErrorCode.EmbeddingInvalid,
        s"text ${text.map(_.toInt)}"
      )
    }

  test("deterministic: same text gives bit-identical vectors, also in threads"):
    val e = new HashEmbedder
    val text = longText(500, 11L)
    val first = e.embed(text)
    assertEquals(e.embed(text), first)
    val threads = (0 until 8).map { _ =>
      Future(new HashEmbedder().embed(text))(ExecutionContext.global)
    }
    threads.foreach { f =>
      assertEquals(Await.result(f, 60.seconds), first)
    }

  test("case, punctuation and spacing do not change the vector"):
    val e = new HashEmbedder
    assertEquals(e.embed("Hello, World!"), e.embed("hello   world"))
    assertEquals(e.embed("A-B C"), e.embed("a b c"))
    assertEquals(e.embed("ﬁne"), e.embed("FINE"))

  test("word order matters only through bigrams"):
    val e = new HashEmbedder
    assertNotEquals(e.embed("alpha beta"), e.embed("beta alpha"))
    // The same word set in a different order still shares the unigrams.
    val a = e.embed("alpha beta")
    val b = e.embed("beta alpha")
    val dot = a.zip(b).map((x, y) => x.toDouble * y).sum
    assert(dot > 0.3 && dot < 0.999, s"dot $dot")

  // The JVM default Locale is global and root suites run in parallel in one
  // JVM, so this suite never calls Locale.setDefault. HashEmbedder passes
  // Locale.ROOT explicitly, so the locale-dependent case folds are pinned
  // against the reference (written with Locale.ROOT) on Turkish-sensitive input
  // instead: under a Turkish default, "I" would fold to a dotless "ı".
  test("case folding is the root-locale folding, not the default locale's"):
    val before = Locale.getDefault
    val text = "TITLE I İSTANBUL Iıİi"
    assertEquals(HashEmbedder.words("TITLE I"), Vector("title", "i"))
    assertEquals(HashEmbedder.words(text), Ref.words(text))
    assertEquals(
      new HashEmbedder().embed("TITLE"),
      new HashEmbedder().embed("title")
    )
    assertMatchesReference(text)
    assertMatchesReference("KIRMIZI ISIK")
    assertEquals(Locale.getDefault, before)

  test("repeated words weigh 1 + ln(count), not count"):
    val e = new HashEmbedder
    val one = e.embed("alpha")
    val many = e.embed("alpha alpha alpha alpha")
    // Unigram alpha (count 4) and bigram "alpha alpha" (count 3) are separate
    // features: check against the reference rather than a hand formula.
    assertEquals(one.count(_ != 0f), 1)
    assertMatchesReference("alpha alpha alpha alpha")
    assert(many.count(_ != 0f) <= 2)

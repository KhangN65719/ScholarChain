package scholarchain.hadoop

import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import scholarchain.core.SourceType
import scholarchain.core.pairing.{BlockKind, BlockStrategy}
import scholarchain.hadoop.pipeline.{
  BlockMember,
  Blocking,
  DblpParser,
  TextNormalizer
}

/** F2: the folded form is a matching key only, and it is non-empty and
  * deterministic for every script. Stored records keep the original characters.
  */
class NonLatinFoldingSuite extends FunSuite:
  private val run = TestRun.context()
  private val cyrillicCapitalA = 0x0410.toChar.toString

  private def parse(xml: String) =
    DblpParser.parse(xml, run).getOrElse(fail(s"parse failed for $xml"))

  test("Latin with decomposable diacritics is unchanged") {
    assertEquals(TextNormalizer.nameKey("Émile Zola"), "emile zola")
    assertEquals(
      TextNormalizer.nameKey("Universität Trier"),
      "universitat trier"
    )
    assertEquals(
      TextNormalizer.nameKey("Émile Zola"),
      TextNormalizer.nameKey("Émile Zola")
    )
    assertEquals(TextNormalizer.nameBlockKey("Michael Ley"), "ley|m")
  }

  test("Latin letters with no decomposition fold to ASCII") {
    assertEquals(TextNormalizer.nameKey("Bjørn Ødegård"), "bjorn odegard")
    assertEquals(
      TextNormalizer.nameKey("Bjørn Ødegård"),
      TextNormalizer.nameKey("Bjorn Odegard")
    )
    assertEquals(TextNormalizer.nameKey("Lech Wałęsa"), "lech walesa")
    assertEquals(TextNormalizer.nameKey("Işık"), "isik")
    assertEquals(TextNormalizer.nameKey("Straße"), "strasse")
  }

  test("CJK names give non-empty, deterministic, same-script keys") {
    assertEquals(TextNormalizer.nameKey("李明"), "李明")
    assertEquals(TextNormalizer.titleKey("日本語の論文"), "日本語の論文")
    assertEquals(TextNormalizer.nameKey("김민수"), "김민수")
    // Fullwidth and halfwidth forms unify through NFKC.
    assertEquals(TextNormalizer.nameKey("ｱｲｳ"), "アイウ")
    // Folding is idempotent, so reruns and re-folds cannot drift.
    assertEquals(
      TextNormalizer.fold(TextNormalizer.fold("李明")),
      TextNormalizer.fold("李明")
    )
  }

  test("meaningful marks in non-Latin scripts are kept") {
    // Dakuten distinguishes か from が; stripping it would merge two names.
    assertEquals(TextNormalizer.nameKey("が"), "が")
    assert(TextNormalizer.nameKey("が") != TextNormalizer.nameKey("か"))
    // Hangul decomposes to jamo under NFD and must come back composed.
    assertEquals(
      TextNormalizer.nameKey("가"),
      TextNormalizer.nameKey("가")
    )
  }

  test("Cyrillic, Greek and Arabic keep their own script") {
    assertEquals(TextNormalizer.nameKey("Владимир"), "владимир")
    assertEquals(TextNormalizer.nameKey("ВЛАДИМИР"), "владимир")
    assertEquals(TextNormalizer.nameKey("Ёлка"), "елка")
    assertEquals(TextNormalizer.nameKey("Ανάλυση"), "αναλυση")
    assertEquals(
      TextNormalizer.nameKey("ΟΔΥΣΣΕΥΣ"),
      TextNormalizer.nameKey("Οδυσσευσ")
    )
    assertEquals(TextNormalizer.nameKey("مُحَمَّد"), "محمد")
    assertEquals(TextNormalizer.nameBlockKey("Владимир Путин"), "путин|в")
  }

  test("a mixed-script name keeps the foreign letter instead of deleting it") {
    val spoofed = s"${cyrillicCapitalA}nn Lee"
    val key = TextNormalizer.nameKey(spoofed)
    assertEquals(key.length, "ann lee".length)
    assert(
      key != "ann lee",
      "a homoglyph must not silently equal the Latin name"
    )
    assert(key.endsWith("nn lee"))
  }

  test("a lone combining mark is still not a name") {
    assertEquals(TextNormalizer.nameKey("́"), "")
  }

  test("stored records keep original characters; only the key is folded") {
    val author = parse(
      """<article key="cjk/1"><author>李明</author><title>A Paper.</title></article>"""
    ).work.authors.head
    assertEquals(author.name, "李明")
    assertEquals(author.normalizedName, "李明")

    val latin = parse(
      """<article key="lat/1"><author>Bjørn Ødegård</author><title>T.</title></article>"""
    ).work.authors.head
    assertEquals(latin.name, "Bjørn Ødegård")
    assertEquals(latin.normalizedName, "bjorn odegard")

    val work = parse(
      """<article key="cjk/2"><author>A B</author><title>日本語の論文</title></article>"""
    ).work
    assertEquals(work.title, "日本語の論文")
    assertEquals(work.normalizedTitle, "日本語の論文")
  }

  test("different CJK names land in different blocks, not one") {
    val work = parse(
      """<article key="cjk/3"><author>李明</author><author>王芳</author><author>张伟</author><title>T.</title></article>"""
    ).work
    val keys = work.authors.map(mention =>
      Blocking
        .researcherKeys(mention, work, Vector.empty)
        .collect { case (BlockStrategy.NameInitials, key) => key }
    )
    assert(keys.forall(_.size == 1))
    assertEquals(keys.flatten.distinct.size, 3)
    keys.flatten.foreach(key => assert(!key.endsWith("|"), key))
  }

  test("a title prefix never splits a supplementary-plane letter") {
    val title = "𠀀" * 30
    val work = parse(
      s"""<article key="cjk/4"><author>A B</author><title>$title</title></article>"""
    ).work
    val keys = Blocking.workKeys(work)
    assert(keys.nonEmpty)
    keys.foreach { case (_, key) =>
      assertEquals(new String(key.getBytes(UTF_8), UTF_8), key)
    }
  }

  test("an oversize same-name CJK block is still bounded and counted") {
    val bounded = TestRun.context(maxBlockSize = 4)
    val members = (1 to 50).toVector.map(index =>
      BlockMember(
        kind = BlockKind.Researcher,
        memberId = f"mention-$index%02d",
        source = SourceType.Dblp,
        displayName = "李明",
        normalizedName = TextNormalizer.nameKey("李明"),
        normalizedTitle = "t",
        year = Some(2017),
        venueId = None,
        affiliations = Vector.empty,
        coauthorNames = Vector.empty,
        externalIds = Map.empty,
        workId = s"work:v1:dblp:W$index"
      )
    )
    val (group, retained) =
      Blocking.bound("researcher|name-initials|李明|李", members, bounded)
    assertEquals(group.observedSize, 50)
    assertEquals(group.retainedSize, 4)
    assertEquals(retained.size, 4)
    assert(group.truncated)
  }

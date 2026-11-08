package scholarchain.tests.adversarial

import munit.FunSuite
import scholarchain.hadoop.pipeline.DblpParser
import scholarchain.hadoop.pipeline.TextNormalizer

/** Adversarial Unicode: normalization forms, combining marks, supplementary
  * planes, bidirectional controls, homoglyphs and case-folding edges in author
  * names and titles.
  *
  * `IdentifierAndKeySuite` pins the cases that fold cleanly, which are the ones
  * whose diacritics have a canonical decomposition: `Émile`, `Universität`, an
  * ASCII `ILKAY`. The cases here are the ones that do not.
  * `TextNormalizer.fold` ends with `[^0-9a-z]+ -> " "`, so every character that
  * does not reduce to ASCII after NFKC, lowercasing, NFD and mark stripping
  * becomes a word break. Two families of real author name hit that: non-Latin
  * scripts, which fold to nothing at all, and Latin letters with no canonical
  * decomposition, which split a name into bogus tokens.
  *
  * Characters that are invisible in source are built from code points rather
  * than pasted, so a reader can see which one is under test.
  */
class AdversarialUnicodeSuite extends FunSuite:
  private val run = AdversarialFixtures.run()

  private val cyrillicCapitalA = 0x0410.toChar.toString
  private val rightToLeftOverride = 0x202e.toChar.toString
  private val popDirectionalFormatting = 0x202c.toChar.toString
  private val combiningAcute = 0x0301.toChar.toString
  private val mathematicalBoldCapitalM = "𝐌"
  private val grinningFace = "😀"

  test("every non-Latin script folds to a non-empty deterministic key") {
    assertEquals(TextNormalizer.fold("李明"), "李明")
    assertEquals(TextNormalizer.fold("Ανάλυση"), "αναλυση")
    assertEquals(TextNormalizer.fold("محمد"), "محمد")
    assertEquals(TextNormalizer.fold("Владимир"), "владимир")
    assertEquals(TextNormalizer.nameKey("李明"), "李明")
    assertEquals(TextNormalizer.titleKey("日本語の論文"), "日本語の論文")
    assertEquals(TextNormalizer.fold("李明"), TextNormalizer.fold("李明"))
  }

  test("different non-Latin names do not collapse into one block") {
    val keys = Vector("李明", "王伟", "محمد", "Владимир", "김민수")
      .map(TextNormalizer.nameKey)
    assert(keys.forall(_.nonEmpty))
    assertEquals(keys.distinct.size, keys.size)
    assertNotEquals(
      TextNormalizer.nameBlockKey("李明"),
      TextNormalizer.nameBlockKey("王伟")
    )
  }

  test("Japanese dakuten is kept, so か and が stay distinct") {
    assertNotEquals(TextNormalizer.fold("か"), TextNormalizer.fold("が"))
  }

  test("an author name in a non-Latin script is accepted with its original") {
    // F1 CONVERTED from a pin of the defect to an assertion of the repair,
    // landing in the same cycle as `hadoop-engineer`'s fix. Corrected
    // assertion exactly as specified in final-review.md.
    //
    // What the pin established: the parser intends to reject this -- it
    // tests `mentions.exists(_.normalizedName.isEmpty)` at
    // `DblpParser.scala:145-148` -- but `AuthorMention`'s constructor
    // assertion (`Contracts.scala:138`) throws first during the `mentions`
    // construction at `:118-137`, so that guard was dead code. The review
    // escalated it: `DblpExtractionJob.map:50-89` has no `try`, so the
    // exception failed the task attempt and, after retries, the stage. One
    // author name composed only of non-ASCII letters anywhere in a snapshot
    // killed job 1.
    val xml =
      """<article key="cjk/1"><author>李明</author><title>A Paper.</title></article>"""
    val parsed = DblpParser.parse(xml, run)
    val publication = parsed.getOrElse(fail(s"rejected: $parsed"))
    val author = publication.work.authors.head
    assertEquals(author.name, "李明")
    assertEquals(author.normalizedName, "李明")
  }

  test("a title in a non-Latin script is accepted with its original") {
    // F1 CONVERTED, same repair. This half threw from `WorkRecord`'s
    // nonblank normalized-title assertion (`Contracts.scala:104`, reached at
    // `DblpParser.scala:170`), with no guard at all, dead or otherwise.
    val xml =
      """<article key="cjk/2"><author>A B</author><title>日本語の論文</title></article>"""
    val parsed = DblpParser.parse(xml, run)
    val publication = parsed.getOrElse(fail(s"rejected: $parsed"))
    assert(publication.work.title.contains("日本語の論文"))
    assertEquals(publication.work.normalizedTitle, "日本語の論文")
  }

  test("a Latin letter with no decomposition becomes a word break") {
    // DEFECT, owner hadoop-engineer. These are ordinary European author
    // names, not hostile input. Each folds into tokens that are not the
    // name's words, and nothing counts or reports it: the key is nonblank,
    // so no guard fires.
    // REMEDY NOW DECIDED, see docs/agent-runs/phase-04/decisions.md section
    // 2: retain the original characters in all stored records, and fold or
    // transliterate only into a separate matching key. Not implemented this
    // cycle -- it is a Phase 5 condition, due before Phase 5 consumes
    // blocking keys -- so this pin stays as a pin and deliberately carries
    // no `.fail` twin: a twin would assert a key shape that does not exist
    // yet. Convert both cases when the remedy lands.
    assertEquals(TextNormalizer.nameKey("Bjørn Ødegård"), "bjorn odegard")
    assertEquals(TextNormalizer.nameKey("Lech Wałęsa"), "lech walesa")
    assertEquals(TextNormalizer.nameKey("Işık"), "isik")
    assertEquals(TextNormalizer.nameKey("Straße"), "strasse")
    assertEquals(
      TextNormalizer.nameKey("Bjørn Ødegård"),
      TextNormalizer.nameKey("Bjorn Odegard")
    )
  }

  test("the resulting block key names the wrong family and initials") {
    // The consequence of the case above, stated where it does the damage.
    // `nameBlockKey` takes the last token as the family name, so a name
    // split by a dropped letter blocks under a fragment.
    assertEquals(TextNormalizer.nameBlockKey("Bjørn Ødegård"), "odegard|b")
    assertEquals(TextNormalizer.nameBlockKey("Lech Wałęsa"), "walesa|l")
    assertEquals(TextNormalizer.familyToken("Bjørn Ødegård"), "odegard")
    // `initials` runs over every token and `nameBlockKey` over all but the
    // last, so the dropped letter corrupts both, differently.
    assertEquals(TextNormalizer.initials("Bjørn Ødegård"), "bo")
    assertEquals(TextNormalizer.initials("Michael Ley"), "ml")
  }

  test("a case-folding pair that should agree does not") {
    // German sharp s against its uppercase expansion. One name, two keys,
    // so the two spellings never meet in a block.
    assertEquals(TextNormalizer.nameKey("Strasse"), "strasse")
    assertEquals(
      TextNormalizer.nameKey("Straße"),
      TextNormalizer.nameKey("Strasse")
    )
  }

  test("the Turkish dotted capital I folds but the dotless i does not") {
    // U+0130 decomposes to `i` plus a combining dot, which the mark strip
    // removes, so the capital form folds correctly. U+0131 has no
    // decomposition, so it becomes a word break in the same name.
    assertEquals(TextNormalizer.nameKey("İlkay"), "ilkay")
    assertEquals(TextNormalizer.nameKey("İlkay Altıntaş"), "ilkay altintas")
  }

  test("a Cyrillic homoglyph silently redirects a name to another block") {
    // DEFECT, owner hadoop-engineer. The Cyrillic capital A is visually
    // identical to the Latin one and NFKC does not unify them, so the
    // initial is deleted rather than folded. The display name still reads
    // "Ann Lee" while the block key says someone else.
    // Required: a mixed-script name is reported, in a counter or a reject,
    // rather than folded into a different identity.
    val spoofed = s"${cyrillicCapitalA}nn Lee"
    // The initial is no longer deleted. It survives as the Cyrillic letter,
    // so the spoof is visible as a different key, but the mixed-script name
    // is still neither reported nor unified with the Latin spelling.
    assertEquals(TextNormalizer.nameKey(spoofed), "аnn lee")
    assertEquals(TextNormalizer.nameKey("Ann Lee"), "ann lee")
    assertEquals(TextNormalizer.nameBlockKey(spoofed), "lee|а")
    assertEquals(TextNormalizer.nameBlockKey("Ann Lee"), "lee|a")
  }

  test("a combining mark on its own is not a name") {
    assertEquals(TextNormalizer.nameKey(combiningAcute), "")
    assertEquals(TextNormalizer.nameKey(combiningAcute * 8), "")
  }

  test("a bidirectional control is dropped from the key, kept on display") {
    // The override survives on the display name and disappears from the
    // key, so two records can present opposite reading order and block
    // identically. Recorded as a spoofing surface for the owner; the
    // normalization itself is right.
    val hostile =
      rightToLeftOverride + "Michael Ley" + popDirectionalFormatting
    assertEquals(TextNormalizer.nameKey(hostile), "michael ley")
    val xml =
      s"""<article key="bidi/1"><author>$hostile</author><title>T.</title></article>"""
    val parsed = DblpParser.parse(xml, run)
    val publication = parsed.getOrElse(fail(s"parse failed: $parsed"))
    val author = publication.work.authors.head
    assert(author.name.contains(rightToLeftOverride))
    assertEquals(author.normalizedName, "michael ley")
  }

  test("a supplementary-plane letter folds through NFKC") {
    val styled = s"${mathematicalBoldCapitalM}ichael Ley"
    assertEquals(TextNormalizer.nameKey(styled), "michael ley")
    assertEquals(
      TextNormalizer.nameBlockKey(styled),
      TextNormalizer.nameBlockKey("Michael Ley")
    )
  }

  test("a supplementary-plane symbol folds to a word break") {
    val title = s"Graphs $grinningFace and Hypergraphs"
    assertEquals(TextNormalizer.titleKey(title), "graphs and hypergraphs")
  }

  test("fullwidth forms normalize to their ASCII equivalents") {
    assertEquals(TextNormalizer.titleKey("ＤＢＬＰ ２００９"), "dblp 2009")
  }

  test("a homonym ordinal written in fullwidth digits is not stripped") {
    // `stripHomonymOrdinal` runs before the fold, so it only ever sees the
    // raw digits. A fullwidth ordinal therefore survives into the key as a
    // token, splitting one person into two blocks. Minor, owner
    // hadoop-engineer; DBLP writes ASCII digits today.
    assertEquals(TextNormalizer.nameKey("Wei Wang 0014"), "wei wang")
    assertEquals(TextNormalizer.nameKey("Wei Wang ００１４"), "wei wang 0014")
  }

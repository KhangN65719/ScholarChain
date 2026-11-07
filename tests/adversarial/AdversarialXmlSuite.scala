package scholarchain.tests.adversarial

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import scholarchain.hadoop.pipeline.DblpParser
import scholarchain.hadoop.pipeline.RejectReason
import scholarchain.hadoop.pipeline.XmlRecordStream

/** Adversarial XML: malformed, truncated, deeply nested, entity-bearing and
  * encoding-hostile DBLP input.
  *
  * Independent of `XmlRecordStreamSuite` and `DblpParserSuite`, which cover the
  * well-formed split cases and the hand-written rejects. The cases here are the
  * inputs those suites do not try: a record tag hidden inside a comment or a
  * CDATA section, a literal `>` in an attribute value, an unterminated record
  * following a good one, an undeclared named entity of the kind the real DBLP
  * dump is full of, and bytes that are not UTF-8.
  *
  * Cases marked DEFECT pin behavior the reviewer and the owner should treat as
  * wrong. They are written as assertions on the observed behavior rather than
  * on the desired behavior, so the suite stays green and fails loudly when the
  * defect is repaired. Each one names the required behavior and the owning role
  * in a comment.
  */
class AdversarialXmlSuite extends FunSuite:
  private val run = AdversarialFixtures.run()

  private val commentedRecord =
    """<dblp><!-- <article key="ghost/1"><title>Ghost.</title></article> --></dblp>"""

  private val tightlyCommentedRecord =
    """<dblp><!--<article key="ghost/2"><title>Ghost.</title></article>--></dblp>"""

  private def readAll(xml: String): Vector[String] =
    XmlRecordStream.records(xml)

  /** One record read from raw bytes, so a non-UTF-8 byte can be injected. */
  private def latin1Record(): String =
    val prefix = """<article key="enc/1"><title>Caf"""
    val suffix = """.</title></article>"""
    val bytes =
      prefix.getBytes(UTF_8) ++ Array(0xe9.toByte) ++ suffix.getBytes(UTF_8)
    val reader = new XmlRecordStream(
      new ByteArrayInputStream(bytes),
      XmlRecordStream.publicationTags,
      0L,
      bytes.length.toLong
    )
    reader.iterator.map(_._2).toVector.head

  test("a record tag inside an XML comment is read as a live record") {
    // DEFECT, owner hadoop-engineer. `XmlRecordStream.seekRecordStart` claims
    // in its own comment that "any other byte, including `<` inside a comment
    // or CDATA, is skipped". It is not. The name scan after `<!--` yields
    // `!--`, which matches no tag, and scanning then resumes and finds the
    // `<article` inside the comment. The commented-out record is emitted and
    // the parser accepts it as a publication.
    // Required: a commented-out region contributes no record, or the reader
    // states that it does not track comments and the parser rejects it.
    val records = readAll(commentedRecord)
    assertEquals(records.size, 1)
    val parsed = DblpParser.parse(records.head, run)
    val publication = parsed.getOrElse(fail(s"parse failed: $parsed"))
    assertEquals(publication.work.sourceRecordId, "ghost/1")
    assertEquals(publication.work.title, "Ghost.")
  }

  test("a commented-out region contributes no record".fail) {
    // F8 `.fail` TWIN, for the skip-region half, which is carried forward
    // open per final-review.md. The body asserts the CORRECT behavior, so
    // munit's `.fail` keeps it green while the defect stands and turns it
    // red the instant the skip-region fix lands -- at which point delete the
    // `.fail` tag and the paired pins above.
    //
    // No twin for F8's other half: the false comment at
    // `XmlRecordStream.scala:87-90` is being corrected this cycle (the
    // review made it mandatory, calling it "the third false comment this
    // phase has produced"), and a comment is not observable from a test.
    assertEquals(readAll(commentedRecord).size, 0)
    assertEquals(readAll(tightlyCommentedRecord).size, 0)
    val cdata =
      """<dblp><![CDATA[ <article key="cdata/1"><title>T.</title></article> ]]></dblp>"""
    assertEquals(readAll(cdata).size, 0)
  }

  test("whether a commented-out record is ingested turns on one space") {
    // `<!--<article` is skipped, because the name scan runs straight through
    // the second `<` and produces `!--<article`, which matches no tag. The
    // spaced form is ingested and the tight form is not, so the defect above
    // is a function of the dump's comment style. Same owner, same repair.
    assertEquals(readAll(commentedRecord).size, 1)
    assertEquals(readAll(tightlyCommentedRecord).size, 0)
  }

  test("a record tag inside a CDATA section is read as a live record") {
    // DEFECT, owner hadoop-engineer. Same root cause as the comment case:
    // `![CDATA[` matches no tag and the scan continues into the escaped text.
    val xml =
      """<dblp><![CDATA[ <article key="cdata/1"><title>T.</title></article> ]]></dblp>"""
    val records = readAll(xml)
    assertEquals(records.size, 1)
    assert(records.head.startsWith("""<article key="cdata/1">"""))
  }

  test("a literal '>' in an attribute value is read as content") {
    // F9 CONVERTED, landing with `hadoop-engineer`'s reader/parser repair.
    //
    // One deliberate departure from the dispatch's framing, flagged to the
    // orchestrator: the brief said F9 "should become a reject assertion with
    // its counter". It should not. `<article key="a>1"/>` is **well-formed
    // XML** -- the spec forbids a literal `<` in an attribute value and
    // permits `>` -- so rejecting it would be a second defect. The repair is
    // quote-state tracking in `readRecordBody:124-133`, after which the
    // record reads correctly. F10 and F11 are genuine reject cases; this one
    // is a misparse.
    val records = readAll("""<dblp><article key="a>1"/></dblp>""")
    assertEquals(records, Vector("""<article key="a>1"/>"""))
  }

  test("the same attribute is survivable when the record has a closing tag") {
    // The closing `</article>` restores depth zero, so the identical hostile
    // attribute is harmless here. The defect above is reachable only through
    // the self-closing form, which bounds its severity.
    val xml =
      """<dblp><article key="a>1"><title>T.</title></article></dblp>"""
    val records = readAll(xml)
    assertEquals(records.size, 1)
    assert(records.head.endsWith("</article>"))
  }

  test("a complete record survives an unterminated record after it") {
    // The torn fragment must reach the parser's reject channel, not abort draining.
    val torn =
      """<dblp><article key="good/1"><title>Good.</title></article>""" +
        """<article key="bad/2"><title>Bad."""
    val records = readAll(torn)
    assertEquals(records.size, 2)
    assert(records.head.startsWith("""<article key="good/1">"""), records.head)
    assert(DblpParser.parse(records.head, run).isRight)
    assertEquals(
      DblpParser.parse(records(1), run).left.toOption.map(_._1),
      Some(RejectReason.MalformedXml)
    )
  }

  test("deeply nested inline markup in a title is flattened, not truncated") {
    // 200 is far beyond anything a DBLP title contains and stays safely
    // inside the default JVM stack. Recorded risk, owner hadoop-engineer:
    // `XmlFragment.text` walks the DOM with unbounded recursion, and a
    // StackOverflowError is an Error, which `XmlFragment.parse` does not
    // catch, so a hostile dump could still make this fatal rather than a
    // reject. No bound on element depth is configured on the parser.
    val depth = 200
    val opened = "<i>" * depth
    val closed = "</i>" * depth
    val xml =
      s"""<article key="deep/1"><title>$opened deep $closed</title></article>"""
    val parsed = DblpParser.parse(xml, run)
    val publication = parsed.getOrElse(fail(s"parse failed: $parsed"))
    assertEquals(publication.work.title, "deep")
    assertEquals(publication.work.normalizedTitle, "deep")
  }

  test("an undeclared named entity makes a real DBLP record unreadable") {
    // The dataset policy omits the DTD and the parser refuses a DOCTYPE, so
    // `&eacute;` resolves against nothing and the record is a reject. The
    // published DBLP dump uses these entities throughout, so this is the
    // reject class a full-snapshot run should expect to dominate. Recorded
    // for the owner and for whoever sizes the reject output; it is a
    // consequence of a deliberate security choice, not a coding defect.
    val xml =
      """<article key="e/1"><author>A B</author><title>Caf&eacute;.</title></article>"""
    val result = DblpParser.parse(xml, run)
    assertEquals(
      result.left.toOption.map(_._1),
      Some(RejectReason.MalformedXml)
    )
    assert(result.left.toOption.exists(_._2.nonEmpty))
  }

  test("a named DBLP entity resolves instead of rejecting the record".fail) {
    // R6 `.fail` TWIN. New in final-review.md, not among my thirteen -- I
    // had classified this as category 2, correct and deliberate, flagging
    // only reject-output sizing. The reviewer disagreed that nothing is
    // owed, and I accept the correction: the real DBLP dump uses named HTML
    // entities extensively, so on a full snapshot this rejects a large
    // fraction of records, makes the accepted/rejected ratio meaningless and
    // starves the graph.
    //
    // The security posture at `XmlFragment.factory():27-30` is right and is
    // NOT what this twin asks to change. The remedy recorded in
    // decisions.md section 4 is to pre-resolve DBLP's fixed, published
    // entity set before parsing, with a counter -- never to enable the DTD.
    // Owner `hadoop-engineer`, with `dataset-engineer` supplying the list.
    // Phase 5 blocker and a condition on the first full-snapshot run.
    val xml =
      """<article key="e/1"><author>A B</author><title>Caf&eacute;.</title></article>"""
    val parsed = DblpParser.parse(xml, run)
    val publication = parsed.getOrElse(fail(s"parse failed: $parsed"))
    assertEquals(publication.work.title, "Café.")
    assertEquals(publication.work.normalizedTitle, "cafe")
  }

  test("the five predefined entities still decode") {
    val xml =
      """<article key="e/2"><author>A B</author><title>A &amp; B &lt;C&gt;.</title></article>"""
    val parsed = DblpParser.parse(xml, run)
    val publication = parsed.getOrElse(fail(s"parse failed: $parsed"))
    assertEquals(publication.work.title, "A & B <C>.")
  }

  test("a non-UTF-8 byte is not accepted as content") {
    val payload = latin1Record()
    assertEquals(
      DblpParser.parse(payload, run).left.toOption.map(_._1),
      Some(RejectReason.MalformedXml)
    )
  }

  test("the reader refuses malformed construction rather than scanning") {
    val bytes = "<article key=\"a/1\"/>".getBytes(UTF_8)
    def stream = new ByteArrayInputStream(bytes)
    intercept[IllegalArgumentException] {
      new XmlRecordStream(stream, Set.empty, 0L, 10L)
    }
    intercept[IllegalArgumentException] {
      new XmlRecordStream(stream, Set("not a name"), 0L, 10L)
    }
    intercept[IllegalArgumentException] {
      new XmlRecordStream(stream, Set("article"), -1L, 10L)
    }
    intercept[IllegalArgumentException] {
      new XmlRecordStream(stream, Set("article"), 10L, 5L)
    }
  }

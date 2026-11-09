package scholarchain.tests.adversarial

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import scholarchain.hadoop.pipeline.DblpParser
import scholarchain.hadoop.pipeline.RejectReason
import scholarchain.hadoop.pipeline.XmlRecordStream

/** Phase 11: DBLP XML cut off at every possible point, in text, in a tag, in an
  * attribute, in an entity and in the middle of a multibyte character. A torn
  * record must reach the parser's counted reject channel. It must never throw,
  * never abort the stream and never become a publication.
  */
class Phase11XmlTruncationSuite extends FunSuite:
  private val run = AdversarialFixtures.run()

  private val full =
    """<article key="journals/x/Mueller09"><author>J&ouml;rg M&uuml;ller</author>""" +
      """<author>Ann Lee</author><title>Caf&eacute; &amp; Cr&egrave;me.</title>""" +
      """<year>2009</year><journal>J. X</journal></article>"""

  /** Records read from the first `n` bytes of `bytes`. */
  private def recordsOfBytes(bytes: Array[Byte]): Vector[String] =
    val reader = new XmlRecordStream(
      new ByteArrayInputStream(bytes),
      XmlRecordStream.publicationTags,
      0L,
      bytes.length.toLong
    )
    reader.iterator.map(_._2).toVector

  private def rejectOf(record: String) =
    DblpParser.parse(record, run).left.toOption.map(_._1)

  test(
    "the full record parses: the truncation checks start from a good record"
  ) {
    val parsed = DblpParser.parse(full, run)
    val publication = parsed.getOrElse(fail(s"fixture rejected: $parsed"))
    assertEquals(publication.work.title, "Café & Crème.")
    assertEquals(publication.work.authors.map(_.name).head, "Jörg Müller")
    assertEquals(XmlRecordStream.records(full), Vector(full))
  }

  test(
    "every character prefix is rejected or skipped, never parsed or thrown"
  ) {
    var emitted = 0
    (0 until full.length).foreach { n =>
      val prefix = full.take(n)
      val records =
        try XmlRecordStream.records(prefix)
        catch
          case t: Throwable =>
            fail(
              s"reader threw ${t.getClass.getName} at $n: ${prefix.takeRight(30)}"
            )
      records.foreach { record =>
        emitted += 1
        val result =
          try DblpParser.parse(record, run)
          catch
            case t: Throwable =>
              fail(
                s"parser threw ${t.getClass.getName} at $n: ${record.takeRight(30)}"
              )
        assertEquals(
          result.left.toOption.map(_._1),
          Some(RejectReason.MalformedXml),
          s"a torn record at $n was accepted: ${prefix.takeRight(40)}"
        )
      }
    }
    assert(emitted > 0, "no torn fragment ever reached the parser")
  }

  test("every byte prefix, multibyte cuts included, is safe") {
    // literal multibyte characters, so some byte cuts fall inside a character
    val literal = full
      .replace("&ouml;", "ö")
      .replace("&uuml;", "ü")
      .replace("&eacute;", "é")
      .replace("&egrave;", "è")
    assert(DblpParser.parse(literal, run).isRight, "the literal fixture parses")
    val bytes = literal.getBytes(UTF_8)
    assert(bytes.length > literal.length)
    var emitted = 0
    (0 until bytes.length).foreach { n =>
      val records =
        try recordsOfBytes(bytes.take(n))
        catch
          case t: Throwable =>
            fail(s"reader threw ${t.getClass.getName} at byte $n")
      records.foreach { record =>
        emitted += 1
        val result =
          try DblpParser.parse(record, run)
          catch
            case t: Throwable =>
              fail(s"parser threw ${t.getClass.getName} at byte $n")
        assert(result.isLeft, s"a record cut at byte $n parsed")
      }
    }
    assert(emitted > 0, "no torn byte prefix ever reached the parser")
  }

  test(
    "the cut points under test include mid-tag, mid-attribute and mid-entity"
  ) {
    def endsInside(marker: String): Vector[String] =
      val at = full.indexOf(marker)
      assert(at >= 0, marker)
      (1 until marker.length).toVector.map(k => full.take(at + k))
    val midEntity = endsInside("&eacute;")
    val midClose = endsInside("</title>")
    val midAttr = endsInside("key=\"journals")
    var emitted = 0
    (midEntity ++ midClose ++ midAttr).foreach { prefix =>
      XmlRecordStream.records(prefix).foreach { record =>
        emitted += 1
        assertEquals(
          rejectOf(record),
          Some(RejectReason.MalformedXml),
          prefix.takeRight(30)
        )
      }
    }
    assert(
      emitted > 0,
      "no mid-tag, mid-attribute or mid-entity cut was emitted"
    )
  }

  test("a good record before a record torn at any point is still read") {
    val good = full
    val second =
      """<article key="b/2"><author>Bo Chen</author><title>Caf&eacute; again.</title><year>2010</year></article>"""
    (1 until second.length).foreach { n =>
      val text = "<dblp>" + good + second.take(n)
      val records = XmlRecordStream.records(text)
      assert(records.nonEmpty, s"no record at cut $n")
      assertEquals(records.head, good, s"the good record was lost at cut $n")
      assert(DblpParser.parse(records.head, run).isRight, n.toString)
      records.drop(1).foreach { torn =>
        assertEquals(rejectOf(torn), Some(RejectReason.MalformedXml), s"cut $n")
      }
    }
  }

  test("a complete record with a broken entity is a counted reject") {
    val broken = Vector(
      "AT&T Labs",
      "Caf&eacute Latte",
      "Caf&#233 Latte",
      "x &#xZZ; y",
      "x &#99999999999; y",
      "x &#0; y",
      "x &#55357; y", // a lone surrogate
      "x &; y",
      "x &amp y"
    )
    broken.foreach { title =>
      val xml =
        s"""<article key="e/9"><author>A B</author><title>$title</title></article>"""
      val result =
        try DblpParser.parse(xml, run)
        catch
          case t: Throwable =>
            fail(s"parser threw ${t.getClass.getName}: $title")
      assertEquals(
        result.left.toOption.map(_._1),
        Some(RejectReason.MalformedXml),
        s"accepted: $title"
      )
      assert(result.left.toOption.exists(_._2.nonEmpty), title)
    }
  }

  test("a record cut inside the closing tag never reads as a publication") {
    val cut = full.dropRight("</article>".length) + "</arti"
    val cutRecords = XmlRecordStream.records(cut)
    assert(cutRecords.nonEmpty, "the record cut inside </article> was dropped")
    cutRecords.foreach { record =>
      assertEquals(rejectOf(record), Some(RejectReason.MalformedXml))
    }
    val noClose = full.dropRight("</article>".length)
    val noCloseRecords = XmlRecordStream.records(noClose)
    assert(
      noCloseRecords.nonEmpty,
      "the record with no closing tag was dropped"
    )
    noCloseRecords.foreach { record =>
      assertEquals(rejectOf(record), Some(RejectReason.MalformedXml))
    }
  }

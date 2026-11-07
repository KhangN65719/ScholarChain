package scholarchain.hadoop

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import scholarchain.hadoop.pipeline.XmlRecordStream

/** Record-boundary tests.
  *
  * These are the tests behind the gate item "DBLP parser handles record
  * boundaries correctly". The split cases are the ones that matter: a reader
  * must claim a record whose opening tag lies in its own split and must ignore
  * one that began in an earlier split, or the dump is either duplicated or
  * silently truncated.
  */
class XmlRecordStreamSuite extends FunSuite:
  private val document =
    """<?xml version="1.0" encoding="UTF-8"?>
      |<dblp>
      |  <article key="a/1"><author>Ann</author><title>First.</title></article>
      |  <inproceedings key="c/2"><author>Bo</author><title>Second.</title></inproceedings>
      |  <www key="homepages/a/Ann"><author>Ann</author></www>
      |</dblp>""".stripMargin

  private def readSplit(
      xml: String,
      start: Long,
      end: Long
  ): Vector[String] =
    val bytes = xml.getBytes(UTF_8)
    val input = new ByteArrayInputStream(bytes)
    input.skip(start)
    new XmlRecordStream(input, XmlRecordStream.allTags, start, end).iterator
      .map(_._2)
      .toVector

  test("reads every publication record in a whole document") {
    val records = XmlRecordStream.records(document)
    assertEquals(records.size, 2)
    assert(records.head.startsWith("<article key=\"a/1\">"))
    assert(records.head.endsWith("</article>"))
    assert(records(1).startsWith("<inproceedings key=\"c/2\">"))
  }

  test("identity tags are read separately from publication tags") {
    assertEquals(
      XmlRecordStream.records(document, XmlRecordStream.identityTags).size,
      1
    )
    assertEquals(XmlRecordStream.records(document).size, 2)
    assertEquals(
      XmlRecordStream.records(document, XmlRecordStream.allTags).size,
      3
    )
  }

  test("a record is never split across two readers") {
    val bytes = document.getBytes(UTF_8).length.toLong
    // Every split point is exercised, including those landing inside a record.
    (0L until bytes).foreach { boundary =>
      val first = readSplit(document, 0L, boundary)
      val second = readSplit(document, boundary, bytes)
      val combined = first ++ second
      assertEquals(
        combined.size,
        3,
        s"boundary $boundary yielded ${combined.size} records"
      )
      assertEquals(
        combined.toSet,
        XmlRecordStream.records(document, XmlRecordStream.allTags).toSet,
        s"boundary $boundary changed the record set"
      )
      // Each record appears exactly once across the two readers.
      assertEquals(combined.distinct.size, combined.size)
    }
  }

  test("a reader past the last start tag claims nothing") {
    val bytes = document.getBytes(UTF_8).length.toLong
    assertEquals(readSplit(document, bytes - 2, bytes), Vector.empty)
  }

  test("a self-closing child does not end the record early") {
    val xml =
      """<dblp><article key="a/1"><ee/><title>T.</title></article></dblp>"""
    val records = XmlRecordStream.records(xml)
    assertEquals(records.size, 1)
    assert(records.head.contains("<title>T.</title>"))
    assert(records.head.endsWith("</article>"))
  }

  test("a self-closing record element is a complete record") {
    val records =
      XmlRecordStream.records("""<dblp><article key="a/1"/></dblp>""")
    assertEquals(records, Vector("""<article key="a/1"/>"""))
  }

  test("a nested same-named element does not end the record early") {
    val xml =
      """<dblp><article key="a/1"><note><article>inner</article></note></article></dblp>"""
    val records = XmlRecordStream.records(xml)
    assertEquals(records.size, 1)
    assert(records.head.endsWith("</note></article>"))
  }

  test("multi-byte content survives the byte-level scan") {
    val xml =
      """<dblp><article key="a/1"><author>Émile Zola 日本</author></article></dblp>"""
    val records = XmlRecordStream.records(xml)
    assertEquals(records.size, 1)
    assert(records.head.contains("Émile Zola 日本"))
    // Offsets are byte offsets, so a multi-byte record must still round-trip.
    assertEquals(
      records.head.getBytes(UTF_8).length,
      """<article key="a/1"><author>Émile Zola 日本</author></article>"""
        .getBytes(UTF_8)
        .length
    )
  }

  test("an unterminated element is reported rather than silently truncated") {
    val records =
      XmlRecordStream.records("""<dblp><article key="a/1"><title>T.</title>""")
    assertEquals(records.size, 1)
    assert(
      scholarchain.hadoop.pipeline.DblpParser
        .parse(records.head, TestRun.context())
        .isLeft
    )
  }

  test("an unknown tag is skipped without consuming the next record") {
    val xml =
      """<dblp><unsupported key="x"/><article key="a/1"><title>T.</title></article></dblp>"""
    assertEquals(XmlRecordStream.records(xml).size, 1)
  }

  test("rootTag names the element without a full parse") {
    assertEquals(
      XmlRecordStream.rootTag("""<inproceedings key="c/2">x</inproceedings>"""),
      Some("inproceedings")
    )
    assertEquals(XmlRecordStream.rootTag("not xml"), None)
  }

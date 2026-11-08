package scholarchain.hadoop

import munit.FunSuite
import scholarchain.hadoop.pipeline.{
  DblpEntities,
  DblpParser,
  RejectReason,
  XmlFragment
}

/** R6: DBLP's named HTML entities resolve without a DTD, and everything else
  * stays rejected.
  */
class DblpEntitySuite extends FunSuite:
  private val run = TestRun.context()

  private def article(author: String, title: String = "T."): String =
    s"""<article key="a/1"><author>$author</author><title>$title</title></article>"""

  test(
    "a named entity in an author resolves and keeps the original character"
  ) {
    val publication =
      DblpParser
        .parse(article("J&ouml;rg M&uuml;ller"), run)
        .getOrElse(fail("parse failed"))
    val author = publication.work.authors.head
    assertEquals(author.name, "Jörg Müller")
    assertEquals(author.normalizedName, "jorg muller")
  }

  test("named entities in a title resolve") {
    val publication = DblpParser
      .parse(article("A B", "Caf&eacute; &amp; Bar&#233;"), run)
      .getOrElse(fail("parse failed"))
    assertEquals(publication.work.title, "Café & Baré")
  }

  test("a named entity inside an attribute value resolves") {
    val element = XmlFragment
      .parse("""<author orcid="x&eacute;">A</author>""")
      .getOrElse(fail("parse failed"))
    assertEquals(element.getAttribute("orcid"), "xé")
  }

  test("an undeclared entity is a counted MalformedXml reject") {
    val result = DblpParser.parse(article("A &bogus; B"), run)
    assertEquals(
      result.left.toOption.map(_._1),
      Some(RejectReason.MalformedXml)
    )
  }

  test("a DOCTYPE that declares a DBLP entity name is still refused") {
    val xml =
      """<!DOCTYPE article [<!ENTITY uuml "evil">]>
        |<article key="a/1"><author>A</author><title>T.</title></article>""".stripMargin
    val result = DblpParser.parse(xml, run)
    assertEquals(
      result.left.toOption.map(_._1),
      Some(RejectReason.MalformedXml)
    )
  }

  test("an external entity declaration is still refused") {
    val xml =
      """<!DOCTYPE article [<!ENTITY x SYSTEM "file:///etc/passwd">]>
        |<article key="a/1"><author>&x;</author><title>T.</title></article>""".stripMargin
    assertEquals(
      DblpParser.parse(xml, run).left.toOption.map(_._1),
      Some(RejectReason.MalformedXml)
    )
  }

  test(
    "predefined entities are untouched and an escaped ampersand stays literal"
  ) {
    val publication = DblpParser
      .parse(article("A B", "x &lt; y &amp;uuml; z"), run)
      .getOrElse(fail("parse failed"))
    assertEquals(publication.work.title, "x < y &uuml; z")
  }

  test("entity text inside CDATA is literal") {
    assertEquals(
      DblpEntities.resolve("<a><![CDATA[&uuml;]]>&uuml;</a>"),
      "<a><![CDATA[&uuml;]]>&#252;</a>"
    )
  }

  test("the table is the HTML 4 set without XML predefined names") {
    assertEquals(DblpEntities.table.size, 248)
    assertEquals(DblpEntities.table("uuml"), 0xfc)
    assertEquals(DblpEntities.table("eacute"), 0xe9)
    assertEquals(DblpEntities.table("szlig"), 0xdf)
    assertEquals(DblpEntities.table("yuml"), 0xff)
    assertEquals(DblpEntities.table("Omega"), 937)
    assertEquals(DblpEntities.table("omega"), 969)
    assertEquals(DblpEntities.table("euro"), 8364)
    assert(
      Set("amp", "lt", "gt", "quot", "apos").forall(n =>
        !DblpEntities.table.contains(n)
      )
    )
    assertEquals(DblpEntities.table.values.toSet.size, DblpEntities.table.size)
    assert(DblpEntities.table.values.forall(Character.isValidCodePoint))
  }

  test("stored names keep sharp s and slashed o as written") {
    val publication = DblpParser
      .parse(article("Strau&szlig; S&oslash;ren"), run)
      .getOrElse(fail("parse failed"))
    assertEquals(publication.work.authors.head.name, "Strauß Søren")
  }

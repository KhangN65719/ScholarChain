package scholarchain.tests.adversarial

import java.text.Normalizer
import munit.FunSuite
import scholarchain.core.MatchOutcome
import scholarchain.er.matching.*
import scholarchain.tests.adversarial.ErFixtures.*

/** Normalization edge cases, probed through the public key builders and then
  * through the matcher.
  */
class AdversarialErNormalizationSuite extends FunSuite:

  test("every DOI prefix and case form folds to one key") {
    val forms = Vector(
      "10.1000/ABC.1",
      "https://doi.org/10.1000/ABC.1",
      "http://dx.doi.org/10.1000/abc.1",
      "doi.org/10.1000/Abc.1",
      "DOI:10.1000/abc.1",
      "  doi:10.1000/abc.1  ",
      "info:doi/10.1000/ABC.1"
    )
    assertEquals(forms.map(Identifiers.doi).toSet, Set(Some("10.1000/abc.1")))
  }

  test("malformed DOIs normalize to nothing instead of a bogus key") {
    Vector(
      "",
      "   ",
      "doi:",
      "10.1/short",
      "10.1000/",
      "10.1000/has space",
      "11.1000/abc",
      "https://example.org/10.1000/abc"
    ).foreach(v => assertEquals(Identifiers.doi(v), None, v))
    assertEquals(Identifiers.doi(null), None)
  }

  test("arXiv forms agree and version suffixes are dropped") {
    val forms = Vector(
      "1706.03762",
      "arXiv:1706.03762v5",
      "ARXIV:1706.03762",
      "https://arxiv.org/abs/1706.03762v1",
      "https://arxiv.org/pdf/1706.03762v7.pdf",
      "10.48550/arXiv.1706.03762"
    )
    assertEquals(forms.map(Identifiers.arxiv).toSet, Set(Some("1706.03762")))
    assertEquals(Identifiers.arxiv("hep-th/9901001v2"), Some("hep-th/9901001"))
    assertEquals(Identifiers.arxiv("1706.037"), None)
    assertEquals(Identifiers.arxiv("17066.03762"), None)
    assertEquals(Identifiers.arxiv(""), None)
  }

  test("ORCID check digit: transposition, truncation and format are refused") {
    assertEquals(Identifiers.orcid(orcidA), Some(orcidA))
    assertEquals(Identifiers.orcid(orcidX.toLowerCase), Some(orcidX))
    assertEquals(
      Identifiers.orcid(s"https://orcid.org/$orcidB"),
      Some(orcidB)
    )
    Vector(
      "0000-0002-1825-0079",
      "0000-0002-1825-009",
      "0000000218250097",
      "0000-0002-1825-0097-",
      "",
      "0000-0002-1694-2330"
    ).foreach(v => assertEquals(Identifiers.orcid(v), None, v))
  }

  test("OpenAlex and repository identifiers do not cross kinds") {
    assertEquals(
      Identifiers.openAlex('A', "https://openalex.org/a5023"),
      Some("A5023")
    )
    assertEquals(Identifiers.openAlex('A', "W5023"), None)
    assertEquals(
      Identifiers.repositoryUrl("https://GitHub.com/Org/Repo.git/tree/main"),
      Some("github.com/org/repo")
    )
    assertEquals(Identifiers.repositoryUrl("https://github.com/org"), None)
  }

  test("NFC and NFD spellings of a name fold identically") {
    val nfc = Normalizer.normalize("José Müller", Normalizer.Form.NFC)
    val nfd = Normalizer.normalize("José Müller", Normalizer.Form.NFD)
    assertNotEquals(nfc, nfd)
    assertEquals(Text.foldName(nfc), Text.foldName(nfd))
    assertEquals(Names.parse(nfc), Names.parse(nfd))
  }

  test("fullwidth, ligature, apostrophe and stroke variants fold together") {
    assertEquals(Text.foldName("Ｊｏｈｎ Ｓｍｉｔｈ"), "john smith")
    assertEquals(Text.foldName("O'Brien"), Text.foldName("O’Brien"))
    assertEquals(Text.foldName("O'Brien"), Text.foldName("OBrien"))
    assertEquals(Text.foldName("Łukasz Søren"), "lukasz soren")
    assertEquals(Text.fold("ﬁne"), "fine")
  }

  test(
    "non-Latin names stay distinct rather than collapsing to one empty key"
  ) {
    assertEquals(Text.foldName("王伟"), "王伟")
    assertNotEquals(Text.foldName("王伟"), Text.foldName("李娜"))
    assertNotEquals(Names.parse("王 伟").family, "")
  }

  test("name order, initials and punctuation parse to the same parts") {
    assertEquals(Names.parse("Smith, John"), Names.parse("John Smith"))
    assertEquals(Names.parse("J.R.R. Tolkien"), Names.parse("JRR Tolkien"))
    assertEquals(Names.parse("J. R. R. Tolkien"), Names.parse("JRR Tolkien"))
    assertEquals(Names.parse("John Smith Jr.").family, "smith")
    assertEquals(Names.parse("John Smith 0001").family, "smith")
    assertEquals(Names.parse("Jean-Pierre Dupont").forenames.size, 2)
  }

  test("a mononym, an empty name and null parse without throwing") {
    assertEquals(Names.parse("Madonna").family, "madonna")
    assertEquals(Names.parse("Madonna").forenames, Vector.empty)
    assert(Names.parse("").isEmpty)
    assert(Names.parse("   ").isEmpty)
    assert(Names.parse(null).isEmpty)
    assert(Names.parse(",").isEmpty || Names.parse(",").family.isEmpty)
  }

  test("title markup, entities and LaTeX do not change the title key") {
    val plain = Text.titleKey("Deep Learning & Vision")
    assertEquals(Text.titleKey("<i>Deep</i> Learning &amp; Vision"), plain)
    assertEquals(Text.titleKey("Deep \\emph{Learning} &amp; Vision"), plain)
    assertEquals(Text.titleKey("DEEP   learning ... & vision!"), plain)
    assertEquals(Text.titleKey("<sub><sup></sup></sub>"), "")
  }

  test("two works differing only in title markup match by rule") {
    val a = work(
      "w1",
      "<i>Graph</i> Resolution &amp; Identity Theory",
      Vector("Ada Lovelace")
    )
    val b =
      work("w2", "Graph Resolution & Identity Theory", Vector("Lovelace, Ada"))
    val r = decide(a, b)
    assertEquals(r.decision.decision, MatchOutcome.Accepted)
    assertEquals(r.path, MatchPath.DeterministicBibliographic)
  }

  test("empty and null-ish fields decide without throwing or accepting") {
    val a = person("a", "", title = " ", year = None, venue = None)
    val b = person("b", "", title = " ", year = None, venue = None)
    assertNotEquals(outcome(a, b), MatchOutcome.Accepted)
    val w1 = work("w1", "", year = None, venue = None)
    val w2 = work("w2", "", year = None, venue = None)
    assertNotEquals(outcome(w1, w2), MatchOutcome.Accepted)
  }

  test("a SideRecord refuses blank identity fields") {
    intercept[IllegalArgumentException](person(" ", "Ada Lovelace"))
  }

  test("huge inputs fold and decide without failure") {
    val huge = ("alpha beta gamma " * 20000)
    assert(Text.fold(huge).length > 100000)
    val a = work("w1", huge, Vector.fill(2000)("Ada Lovelace"))
    val b = work("w2", huge, Vector.fill(2000)("Ada Lovelace"))
    val r = decide(a, b)
    assert(r.score >= 0.0 && r.score <= 1.0)
    val manyNames = person("p1", "A " * 5000 + "Smith")
    val other = person("p2", "A " * 5000 + "Smith")
    assertNotEquals(outcome(manyNames, other), MatchOutcome.Accepted)
  }

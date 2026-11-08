package scholarchain.er

import scholarchain.er.matching.*

class NormalizationSuite extends munit.FunSuite:

  test("fold removes diacritics and case but keeps non-Latin letters") {
    assertEquals(Text.fold("Müller"), "muller")
    assertEquals(Text.fold("Łukasz Ørsted Straße"), "lukasz orsted strasse")
    assertEquals(Text.fold("José  GARCÍA-López"), "jose garcia lopez")
    assertEquals(Text.fold("王伟"), "王伟")
    assertEquals(Text.fold("Ｆｕｌｌｗｉｄｔｈ"), "fullwidth")
  }

  test("composed and decomposed spellings fold to one key") {
    assertEquals(Text.fold("Zoë"), Text.fold("Zoë"))
  }

  test("names fold apostrophes away instead of splitting on them") {
    assertEquals(Names.parse("Sean O'Brien"), Names.parse("Sean OBrien"))
    assertEquals(Names.parse("Sean O’Brien").family, "obrien")
  }

  test("title markup, entities and latex are stripped from keys only") {
    val raw = "<i>Deep</i> Learning &amp; H<sub>2</sub>O \\textit{Models}"
    assertEquals(Text.titleKey(raw), "deep learning h2o models")
    assertEquals(Text.titleKey("A &#x26; B"), Text.titleKey("A & B"))
  }

  test("DOI prefixes normalize to one lowercase key") {
    val forms = Vector(
      "https://doi.org/10.1000/ABC.123",
      "http://dx.doi.org/10.1000/ABC.123",
      "doi:10.1000/abc.123",
      "DOI:10.1000/ABC.123",
      "10.1000/abc.123"
    )
    forms.foreach(form =>
      assertEquals(Identifiers.doi(form), Some("10.1000/abc.123"), form)
    )
    assertEquals(Identifiers.doi("not a doi"), None)
  }

  test("arXiv forms drop version suffixes and accept the arXiv DOI") {
    assertEquals(Identifiers.arxiv("arXiv:2101.00001v3"), Some("2101.00001"))
    assertEquals(
      Identifiers.arxiv("https://arxiv.org/abs/2101.00001"),
      Some("2101.00001")
    )
    assertEquals(
      Identifiers.arxiv("10.48550/arXiv.2101.00001"),
      Some("2101.00001")
    )
    assertEquals(Identifiers.arxiv("hep-th/9901001v2"), Some("hep-th/9901001"))
  }

  test("ORCID requires a valid check digit") {
    assertEquals(
      Identifiers.orcid(s"https://orcid.org/${Fixtures.validOrcidA}"),
      Some(Fixtures.validOrcidA)
    )
    assertEquals(
      Identifiers.orcid(Fixtures.validOrcidB),
      Some(Fixtures.validOrcidB)
    )
    assertEquals(Identifiers.orcid("0000-0002-1825-0098"), None)
    assertEquals(Identifiers.orcid("0000-0002-1825"), None)
  }

  test("repository URLs reduce to host, owner and repository") {
    val expected = Some("github.com/owner/repo")
    assertEquals(
      Identifiers.repositoryUrl("https://github.com/Owner/Repo.git"),
      expected
    )
    assertEquals(
      Identifiers.repositoryUrl("http://www.github.com/owner/repo/tree/main"),
      expected
    )
    assertEquals(Identifiers.repositoryUrl("https://example.com/a/b"), None)
  }

  test("name order, particles, ordinals and suffixes parse consistently") {
    val direct = Names.parse("Jean-Pierre Müller")
    assertEquals(Names.parse("Müller, Jean-Pierre"), direct)
    assertEquals(direct.family, "muller")
    assertEquals(direct.forenames, Vector("jean", "pierre"))
    assertEquals(Names.parse("Wei Wang 0014"), Names.parse("Wei Wang"))
    assertEquals(Names.parse("Ludwig van der Berg").family, "van der berg")
    assertEquals(Names.parse("Robert Smith Jr.").family, "smith")
    assertEquals(Names.parse("J. P. Smith").forenames, Vector("j", "p"))
    assertEquals(Names.parse("JP Smith").forenames, Vector("j", "p"))
    assertEquals(Names.parse("Madonna"), ParsedName("madonna", Vector.empty))
    assert(Names.parse("  ").isEmpty)
  }

  test("forename comparison grades full, initial, near and conflicting") {
    def forename(a: String, b: String): Option[Double] =
      NameComparison.compare(Names.parse(a), Names.parse(b)).forename
    assertEquals(forename("John Smith", "John Smith"), Some(1.0))
    assertEquals(forename("John Smith", "J. Smith"), Some(0.75))
    assertEquals(forename("John Smith", "Jon Smith"), Some(0.9))
    assertEquals(forename("John Smith", "Mary Smith"), Some(0.0))
    assertEquals(forename("John Q. Smith", "John Smith"), Some(1.0))
    assertEquals(forename("Smith", "John Smith"), None)
  }

  test("a swapped given and family order is recognized and flagged") {
    val comparison =
      NameComparison.compare(Names.parse("Wei Wang"), Names.parse("Wang Wei"))
    assert(comparison.swapped)
    assertEquals(comparison.family, 1.0)
    val reverse =
      NameComparison.compare(Names.parse("Wang Wei"), Names.parse("Wei Wang"))
    assertEquals(reverse, comparison)
    assert(
      !NameComparison
        .compare(Names.parse("Wei Wang"), Names.parse("Wei Wang"))
        .swapped
    )
  }

  test("similarity functions are symmetric and bounded") {
    val pairs = Vector(
      ("martha", "marhta"),
      ("dixon", "dicksonx"),
      ("smith", "smyth"),
      ("", "abc")
    )
    pairs.foreach { (a, b) =>
      assertEquals(Text.jaroWinkler(a, b), Text.jaroWinkler(b, a))
      assertEquals(Text.editSimilarity(a, b), Text.editSimilarity(b, a))
      assert(Text.jaroWinkler(a, b) >= 0.0 && Text.jaroWinkler(a, b) <= 1.0)
    }
    assertEquals(Text.editSimilarity("same", "same"), 1.0)
  }

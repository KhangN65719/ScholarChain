package scholarchain.hadoop

import munit.FunSuite
import scholarchain.hadoop.pipeline.{
  ExactIdentifiers,
  IdNamespace,
  PartitionKey,
  StreamKey,
  TextNormalizer
}

/** Key-function tests: identifier normalization, name and title folding, and
  * partition assignment. These are the functions every join in the pipeline
  * depends on, so each one is pinned against hand-checkable values.
  */
class IdentifierAndKeySuite extends FunSuite:
  test("DOIs normalize across resolver prefixes and case") {
    val expected = Some("10.14778/1687553.1687577")
    assertEquals(ExactIdentifiers.doi("10.14778/1687553.1687577"), expected)
    assertEquals(
      ExactIdentifiers.doi("https://doi.org/10.14778/1687553.1687577"),
      expected
    )
    assertEquals(
      ExactIdentifiers.doi("http://dx.doi.org/10.14778/1687553.1687577"),
      expected
    )
    assertEquals(
      ExactIdentifiers.doi("doi:10.14778/1687553.1687577"),
      expected
    )
    // The registry defines DOIs as case-insensitive, so the two sample
    // spellings of the JCDL DOI must collapse to one join key.
    assertEquals(
      ExactIdentifiers.doi("https://doi.org/10.1109/JCDL.2017.7991609"),
      ExactIdentifiers.doi("https://doi.org/10.1109/jcdl.2017.7991609")
    )
    assertEquals(ExactIdentifiers.doi("not-a-doi"), None)
    assertEquals(ExactIdentifiers.doi("10.123/short-prefix"), None)
  }

  test("arXiv IDs normalize and drop version suffixes") {
    assertEquals(
      ExactIdentifiers.arxiv("arXiv:2101.01234v3"),
      Some("2101.01234")
    )
    assertEquals(
      ExactIdentifiers.arxiv("https://arxiv.org/abs/2101.01234"),
      Some("2101.01234")
    )
    assertEquals(
      ExactIdentifiers.arxiv("10.48550/arXiv.2101.01234"),
      Some("2101.01234")
    )
    assertEquals(
      ExactIdentifiers.arxiv("cs.DL/0701001v2"),
      Some("cs.dl/0701001")
    )
    assertEquals(ExactIdentifiers.arxiv("10.14778/1687553.1687577"), None)
  }

  test("ORCID iDs are accepted only with a valid check digit") {
    // All four sample iDs, with check digits verified by ISO 7064 MOD 11-2.
    Vector(
      "0000-0001-7580-4351",
      "0000-0001-5379-5191",
      "0000-0001-7441-129X",
      "0000-0003-0523-8547"
    ).foreach(iD =>
      assertEquals(
        ExactIdentifiers.orcid(s"https://orcid.org/$iD"),
        Some(iD),
        s"$iD should be accepted"
      )
    )
    // A transposition breaks the check digit and must be refused, because a
    // bad iD would otherwise mint a researcher identity.
    assertEquals(ExactIdentifiers.orcid("0000-0001-7508-4351"), None)
    assertEquals(ExactIdentifiers.orcid("0000-0001-7580-4352"), None)
    assertEquals(ExactIdentifiers.orcid("0000-0001-7580"), None)
  }

  test("OpenAlex identifiers are namespaced by entity prefix") {
    assertEquals(
      ExactIdentifiers.openAlexOf('W', "https://openalex.org/W2147107544"),
      Some("W2147107544")
    )
    assertEquals(
      ExactIdentifiers.openAlexOf('A', "https://openalex.org/A5033019634"),
      Some("A5033019634")
    )
    // A work ID must not satisfy an author lookup.
    assertEquals(
      ExactIdentifiers.openAlexOf('A', "https://openalex.org/W2147107544"),
      None
    )
  }

  test("ROR and ISSN normalize to their registry forms") {
    assertEquals(
      ExactIdentifiers.ror("https://ror.org/02778hg05"),
      Some("02778hg05")
    )
    assertEquals(ExactIdentifiers.ror("https://ror.org/not-a-ror"), None)
    assertEquals(ExactIdentifiers.issn("2150-8097"), Some("2150-8097"))
    assertEquals(ExactIdentifiers.issn("21508097"), None)
  }

  test("a DBLP electronic edition yields a DOI and never a DBLP crossref key") {
    assertEquals(
      ExactIdentifiers.fromElectronicEdition(
        "https://doi.org/10.14778/1687553.1687577"
      ),
      Vector(IdNamespace.Doi -> "10.14778/1687553.1687577")
    )
    // A DBLP `crossref` value is a parent record key, not a DOI.
    assertEquals(
      ExactIdentifiers.fromElectronicEdition("conf/jcdl/2017"),
      Vector.empty
    )
  }

  test("join keys round-trip") {
    val key = ExactIdentifiers.joinKey(IdNamespace.Doi, "10.1/abc")
    assertEquals(key, "doi|10.1/abc")
    assertEquals(
      ExactIdentifiers.parseJoinKey(key),
      Some(IdNamespace.Doi -> "10.1/abc")
    )
    assertEquals(ExactIdentifiers.parseJoinKey("nosuch|x"), None)
  }

  test("name folding is Unicode- and locale-stable") {
    assertEquals(TextNormalizer.nameKey("Émile Zola"), "emile zola")
    // Composed and decomposed spellings must produce one key.
    assertEquals(
      TextNormalizer.nameKey("Universität Trier"),
      TextNormalizer.nameKey("Universität Trier")
    )
    assertEquals(TextNormalizer.nameKey("  Michael   Ley  "), "michael ley")
    // The Turkish dotless-i trap: folding must not depend on the JVM locale.
    assertEquals(TextNormalizer.nameKey("ILKAY"), "ilkay")
  }

  test("a DBLP homonym ordinal is not part of the name") {
    assertEquals(TextNormalizer.nameKey("Wei Wang 0014"), "wei wang")
    assertEquals(TextNormalizer.nameKey("Wei Wang"), "wei wang")
    // A four-digit token that is not a trailing ordinal is retained.
    assertEquals(TextNormalizer.nameKey("Wang 0014 Wei"), "wang 0014 wei")
  }

  test("name block keys combine family name and given initials") {
    assertEquals(TextNormalizer.nameBlockKey("Michael Ley"), "ley|m")
    assertEquals(
      TextNormalizer.nameBlockKey("Ruslan R. Fayzrakhmanov"),
      "fayzrakhmanov|rr"
    )
    assertEquals(TextNormalizer.nameBlockKey("Ley"), "ley")
    assertEquals(TextNormalizer.nameBlockKey(""), "")
  }

  test("title folding strips punctuation and case") {
    assertEquals(
      TextNormalizer.titleKey("OXPath-Based Data Acquisition for dblp."),
      "oxpath based data acquisition for dblp"
    )
    assertEquals(
      TextNormalizer.titleKey("DBLP - Some Lessons Learned."),
      "dblp some lessons learned"
    )
  }

  test("partition assignment is deterministic and in range") {
    val keys = Vector(
      "work:v1:dblp:abc",
      "doi|10.14778/1687553.1687577",
      "researcher|ley|m",
      "mention:v1:" + "0" * 64
    )
    (1 to 8).foreach { partitions =>
      keys.foreach { key =>
        val first = PartitionKey.partitionOf(key, partitions)
        val second = PartitionKey.partitionOf(key, partitions)
        assertEquals(first, second, s"$key is not stable")
        assert(first >= 0 && first < partitions, s"$key left the range")
      }
    }
  }

  test("partitioning ignores the stream label so a join stays intact") {
    val key = "doi|10.14778/1687553.1687577"
    val labelled = StreamKey.encode("identifier-keys", key)
    val otherLabel = StreamKey.encode("works", key)
    assertEquals(
      PartitionKey.partitionOf(StreamKey.logicalKey(labelled), 7),
      PartitionKey.partitionOf(StreamKey.logicalKey(otherLabel), 7)
    )
    assertEquals(StreamKey.decode(labelled), ("identifier-keys", key))
  }

  test("a key with no label decodes to itself") {
    assertEquals(StreamKey.decode("bare-key"), ("", "bare-key"))
  }

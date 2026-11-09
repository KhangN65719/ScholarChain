package scholarchain.rag

import scholarchain.core.YearWindow

class ResolverSuite extends munit.FunSuite:
  private val world = DemoWorld.build(semantic = false)
  private val ids = DemoWorld.ids
  private val resolver = new ResearcherResolverAgent(
    world.ports.directory,
    world.ports.neptune
  )
  private val window = DemoWorld.Early

  test("one canonical record resolves with coauthor context and confidence") {
    val r = resolver.resolveName("Ada Quillfeather", window)
    assertEquals(r.status, ResolutionStatus.Resolved)
    val chosen = r.chosen.get
    assertEquals(chosen.nodeId, ids.ada)
    assertEquals(chosen.candidate.orcid, Some("0000-0001-0000-0001"))
    assert(chosen.confidence >= 0.9)
    assertEquals(
      chosen.coauthorContext.map(_.coauthorId).toSet,
      Set(ids.boris, ids.fiona)
    )
    assertEquals(chosen.contextWindow, "2000-2009")
    assert(!r.selectedByUser)
  }

  test("case, accents and word order do not change the match") {
    assertEquals(
      resolver.resolveName("ADA quillfeather", window).chosen.map(_.nodeId),
      Some(ids.ada)
    )
    val reordered = resolver.resolveName("Quillfeather Ada", window)
    assertEquals(reordered.chosen.map(_.nodeId), Some(ids.ada))
    assert(
      reordered.chosen.get.confidence < 0.9,
      "a word-order match is less certain"
    )
  }

  test("two records with one name are returned and none is chosen") {
    val r = resolver.resolveName("Wei Zhang", window)
    assertEquals(r.status, ResolutionStatus.Ambiguous)
    assertEquals(r.chosen, None)
    assertEquals(r.candidates.map(_.nodeId).toSet, Set(ids.weiA, ids.weiB))
    assertEquals(
      r.candidates.flatMap(_.candidate.orcid).toSet,
      Set("0000-0007-0000-0007", "0000-0008-0000-0008")
    )
    assert(r.note.contains("2 stored records"))
  }

  test("the user can choose one of the candidates, and only a candidate") {
    val chosen = resolver.resolveName("Wei Zhang", window, Some(ids.weiB))
    assertEquals(chosen.status, ResolutionStatus.Resolved)
    assertEquals(chosen.chosen.map(_.nodeId), Some(ids.weiB))
    assert(chosen.selectedByUser)
    val stranger = resolver.resolveName("Wei Zhang", window, Some(ids.ada))
    assertEquals(stranger.status, ResolutionStatus.Ambiguous)
    assertEquals(stranger.chosen, None)
    val mention = resolver.resolveName("Wei Zhang", window, Some(ids.ines))
    assertEquals(mention.status, ResolutionStatus.Ambiguous)
  }

  test("an unknown name is not found") {
    val r = resolver.resolveName("Nobody Atall", window)
    assertEquals(r.status, ResolutionStatus.NotFound)
    assertEquals(r.candidates, Vector.empty)
  }

  test("an unusable name is invalid") {
    assertEquals(
      resolver.resolveName("!!! ???", window).status,
      ResolutionStatus.Invalid
    )
  }

  test("a lone unresolved mention is not an established researcher") {
    val r = resolver.resolveName("Ines Calder", window)
    assertEquals(r.status, ResolutionStatus.MentionOnly)
    assertEquals(r.chosen, None)
    assert(r.candidates.head.candidate.isMention)
  }

  test("a record that shares its ORCID with another node is low confidence") {
    val r = resolver.resolveName("Priya Raman", window)
    assertEquals(r.status, ResolutionStatus.LowConfidence)
    assertEquals(r.chosen, None)
    assertEquals(r.candidates.head.candidate.sharesOrcidWith, 1)
  }

  test("an identifier is checked against the directory") {
    assertEquals(
      resolver.resolveId(ids.ada, window).chosen.map(_.nodeId),
      Some(ids.ada)
    )
    assertEquals(
      resolver.resolveId(ids.ines, window).status,
      ResolutionStatus.MentionOnly
    )
    val unknown = resolver.resolveId(
      scholarchain.core.StableIds.researcherFromExternal("orcid", "9999-0000"),
      window
    )
    assertEquals(unknown.status, ResolutionStatus.NotFound)
  }

  test("candidate context follows the requested window") {
    val late = resolver.resolveName("Ada Quillfeather", YearWindow(2010, 2019))
    assertEquals(
      late.chosen.get.coauthorContext.map(_.coauthorId),
      Vector(ids.dana)
    )
  }

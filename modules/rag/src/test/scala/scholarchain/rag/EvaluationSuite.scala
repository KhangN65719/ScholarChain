package scholarchain.rag

import scholarchain.core.{EvidenceType, VerificationStatus}
import scholarchain.storage.{VectorFilter, VectorIndexes}

/** Retrieval and groundedness evaluation over the demo world. Deterministic
  * graph retrieval and semantic retrieval are scored separately, and a last
  * check shows that adding semantic context changes no deterministic claim.
  */
class EvaluationSuite extends munit.FunSuite:
  import RuntimeTestSupport.*

  /** question, substrings every answer must contain, and facts it must not */
  private val benchmark: Vector[(String, Vector[String], Vector[String])] =
    Vector(
      (
        "Which researcher has the most distinct coauthors from 2000-2009?",
        Vector(
          "Rank 1 by distinct coauthors (collaboration breadth) in window 2000-2009: Boris Marlowe with 3."
        ),
        Vector("Ines Calder")
      ),
      (
        "What is the maximum team size of Boris Marlowe from 2000-2009?",
        Vector("Boris Marlowe has 2 coauthors on the largest team paper"),
        Vector("Ada Quillfeather has")
      ),
      (
        "Who are the coauthors of Boris Marlowe in 2000-2009?",
        Vector(
          "Boris Marlowe and Ada Quillfeather co-authored 3 publication(s)",
          "Boris Marlowe and Chidi Okonkwo co-authored 1 publication(s)"
        ),
        Vector("Dana Whitcombe")
      ),
      (
        "How is Fiona Park connected to Chidi Okonkwo from 2000-2009?",
        Vector(
          "has 2 hop(s): Fiona Park -> Boris Marlowe -> Chidi Okonkwo",
          "Hop 2: Boris Marlowe and Chidi Okonkwo share 1 joint publication(s)"
        ),
        Vector("No collaboration path")
      ),
      (
        "Is Ada Quillfeather connected to Dana Whitcombe from 2000-2009?",
        Vector(
          "No collaboration path connects Ada Quillfeather and Dana Whitcombe"
        ),
        Vector("hop(s)")
      ),
      (
        "Which connected component is Gus Holloway in during 2000-2009?",
        Vector("Gus Holloway belongs to a connected component of 2 node(s)"),
        Vector("of 4 node(s)")
      ),
      (
        "Which community does Chidi Okonkwo belong to in 2000-2009?",
        Vector(
          "Chidi Okonkwo belongs to a community of 1 node(s)",
          "1 edge(s) to other communities"
        ),
        Vector("community of 3")
      ),
      (
        "Is Boris Marlowe a bridge in 2000-2009?",
        Vector("Boris Marlowe has 0.50 bridge score"),
        Vector("Ada Quillfeather has")
      ),
      (
        "Compare Dana Whitcombe in 2000-2009 and 2010-2019",
        Vector(
          "In window 2000-2009, Dana Whitcombe has 2 publications.",
          "In window 2010-2019, Dana Whitcombe has 1 publications."
        ),
        Vector.empty
      )
    )

  private def graphOnly = DemoWorld.build(semantic = false)

  test(
    "graph retrieval alone answers every benchmark question with verified facts"
  ) {
    val world = graphOnly
    var correct = 0
    benchmark.zipWithIndex.foreach {
      case ((question, expected, forbidden), i) =>
        val r = ask(world, question, ordinal = i)
        assert(!r.abstained, question)
        assert(
          expected.forall(r.response.answer.contains),
          s"$question\n${r.response.answer}"
        )
        assert(forbidden.forall(f => !r.response.answer.contains(f)), question)
        assertEquals(r.verdict, Some(VerificationVerdict.Verified), question)
        assert(
          r.response.evidence.items
            .forall(_.evidenceType != EvidenceType.TextChunk),
          "no semantic context in a graph-only run"
        )
        assert(
          r.response.claims
            .forall(_.verificationStatus == VerificationStatus.Verified),
          question
        )
        correct += 1
    }
    assertEquals(correct, benchmark.size)
  }

  private val semanticCases: Vector[(String, String, String, VectorFilter)] =
    Vector(
      (
        VectorIndexes.ResearcherProfile,
        "Boris Marlowe",
        "summary:v1:p-boris-early",
        VectorFilter.empty
      ),
      (
        VectorIndexes.ResearcherProfile,
        "Ada Quillfeather",
        "summary:v1:p-ada-late",
        VectorFilter(yearFrom = Some(2010), yearTo = Some(2019))
      ),
      (
        VectorIndexes.CollaborationCommunity,
        "Boris Marlowe Ada Quillfeather",
        "summary:v1:c1",
        VectorFilter.empty
      ),
      (
        VectorIndexes.PaperAbstract,
        "protein folding molecular dynamics",
        "summary:v1:w4",
        VectorFilter.empty
      ),
      (
        VectorIndexes.PaperAbstract,
        "graph neural networks citation",
        "summary:v1:w1",
        VectorFilter.empty
      ),
      (
        VectorIndexes.CollaborationPath,
        "path Ada Quillfeather Chidi Okonkwo",
        "summary:v1:x1",
        VectorFilter.empty
      )
    )

  test(
    "semantic retrieval is scored on its own: right summary first, every time"
  ) {
    val retrieval = DemoWorld.build().ports.semantic.get
    val hits = semanticCases.count { (index, query, expected, filter) =>
      retrieval
        .search(index, query, 3, filter)
        .headOption
        .map(_.hit.key)
        .contains(expected)
    }
    assertEquals(hits, semanticCases.size)
  }

  test(
    "a semantic hit holds a distance, a pointer and a notice, and no deterministic metric"
  ) {
    val retrieval = DemoWorld.build().ports.semantic.get
    semanticCases.foreach { (index, query, _, filter) =>
      val found = retrieval.search(index, query, 2, filter)
      val context = retrieval.context(query, found)
      assert(!context.deterministicMetricsIncluded)
      assert(
        context.items.forall(i => i.distance.isFinite && i.textRef.nonEmpty)
      )
    }
  }

  test("semantic context changes no deterministic claim") {
    val plain = graphOnly
    val rich = DemoWorld.build()
    benchmark.zipWithIndex.foreach { case ((question, _, _), i) =>
      val a = ask(plain, question, ordinal = i)
      val b = ask(rich, question, ordinal = i)
      val factual = (r: WorkflowResult) =>
        r.response.claims
          .filter(_.text != Rendering.SemanticText)
          .map(c => (c.text, c.verificationStatus))
      assertEquals(factual(b), factual(a), question)
      assertEquals(b.verdict, a.verdict, question)
      val semanticItems = b.response.evidence.items
        .filter(_.evidenceType == EvidenceType.TextChunk)
      b.response.claims.filter(_.text == Rendering.SemanticText).foreach { c =>
        assert(
          c.evidenceIds.forall(id => semanticItems.exists(_.evidenceId == id)),
          question
        )
      }
    }
  }

  test(
    "groundedness: no verified claim rests on a snippet alone, and the report has no unsupported claims"
  ) {
    val world = DemoWorld.build()
    benchmark.zipWithIndex.foreach { case ((question, _, _), i) =>
      val r = ask(world, question, ordinal = i)
      val byId = r.response.evidence.items.map(i => i.evidenceId -> i).toMap
      r.response.claims.filter(_.text != Rendering.SemanticText).foreach { c =>
        assert(
          c.evidenceIds
            .exists(id => byId(id).evidenceType != EvidenceType.TextChunk),
          s"$question: ${c.text}"
        )
      }
      val report = r.verification.get
      assertEquals(
        report.claims.count(_.verdict != VerificationVerdict.Verified),
        0,
        question
      )
      assert(report.changes.forall(_.action == "kept"), question)
    }
  }

  test(
    "every number in every answer claim is held by a cited deterministic fact"
  ) {
    val world = DemoWorld.build()
    val digits = "\\d+(?:\\.\\d+)?".r
    benchmark.zipWithIndex.foreach { case ((question, _, _), i) =>
      val r = ask(world, question, ordinal = i)
      r.response.claims.filter(_.text != Rendering.SemanticText).foreach { c =>
        val facts = c.evidenceIds
          .map(id => r.response.evidence.items.find(_.evidenceId == id).get)
          .filter(_.evidenceType != EvidenceType.TextChunk)
          .map(i => ujson.read(i.text.get))
        val strings = facts
          .flatMap(Facts.strings)
          .filter(_.length >= 3)
          .sortBy(s => -s.length)
        val stripped = strings.foldLeft(c.text)((t, s) => t.replace(s, " "))
        val known = facts.flatMap(Facts.numbers)
        digits.findAllIn(stripped).foreach { n =>
          assert(
            known.exists(k => math.abs(k - n.toDouble) < 0.005),
            s"$question: $n in ${c.text}"
          )
        }
      }
    }
  }

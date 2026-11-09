package scholarchain.rag

import java.io.ByteArrayOutputStream

class RuntimeDemoSuite extends munit.FunSuite:
  private def run(): String =
    val out = new ByteArrayOutputStream
    Console.withOut(out)(RuntimeDemoMain.main(Array.empty))
    out.toString("UTF-8")

  test(
    "the demo answers its questions offline and prints answer, claims, verdict and audit hash"
  ) {
    val text = run()
    RuntimeDemoMain.Questions.foreach { q =>
      assert(text.contains(s"Question: $q"), q)
    }
    assertEquals(
      "answerHash=[0-9a-f]{64}".r.findAllIn(text).size,
      RuntimeDemoMain.Questions.size
    )
    assert(text.contains("Verdict: VERIFIED; abstained: false"))
    assert(text.contains("Verdict: n/a (abstained); abstained: true"))
    assert(text.contains("Rank 1 by distinct coauthors"))
    assert(text.contains("- [verified]"))
    assert(text.contains("Claims:"))
  }

  test("the demo prints the same thing every time") {
    assertEquals(run(), run())
  }

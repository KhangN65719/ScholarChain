package scholarchain.rag

import scholarchain.core.StableIds
import scholarchain.storage.{ErrorCode, StorageException}

class ChunkingSuite extends munit.FunSuite:
  private val policy = ChunkPolicy(80, 20)

  private val abstractText: String =
    (1 to 60).map(i => s"word$i").mkString(" ") + " end."

  private def chunks(text: String, p: ChunkPolicy = policy) =
    Chunker.chunk("work:v1:abc", "dblp-2026", "paper-abstract", text, p)

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  test("a short text is one unchanged chunk") {
    val result = chunks("A short abstract.")
    assertEquals(result.size, 1)
    assertEquals(result.head.text, "A short abstract.")
    assertEquals((result.head.start, result.head.end), (0, 17))
    assertEquals(result.head.ordinal, 0)
  }

  test("chunks are bounded, ordered and cut at word boundaries") {
    val result = chunks(abstractText)
    assert(result.size > 3)
    assertEquals(result.map(_.ordinal), result.indices.toVector)
    result.foreach { c =>
      assert(c.text.length <= policy.maxChars, c.toString)
      assertEquals(c.text, abstractText.substring(c.start, c.end))
      // starts on a word start, ends where a word starts or at the end
      assert(!abstractText(c.start).isWhitespace || c.start == 0)
      assert(
        c.end == abstractText.length ||
          (!abstractText(c.end).isWhitespace && abstractText(
            c.end - 1
          ).isWhitespace)
      )
    }
  }

  test("neighbouring chunks overlap by whole words, at most the overlap") {
    val result = chunks(abstractText)
    result.zip(result.tail).foreach { (a, b) =>
      assert(b.start > a.start)
      assert(b.start <= a.end, "no gap")
      assert(a.end - b.start <= policy.overlapChars, s"$a $b")
    }
    assert(result.zip(result.tail).exists((a, b) => a.end - b.start > 0))
  }

  test("no loss: the chunks rebuild the text exactly") {
    val texts = Vector(
      abstractText,
      "  leading and trailing spaces  " * 12,
      "line one\nline two\n\nline three\t" * 15,
      "Zürich naïve café 数据 graph " * 20
    )
    texts.foreach { text =>
      assertEquals(Chunker.reassemble(chunks(text)), text)
    }
  }

  test("a zero overlap gives chunks that join without repeats") {
    val result = chunks(abstractText, ChunkPolicy(80, 0))
    assertEquals(result.map(_.text).mkString, abstractText)
    result.zip(result.tail).foreach((a, b) => assertEquals(a.end, b.start))
  }

  test("chunking is deterministic and the IDs are stable") {
    assertEquals(chunks(abstractText), chunks(abstractText))
    val first = chunks(abstractText).head
    assertEquals(
      first.chunkId,
      StableIds.chunk(
        "work:v1:abc",
        "dblp-2026",
        "paper-abstract",
        Some(0),
        first.text
      )
    )
    assert(first.chunkId.startsWith("chunk:v1:"))
    val ids = chunks(abstractText).map(_.chunkId)
    assertEquals(ids.distinct, ids)
  }

  test("the ID depends on entity, source version, type, ordinal and text") {
    val base = chunks(abstractText).head.chunkId
    def idWith(entity: String, version: String, kind: String, text: String) =
      Chunker.chunk(entity, version, kind, text, policy).head.chunkId
    assertNotEquals(
      idWith("work:v1:other", "dblp-2026", "paper-abstract", abstractText),
      base
    )
    assertNotEquals(
      idWith("work:v1:abc", "dblp-2027", "paper-abstract", abstractText),
      base
    )
    assertNotEquals(
      idWith("work:v1:abc", "dblp-2026", "other-type", abstractText),
      base
    )
    assertNotEquals(
      idWith(
        "work:v1:abc",
        "dblp-2026",
        "paper-abstract",
        "changed " + abstractText
      ),
      base
    )
    val second = chunks(abstractText)(1)
    assertNotEquals(second.chunkId, base)
  }

  test("a blank text and a word longer than a chunk are refused") {
    assertEquals(code(chunks("   \n ")), ErrorCode.MissingText)
    assertEquals(code(chunks("")), ErrorCode.MissingText)
    val long = "short " + ("x" * 200) + " tail"
    assertEquals(code(chunks(long)), ErrorCode.BoundExceeded)
  }

  private def message(thunk: => Any): String =
    intercept[StorageException](thunk).getMessage

  test("a whitespace run longer than a chunk is refused and named as such") {
    val text = "left" + (" " * 3000) + "right"
    val refused =
      intercept[StorageException](chunks(text, ChunkPolicy(1000, 100)))
    assertEquals(refused.code, ErrorCode.BoundExceeded)
    assert(refused.getMessage.contains("whitespace run of 3000 characters"))
    assert(refused.getMessage.contains("starting at 4"))
    assert(!refused.getMessage.contains("A word"), refused.getMessage)
    // A word that fills the window still gets the word message.
    val word = message(chunks("short " + ("x" * 200) + " tail"))
    assert(word.contains("A word of") && word.contains("longer than"), word)
    // A word of exactly maxChars followed by a long run is a run, not a word.
    val exact = message(chunks(("x" * 80) + (" " * 500) + "y"))
    assert(exact.contains("whitespace run of 500"), exact)
    // A leading run longer than a chunk names the run from position 0.
    val leading = message(chunks((" " * 300) + "z" * 5))
    assert(leading.contains("whitespace run"), leading)
  }

  test("a whitespace run that fits is chunked without loss") {
    val text = "left" + (" " * 40) + "right " + abstractText
    assertEquals(Chunker.reassemble(chunks(text)), text)
  }

  test("the policy rejects sizes that cannot make progress") {
    intercept[IllegalArgumentException](ChunkPolicy(10, 0))
    intercept[IllegalArgumentException](ChunkPolicy(100, 60))
    intercept[IllegalArgumentException](ChunkPolicy(100, -1))
    assertEquals(ChunkPolicy.Default, ChunkPolicy(1000, 100))
    assertEquals(ChunkPolicy.Version, "rag-chunking/1")
  }

  test("the default policy leaves a Phase 6 sized summary whole") {
    val summary = RagFixtures.summaries.map { row =>
      RagTemplates.render(row).text
    }
    summary.foreach { text =>
      assert(text.length <= ChunkPolicy.Default.maxChars, text.length.toString)
      assertEquals(
        Chunker.chunk("s", "v", "summary", text).map(_.text),
        Vector(text)
      )
    }
  }

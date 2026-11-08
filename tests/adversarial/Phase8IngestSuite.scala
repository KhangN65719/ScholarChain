package scholarchain.tests.adversarial

import munit.FunSuite
import scholarchain.rag.RagTemplates
import scholarchain.storage.*
import P8.*

/** Spec items 2 to 6: overwrite refusal and skips, the textRef round trip and
  * its tamper detection, compact metadata, the five indexes, resume and failure
  * handling. Everything runs on the local fakes through the real classes.
  */
class Phase8IngestSuite extends FunSuite:
  private val mapped = World.summaries.size // every row maps to an index

  private def row(label: String): Row =
    World.summaries(World.specs.indexWhere(_.label == label))

  private def source(rows: Vector[Row]): InMemoryTableSource =
    new InMemoryTableSource(World.tables.updated("semantic-summaries", rows))

  private def withText(r: Row, text: String): Row =
    r.updated("summaryText", s(text))

  private def allVectors(rig: Rig): Vector[(String, VectorRecord)] =
    indexNames.flatMap(index => rig.vectors(index).map(index -> _))

  private def documentSnapshot(rig: Rig): Map[String, String] =
    rig.store.list(SemanticDocuments.prefix).map(m => m.key -> m.sha256).toMap

  private def ingested(): (Rig, IngestReport) =
    val rig = new Rig()
    (rig, rig.ingest(World.source))

  private def fetch(rig: Rig, rec: VectorRecord): FetchedText =
    EmbeddingIngest.fetchText(
      rig.store,
      VectorMatch(rec.key, 0.0, rec.metadata)
    )

  private def vectorOf(rig: Rig, label: String): VectorRecord =
    allVectors(rig).collectFirst {
      case (_, rec) if rec.key == World.idOf(label) => rec
    }.get

  // Item 2: same key, different text or model ----------------------------

  test("same key with different text is refused and nothing is overwritten"):
    val (rig, first) = ingested()
    assertEquals(first.rejected, Vector.empty)
    assertEquals(first.written, mapped)
    val vectorsBefore = allVectors(rig)
    val docsBefore = documentSnapshot(rig)
    val originalText = fetch(rig, vectorOf(rig, "adaW1")).text
    val changed = World.summaries.map { r =>
      if r("summaryId").str == World.idOf("adaW1") then
        withText(r, "A completely different summary of the same researcher.")
      else r
    }
    val second = rig.ingest(source(changed), "2")
    assertEquals(second.written, 0)
    assertEquals(second.skipped, mapped - 1)
    assertEquals(second.rejected.map(_.key), Vector(World.idOf("adaW1")))
    assertEquals(
      second.rejected.map(_.code),
      Vector(ErrorCode.ChangedVectorContent)
    )
    assertEquals(rig.runStatus(second), Some("COMPLETED_WITH_REJECTS"))
    assertEquals(allVectors(rig), vectorsBefore)
    assertEquals(documentSnapshot(rig), docsBefore)
    assertEquals(fetch(rig, vectorOf(rig, "adaW1")).text, originalText)
    assert(
      second.rejectedKeys.nonEmpty,
      "the refusal is persisted as a rejected record"
    )

  test("same text and same model is skipped with zero writes"):
    val (rig, first) = ingested()
    val calls = rig.bucket.putCalls
    val objects = rig.store.snapshot
    val vectors = allVectors(rig)
    val second = rig.ingest(World.source, "2")
    assertEquals(second.written, 0)
    assertEquals(second.skipped, mapped)
    assertEquals(second.rejected, Vector.empty)
    assertEquals(second.documentsCreated, 0)
    assertEquals(rig.bucket.putCalls, calls, "no PutVectors call on a rerun")
    assertEquals(rig.store.snapshot, objects, "no new or changed object")
    assertEquals(allVectors(rig), vectors)
    assertEquals(second.manifestKey, first.manifestKey)
    assertEquals(rig.runStatus(second), Some("COMPLETED"))

  test("a changed model version is refused, not silently re-embedded"):
    val (rig, _) = ingested()
    val vectors = allVectors(rig)
    val calls = rig.bucket.putCalls
    val other = new Rig(
      embedder = new RenamedEmbedder("hash-embed-v2"),
      bucket = rig.bucket,
      store = rig.store,
      table = rig.table
    )
    val report = other.ingest(World.source, "model-2")
    assertEquals(report.written, 0)
    assertEquals(report.rejected.size, mapped)
    assert(report.rejected.forall(_.code == ErrorCode.ChangedVectorContent))
    assertEquals(rig.bucket.putCalls, calls)
    assertEquals(allVectors(rig), vectors)
    allVectors(rig).foreach { case (_, rec) =>
      assertEquals(
        metaStr(rec.metadata, "embeddingModel"),
        Some("hash-embed-v1")
      )
    }

  test("a different embedding dimension cannot enter the existing indexes"):
    val (rig, _) = ingested()
    val vectors = allVectors(rig)
    val other = new Rig(
      embedder = new SmallEmbedder,
      bucket = rig.bucket,
      store = rig.store,
      table = rig.table
    )
    intercept[StorageException](other.ingest(World.source, "dim-8"))
    assertEquals(allVectors(rig), vectors)

  test("duplicate key with different content in one batch: first kept"):
    val rig = new Rig()
    val dup = withText(row("adaW1"), "Second variant that must be rejected.")
    val report = rig.ingest(source(World.summaries :+ dup))
    assertEquals(report.written, mapped)
    assertEquals(report.rejected.map(_.key), Vector(World.idOf("adaW1")))
    assertEquals(
      report.rejected.map(_.code),
      Vector(ErrorCode.DuplicateVectorKey)
    )
    assertEquals(rig.runStatus(report), Some("COMPLETED_WITH_REJECTS"))
    val text = fetch(rig, vectorOf(rig, "adaW1")).text
    assert(text.contains("Researcher Ada"), text)
    assert(!text.contains("Second variant"), text)

  test("duplicate key: the first in source order wins, whatever it says"):
    val rig = new Rig()
    val firstWins = withText(row("adaW1"), "Variant first in source order.")
    val later = row("adaW1")
    val report = rig.ingest(
      source(firstWins +: World.summaries.filterNot(_ == later) :+ later)
    )
    assertEquals(
      report.rejected.map(_.code),
      Vector(ErrorCode.DuplicateVectorKey)
    )
    assert(fetch(rig, vectorOf(rig, "adaW1")).text.contains("Variant first"))

  test("an identical duplicate row is collapsed and counted, not rejected"):
    val rig = new Rig()
    val report = rig.ingest(source(World.summaries :+ row("workB")))
    assertEquals(report.rejected, Vector.empty)
    assertEquals(report.duplicates, 1)
    assertEquals(report.written, mapped)
    assertEquals(allVectors(rig).size, mapped)

  // Item 3: textRef round trip and tamper detection ------------------------

  test("every vector resolves to a stored document with its textHash"):
    val (rig, _) = ingested()
    val all = allVectors(rig)
    assertEquals(all.size, mapped)
    all.foreach { case (index, rec) =>
      val ref = metaStr(rec.metadata, "textRef").get
      val doc =
        ujson.read(rig.store.get(ref).getOrElse(fail(s"no object $ref")))
      assertEquals(doc("summaryId").str, rec.key, index)
      assertEquals(sha(doc("text").str), metaStr(rec.metadata, "textHash").get)
      assertEquals(doc("textHash").str, metaStr(rec.metadata, "textHash").get)
      assertEquals(fetch(rig, rec).text, doc("text").str)
      assert(ref.startsWith("semantic-context/"), ref)
    }
    val distinctRefs =
      all.flatMap(a => metaStr(a._2.metadata, "textRef")).toSet.size
    assertEquals(distinctRefs, mapped, "each vector has its own document")

  private def tampered(
      change: (Rig, VectorRecord, String) => VectorMatch
  ): ErrorCode =
    val (rig, _) = ingested()
    val rec = vectorOf(rig, "adaW1")
    val ref = metaStr(rec.metadata, "textRef").get
    val hit = change(rig, rec, ref)
    code(EmbeddingIngest.fetchText(rig.store, hit))

  private def hitOf(rec: VectorRecord): VectorMatch =
    VectorMatch(rec.key, 0.0, rec.metadata)

  test("a document with edited text is detected"):
    val result = tampered { (rig, rec, ref) =>
      val doc = ujson.read(rig.store.get(ref).get)
      doc("text") = "Edited after the fact."
      rig.store.overwrite(ref, utf8(ujson.write(doc)))
      hitOf(rec)
    }
    assertEquals(result, ErrorCode.TextHashMismatch)

  test("edited text with a recomputed document hash is still detected"):
    val result = tampered { (rig, rec, ref) =>
      val doc = ujson.read(rig.store.get(ref).get)
      doc("text") = "Edited and rehashed."
      doc("textHash") = sha("Edited and rehashed.")
      rig.store.overwrite(ref, utf8(ujson.write(doc)))
      hitOf(rec)
    }
    assertEquals(result, ErrorCode.TextHashMismatch)

  test("a deleted document is detected"):
    val result = tampered { (rig, rec, ref) =>
      rig.store.delete(ref)
      hitOf(rec)
    }
    assertEquals(result, ErrorCode.MissingText)

  test("a swapped document is detected"):
    val result = tampered { (rig, rec, ref) =>
      val other = metaStr(vectorOf(rig, "zoeW1").metadata, "textRef").get
      rig.store.overwrite(ref, rig.store.get(other).get)
      hitOf(rec)
    }
    assertEquals(result, ErrorCode.TextHashMismatch)

  test("a textRef misdirected to another valid document is detected"):
    val result = tampered { (rig, rec, _) =>
      val other = metaStr(vectorOf(rig, "zoeW1").metadata, "textRef").get
      hitOf(rec).copy(metadata =
        rec.metadata.updated("textRef", MetaValue.Str(other))
      )
    }
    assertEquals(result, ErrorCode.TextHashMismatch)

  test("a textRef outside semantic-context or with .. is refused"):
    Vector("graph/nodes-work/x.json", "semantic-context/../graph/x", "/abs")
      .foreach { ref =>
        val result = tampered { (_, rec, _) =>
          hitOf(rec).copy(metadata =
            rec.metadata.updated("textRef", MetaValue.Str(ref))
          )
        }
        assertEquals(result, ErrorCode.InvalidKey, ref)
      }

  test("an altered or missing vector textHash is detected"):
    val altered = tampered { (_, rec, _) =>
      hitOf(rec).copy(metadata =
        rec.metadata.updated("textHash", MetaValue.Str("0" * 64))
      )
    }
    assertEquals(altered, ErrorCode.TextHashMismatch)
    val noHash = tampered { (_, rec, _) =>
      hitOf(rec).copy(metadata = rec.metadata - "textHash")
    }
    assertEquals(noHash, ErrorCode.MissingText)
    val noRef = tampered { (_, rec, _) =>
      hitOf(rec).copy(metadata = rec.metadata - "textRef")
    }
    assertEquals(noRef, ErrorCode.MissingText)

  test("garbage or incomplete document bodies are detected"):
    val notJson = tampered { (rig, rec, ref) =>
      rig.store.overwrite(ref, utf8("this is not json"))
      hitOf(rec)
    }
    assertEquals(notJson, ErrorCode.TextHashMismatch)
    val noText = tampered { (rig, rec, ref) =>
      val doc = ujson.read(rig.store.get(ref).get)
      doc.obj.remove("text")
      rig.store.overwrite(ref, utf8(ujson.write(doc)))
      hitOf(rec)
    }
    assertEquals(noText, ErrorCode.TextHashMismatch)
    val foreignSchema = tampered { (rig, rec, ref) =>
      val doc = ujson.read(rig.store.get(ref).get)
      doc("schema") = "something.else/1"
      rig.store.overwrite(ref, utf8(ujson.write(doc)))
      hitOf(rec)
    }
    assertEquals(foreignSchema, ErrorCode.TextHashMismatch)

  test("a document that changed on the real object store cannot be rewritten"):
    val (rig, _) = ingested()
    val rec = vectorOf(rig, "adaW1")
    val ref = metaStr(rec.metadata, "textRef").get
    val code1 = code(
      rig.store.putImmutable(ref, utf8("{}"), "attacker")
    )
    assertEquals(code1, ErrorCode.ImmutableKeyConflict)

  // Item 4: compact metadata ------------------------------------------------

  test("vector metadata is compact, typed and free of text and metrics"):
    val (rig, _) = ingested()
    val required = Set(
      "textHash",
      "embeddingModel",
      "runId",
      "documentType",
      "textRef",
      "subjectId",
      "summaryType"
    )
    allVectors(rig).foreach { case (_, rec) =>
      val md = rec.metadata
      assert(required.subsetOf(md.keySet), s"missing in ${md.keySet}")
      assert(md.keySet.subsetOf(VectorMetadata.Allowed), md.keySet.toString)
      assert(md.keySet.intersect(metricKeys).isEmpty, md.keySet.toString)
      assert(md.keySet.intersect(VectorMetadata.TextKeys).isEmpty)
      assert(!md.contains("topic") && !md.contains("institution"))
      assert(
        VectorMetadata.sizeBytes(
          md
        ) < Limits.S3Vectors.MaxFilterableMetadataBytes,
        s"${VectorMetadata.sizeBytes(md)} bytes"
      )
      assertEquals(metaStr(md, "runId"), Some(runId))
      assertEquals(metaStr(md, "embeddingModel"), Some("hash-embed-v1"))
      assertEquals(metaStr(md, "documentType"), metaStr(md, "summaryType"))
      assert(metaStr(md, "textHash").get.matches("[0-9a-f]{64}"))
      val spark = World.summaries
        .find(_("summaryId").str == rec.key)
        .get("summaryText")
        .str
      md.values.foreach {
        case MetaValue.Str(v) =>
          assert(v.length <= 200, v)
          assert(v.split("\\s+").length <= 12, s"prose in metadata: $v")
          assert(!v.contains(spark.take(40)), v)
        case MetaValue.Strs(vs) => vs.foreach(v => assert(v.length <= 200))
        case _                  => ()
      }
    }

  test("the evidence count is honest even when the lists are capped"):
    val works = (0 until 200).map(i => id("work", s"many$i"))
    val nodes = (0 until 200).map(i => id("researcher", s"many$i"))
    val big = Spec(
      "big",
      "work",
      None,
      works.head,
      "Work with a huge evidence list. " + ("lorem ipsum " * 8000),
      works,
      Seq("teamSize"),
      nodes
    )
    val rig = new Rig()
    val report = rig.ingest(source(World.summaries :+ summaryRow(big)))
    assertEquals(report.rejected, Vector.empty)
    val rec = allVectors(rig).collectFirst {
      case (_, r) if r.key == big.summaryId => r
    }.get
    assertEquals(rec.metadata("evidenceWorkCount"), MetaValue.Num(200.0))
    rec.metadata("evidenceWorkIds") match
      case MetaValue.Strs(vs) => assert(vs.size <= 5)
      case other              => fail(other.toString)
    assert(
      VectorMetadata.sizeBytes(rec.metadata) <
        Limits.S3Vectors.MaxFilterableMetadataBytes
    )
    val text = fetch(rig, rec).text
    assert(text.length > 90000, "the long text stays whole in S3")

  test("topic and institution are never invented, even from stray columns"):
    val stray = row("workA") ++ Map(
      "topic" -> s("Databases"),
      "institution" -> s("MIT")
    )
    val rig = new Rig()
    rig.ingest(source(World.summaries.filterNot(_ == row("workA")) :+ stray))
    allVectors(rig).foreach { case (_, rec) =>
      assert(!rec.metadata.contains("topic"))
      assert(!rec.metadata.contains("institution"))
    }
    val query = VectorQuery(
      VectorIndexes.PaperAbstract,
      rig.probe,
      10,
      VectorFilter(topic = Some("Databases"))
    )
    assertEquals(rig.bucket.query(query), Vector.empty)

  test("a bad row is rejected and reported; the run goes on without it"):
    val prose = row("zoeW1") ++ Map(
      "nodeKind" -> s(
        "this is long free prose that does not belong in filterable " +
          "metadata at all because it has far more than twelve words in it"
      )
    )
    val withNul = withText(row("graceW2"), "bad\u0000text")
    val noText = row("comm1") - "summaryText"
    val blank = withText(row("comm2"), "   ")
    val foreign = summaryRow(
      World.label("pathAG"),
      "run:v1:99999999-9999-4999-8999-999999999999"
    )
    val untouched = World.summaries.filterNot(r =>
      Set("zoeW1", "graceW2", "comm1", "comm2", "pathAG").exists(l =>
        World.idOf(l) == r("summaryId").str
      )
    )
    val rig = new Rig()
    val report =
      rig.ingest(
        source(untouched ++ Vector(prose, withNul, noText, blank, foreign))
      )
    assertEquals(report.written, mapped - 5)
    assertEquals(report.rejected.size, 5)
    assertEquals(
      report.rejected.map(r => (r.key, r.code)).toMap,
      Map(
        World.idOf("zoeW1") -> ErrorCode.MetadataInvalid,
        World.idOf("graceW2") -> ErrorCode.NullCharacter,
        World.idOf("comm1") -> ErrorCode.MalformedRow,
        World.idOf("comm2") -> ErrorCode.MalformedRow,
        World.idOf("pathAG") -> ErrorCode.MalformedRow
      )
    )
    assertEquals(rig.runStatus(report), Some("COMPLETED_WITH_REJECTS"))
    assertEquals(allVectors(rig).size, mapped - 5)
    // A rejected row leaves no vector pointing at a missing document.
    allVectors(rig).foreach { case (_, rec) => fetch(rig, rec) }

  // Item 5: the five indexes -------------------------------------------------

  private def exists(rig: Rig, index: String): Boolean =
    try
      rig.bucket.query(VectorQuery(index, rig.probe, 1))
      true
    catch case e: StorageException if e.code == ErrorCode.InvalidKey => false

  test("all five indexes exist with the right specs after an ingest"):
    val (rig, report) = ingested()
    assertEquals(report.perIndex.map(_.index), indexNames)
    assertEquals(
      indexNames,
      Vector(
        "researcher-profile-v1",
        "collaboration-community-v1",
        "paper-abstract-v1",
        "collaboration-path-v1",
        "topic-institution-summary-v1"
      )
    )
    indexNames.foreach(name => assert(exists(rig, name), name))
    assert(!exists(rig, "unknown-index-v1"))
    val types = Vector(
      Some("researcher-profile"),
      Some("community"),
      Some("work"),
      Some("collaboration-path"),
      None
    )
    indexNames.zip(types).foreach { (name, kind) =>
      rig.bucket.ensureIndex(VectorIndexSpec(name, 384, "cosine", kind))
      intercept[StorageException](
        rig.bucket.ensureIndex(VectorIndexSpec(name, 384, "euclidean", kind))
      )
      intercept[StorageException](
        rig.bucket.ensureIndex(VectorIndexSpec(name, 256, "cosine", kind))
      )
    }
    assertEquals(
      report.perIndex.map(i => i.index -> i.vectorsInIndex).toMap,
      Map(
        "researcher-profile-v1" -> 4,
        "collaboration-community-v1" -> 2,
        "paper-abstract-v1" -> 3,
        "collaboration-path-v1" -> 2,
        "topic-institution-summary-v1" -> 0
      )
    )

  test("an empty export still creates all five indexes, all empty"):
    val rig = new Rig()
    val report = rig.ingest(source(Vector.empty))
    indexNames.foreach(name => assert(exists(rig, name), name))
    assertEquals(report.written, 0)
    assertEquals(report.perIndex.map(_.vectorsInIndex), Vector.fill(5)(0))
    assertEquals(rig.runStatus(report), Some("COMPLETED"))

  test("topic and institution summaries have no index: unmapped, none made"):
    val extra = Vector("topic", "institution").map { kind =>
      summaryRow(
        Spec(
          s"x-$kind",
          kind,
          None,
          s"$kind-1",
          s"A $kind summary.",
          Seq.empty,
          Seq.empty,
          Seq.empty
        )
      )
    }
    val rig = new Rig()
    val report = rig.ingest(source(World.summaries ++ extra))
    assertEquals(report.unmapped.size, 2)
    assert(report.unmapped.forall(_.code == ErrorCode.UnmappedSummary))
    assertEquals(allVectors(rig).size, mapped)
    assertEquals(rig.bucket.count(VectorIndexes.TopicInstitutionSummary), 0)
    assert(rig.store.list("logs/vector-rejects/").nonEmpty)
    val persisted = rig.store
      .list("logs/vector-rejects/")
      .flatMap(m => new String(rig.store.get(m.key).get, "UTF-8").linesIterator)
      .map(l => ujson.read(l)("key").str)
    assertEquals(persisted.toSet, extra.map(_("summaryId").str).toSet)

  // Item 6: resume, failure, retry ------------------------------------------

  test("an interrupted ingest resumes without rewriting finished batches"):
    val rig = new Rig()
    rig.bucket.onPut =
      (call, _, _) => if call == 3 then throw new InterruptedException("killed")
    // munit's intercept rethrows InterruptedException, so catch it by hand.
    val killed =
      try
        rig.ingest(World.source, "r", batch = 2)
        false
      catch case _: InterruptedException => true
    assert(killed, "the injected kill must abort the run")
    val executionId = s"embed:$runId:r"
    val meta = rig.table.get(s"RUN#$executionId", "META").get
    assertEquals(Attr.optS(meta, "status"), Some("STARTED"))
    val written = rig.bucket.landed.toVector
    assertEquals(written.size, 4, "two batches of two landed before the kill")
    rig.bucket.onPut = (_, _, _) => ()
    val report = rig.ingest(World.source, "r", batch = 2)
    assertEquals(rig.runStatus(report), Some("COMPLETED"))
    assertEquals(report.skippedResumedTotal, 4)
    assertEquals(report.written, mapped - 4)
    assertEquals(rig.bucket.landed.size, mapped)
    assertEquals(
      rig.bucket.landed.toSet.size,
      mapped,
      "no key was written twice"
    )
    assertEquals(allVectors(rig).size, mapped)
    val attempts = rig.batchItems(report).map(i => i("attempt").num.toInt)
    assert(attempts.contains(2), s"the killed batch is attempt 2: $attempts")
    assert(
      rig
        .batchItems(report)
        .forall(i => Attr.optS(i, "status").exists(_.startsWith("FINISHED")))
    )

  test("a failing batch is recorded and the run ends FAILED, then resumes"):
    val rig = new Rig()
    rig.bucket.onPut = (_, index, _) =>
      if index == VectorIndexes.PaperAbstract then
        throw new RuntimeException("disk on fire")
    val report = rig.ingest(World.source, "f")
    assertEquals(report.failedBatches.size, 1)
    assertEquals(report.failedBatches.head.index, VectorIndexes.PaperAbstract)
    assert(report.failedBatches.head.reason.contains("disk on fire"))
    assertEquals(rig.runStatus(report), Some("FAILED"))
    val failedItem = rig
      .batchItems(report)
      .find(i => Attr.optS(i, "status").contains("FAILED"))
      .get
    assert(Attr.optS(failedItem, "failure").exists(_.contains("disk on fire")))
    assertEquals(failedItem("attempt").num.toInt, 1)
    assertEquals(allVectors(rig).size, mapped - 3, "the other indexes landed")
    // Resume with the same label: only the failed batch is redone.
    rig.bucket.onPut = (_, _, _) => ()
    val resumed = rig.ingest(World.source, "f")
    assertEquals(rig.runStatus(resumed), Some("COMPLETED"))
    assertEquals(resumed.written, 3)
    assertEquals(resumed.skippedResumedTotal, mapped - 3)
    assertEquals(allVectors(rig).size, mapped)
    val redone = rig
      .batchItems(resumed)
      .find(i => Attr.optS(i, "index").contains(VectorIndexes.PaperAbstract))
      .get
    assertEquals(redone("attempt").num.toInt, 2)

  test("transient failures are retried within the bound and counted"):
    val rig = new Rig()
    rig.bucket.onPut = (call, _, _) =>
      if call <= 2 then throw new TransientStorageException("throttled")
    val report = rig.ingest(World.source, "t")
    assertEquals(report.written, mapped)
    assertEquals(report.rejected, Vector.empty)
    assertEquals(report.retries, 2)
    assertEquals(rig.runStatus(report), Some("COMPLETED"))
    val ledgerRetries =
      rig.batchItems(report).map(i => i("retries").num.toInt).sum
    assertEquals(ledgerRetries, 2)

  test("a permanently throttled batch stops after the bound, never loops"):
    val rig = new Rig()
    var paperCalls = 0
    rig.bucket.onPut = (_, index, _) =>
      if index == VectorIndexes.PaperAbstract then
        paperCalls += 1
        throw new TransientStorageException("throttled forever")
    val report = rig.ingest(World.source, "p", retry = RetryPolicy(3, 1, 4))
    assertEquals(paperCalls, 3, "exactly maxAttempts calls")
    assertEquals(report.rejected.size, 3)
    assert(report.rejected.forall(_.code == ErrorCode.RetryExhausted))
    assertEquals(rig.runStatus(report), Some("COMPLETED_WITH_REJECTS"))
    assertEquals(allVectors(rig).size, mapped - 3)
    assertEquals(report.retries, 2)
    val item = rig
      .batchItems(report)
      .find(i => Attr.optS(i, "index").contains(VectorIndexes.PaperAbstract))
      .get
    assertEquals(Attr.optS(item, "status"), Some("FINISHED_WITH_REJECTS"))
    assertEquals(item("retries").num.toInt, 2)

  test("a single-attempt policy makes one call and no retry"):
    val rig = new Rig()
    var calls = 0
    rig.bucket.onPut = (_, _, _) =>
      calls += 1
      throw new TransientStorageException("x")
    val report = rig.ingest(World.source, "one", retry = RetryPolicy(1, 1, 1))
    assertEquals(calls, 4, "one call per non-empty index, no retry")
    assertEquals(report.written, 0)
    assertEquals(report.rejected.size, mapped)
    assertEquals(report.retries, 0)

  test("a deleted document is put back on a rerun with zero vector writes"):
    val (rig, _) = ingested()
    val ref = metaStr(vectorOf(rig, "workA").metadata, "textRef").get
    val before = rig.store.get(ref).get.toVector
    rig.store.delete(ref)
    val calls = rig.bucket.putCalls
    val second = rig.ingest(World.source, "2")
    assertEquals(rig.bucket.putCalls, calls)
    assertEquals(second.written, 0)
    assertEquals(second.documentsCreated, 1)
    assertEquals(rig.store.get(ref).map(_.toVector), Some(before))
    fetch(rig, vectorOf(rig, "workA"))

  test("a document altered on the object store rejects only that key"):
    val (rig, _) = ingested()
    val ref = metaStr(vectorOf(rig, "workA").metadata, "textRef").get
    val forged = utf8("{\"forged\":true}")
    rig.store.overwrite(ref, forged)
    val vectors = allVectors(rig)
    val second = rig.ingest(World.source, "2")
    // One bad document is one rejected key, not a failed batch or run.
    assertEquals(second.failedBatches, Vector.empty)
    assertEquals(second.rejected.map(_.key), Vector(World.idOf("workA")))
    assertEquals(
      second.rejected.map(_.code),
      Vector(ErrorCode.ImmutableKeyConflict)
    )
    // The fourth field of a RejectedVector is its reason text.
    assert(
      second.rejected.forall(_.productElement(3).toString.nonEmpty),
      "a reason is recorded"
    )
    assertEquals(
      second.rejected.map(_.index),
      Vector(VectorIndexes.PaperAbstract)
    )
    assertEquals(second.written, 0)
    assertEquals(second.skipped, mapped - 1)
    assertEquals(rig.runStatus(second), Some("COMPLETED_WITH_REJECTS"))
    assert(second.rejectedKeys.nonEmpty, "the rejection is persisted")
    assertEquals(allVectors(rig), vectors)
    assertEquals(rig.store.get(ref).map(_.toVector), Some(forged.toVector))

  test("a document error on the unchanged path leaves the rest of the batch"):
    // Paper batch: workA is new, workB's stored document is forged, workC is
    // unchanged. The forged one is rejected; workA is written whole.
    val rig = new Rig()
    val without =
      World.summaries.filterNot(_("summaryId").str == World.idOf("workA"))
    rig.ingest(source(without), "1")
    val ref = metaStr(vectorOf(rig, "workB").metadata, "textRef").get
    val forged = utf8("{\"forged\":true}")
    rig.store.overwrite(ref, forged)
    val workBBefore = vectorOf(rig, "workB")
    val report = rig.ingest(World.source, "2")
    assertEquals(report.failedBatches, Vector.empty)
    assertEquals(report.rejected.map(_.key), Vector(World.idOf("workB")))
    assertEquals(
      report.rejected.map(_.code),
      Vector(ErrorCode.ImmutableKeyConflict)
    )
    assertEquals(report.written, 1)
    assertEquals(vectorOf(rig, "workA").key, World.idOf("workA"))
    assertEquals(
      fetch(rig, vectorOf(rig, "workA")).text,
      RagTemplates.render(row("workA")).text
    )
    assertEquals(vectorOf(rig, "workB"), workBBefore)
    assertEquals(rig.store.get(ref).map(_.toVector), Some(forged.toVector))
    val paperBatch = rig
      .batchItems(report)
      .find(i => Attr.optS(i, "index").contains(VectorIndexes.PaperAbstract))
      .get
    assertEquals(Attr.optS(paperBatch, "status"), Some("FINISHED_WITH_REJECTS"))
    assert(
      Attr.optS(paperBatch, "failure").exists(_.contains(World.idOf("workB"))),
      "the batch record names the rejected key"
    )
    assertEquals(rig.runStatus(report), Some("COMPLETED_WITH_REJECTS"))

  test("rows of another run than the source run are all rejected"):
    val rig = new Rig()
    val report =
      rig.ingest(
        World.source,
        run = "run:v1:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
      )
    assertEquals(report.written, 0)
    assertEquals(report.rejected.size, mapped)
    assert(report.rejected.forall(_.code == ErrorCode.MalformedRow))
    assertEquals(allVectors(rig), Vector.empty)
    assertEquals(rig.runStatus(report), Some("COMPLETED_WITH_REJECTS"))

  // B1: a same-label rerun over other input is refused up front ---------------

  private final class CountingEmbedder(inner: Embedder) extends Embedder:
    var calls = 0
    val modelVersion: String = inner.modelVersion
    val dimension: Int = inner.dimension
    def embed(text: String): Vector[Float] =
      calls += 1
      inner.embed(text)

  /** The same text under another template version. */
  private object BumpedRenderer extends SummaryRenderer:
    def render(row: Row): RenderedDocument =
      val d = RagTemplates.render(row)
      d.copy(templateVersion = d.templateVersion + "-bumped")

  private def rerunRig(
      first: Rig,
      embedder: Embedder = new HashEmbedder,
      renderer: SummaryRenderer = RagTemplates
  ): Rig = new Rig(renderer, embedder, first.bucket, first.store, first.table)

  private def ledgerState(
      rig: Rig,
      label: String
  ): (Option[Item], Vector[Item]) =
    val pk = s"RUN#embed:$runId:$label"
    (rig.table.get(pk, "META"), rig.table.query(pk, Some("STAGE#"), 1000))

  /** Reruns `label` over `rows` and requires `ExecutionInputChanged` with
    * nothing embedded, written, skipped or recorded.
    */
  private def assertRefused(
      first: Rig,
      rerun: Rig,
      counting: CountingEmbedder,
      rows: TableSource,
      label: String = "1"
  ): StorageException =
    val vectors = allVectors(first)
    val objects = first.store.snapshot
    val puts = first.bucket.putCalls
    val landed = first.bucket.landed.toVector
    val attempted = first.bucket.attempted.toVector
    val ledger = ledgerState(first, label)
    val embedsBefore = counting.calls
    val failure = intercept[StorageException](rerun.ingest(rows, label))
    assertEquals(failure.code, ErrorCode.ExecutionInputChanged)
    assert(
      failure.getMessage.contains("executionLabel"),
      s"the refusal must tell the caller to use a new label: ${failure.getMessage}"
    )
    assertEquals(counting.calls, embedsBefore, "nothing was embedded")
    assertEquals(first.bucket.putCalls, puts, "no PutVectors call")
    assertEquals(first.bucket.attempted.toVector, attempted)
    assertEquals(first.bucket.landed.toVector, landed)
    assertEquals(allVectors(first), vectors, "stored vectors are identical")
    assertEquals(first.store.snapshot, objects, "stored documents and manifest")
    assertEquals(ledgerState(first, label), ledger, "ledger records untouched")
    failure

  private def counted(): CountingEmbedder = new CountingEmbedder(
    new HashEmbedder
  )

  test("the same execution label over a changed row is refused, not silent"):
    val (rig, _) = ingested()
    val changed = World.summaries.map { r =>
      if r("summaryId").str == World.idOf("adaW1") then
        withText(r, "Changed under the same label.")
      else r
    }
    val counting = counted()
    assertRefused(rig, rerunRig(rig, counting), counting, source(changed))

  test("the same execution label over a changed row never overwrites"):
    val (rig, _) = ingested()
    val vectors = allVectors(rig)
    val docs = documentSnapshot(rig)
    val originalText = fetch(rig, vectorOf(rig, "adaW1")).text
    val changed = World.summaries.map { r =>
      if r("summaryId").str == World.idOf("adaW1") then
        withText(r, "Changed under the same label.")
      else r
    }
    val counting = counted()
    assertRefused(rig, rerunRig(rig, counting), counting, source(changed))
    assertEquals(allVectors(rig), vectors)
    assertEquals(documentSnapshot(rig), docs)
    // Under a new label the old rule applies: that key is refused, not rewritten.
    val relabelled = rig.ingest(source(changed), "2")
    assertEquals(relabelled.rejected.map(_.key), Vector(World.idOf("adaW1")))
    assertEquals(
      relabelled.rejected.map(_.code),
      Vector(ErrorCode.ChangedVectorContent)
    )
    assertEquals(allVectors(rig), vectors)
    assertEquals(documentSnapshot(rig), docs)
    assertEquals(fetch(rig, vectorOf(rig, "adaW1")).text, originalText)

  test("the same execution label with an added row is refused"):
    val (rig, _) = ingested()
    val extra = summaryRow(
      Spec(
        "extra",
        "work",
        None,
        id("work", "late-arrival"),
        "Work Late arrival about stellar nucleosynthesis, team of 2 authors.",
        Seq(id("work", "late-arrival")),
        Seq("teamSize"),
        Seq(World.ada)
      )
    )
    val counting = counted()
    assertRefused(
      rig,
      rerunRig(rig, counting),
      counting,
      source(World.summaries :+ extra)
    )
    // The new row never entered the index under the refused run.
    assertEquals(allVectors(rig).size, mapped)
    assert(!allVectors(rig).exists(_._2.key == extra("summaryId").str))

  test("the same execution label with a removed row is refused"):
    val (rig, _) = ingested()
    val counting = counted()
    assertRefused(
      rig,
      rerunRig(rig, counting),
      counting,
      source(
        World.summaries.filterNot(_("summaryId").str == World.idOf("workC"))
      )
    )

  test("the same execution label with a changed model is refused"):
    val (rig, _) = ingested()
    val counting = new CountingEmbedder(new RenamedEmbedder("hash-embed-v2"))
    assertRefused(rig, rerunRig(rig, counting), counting, World.source)
    allVectors(rig).foreach { case (_, rec) =>
      assertEquals(
        metaStr(rec.metadata, "embeddingModel"),
        Some("hash-embed-v1")
      )
    }

  test("the same execution label with a changed template version is refused"):
    val (rig, _) = ingested()
    val counting = counted()
    assertRefused(
      rig,
      rerunRig(rig, counting, BumpedRenderer),
      counting,
      World.source
    )

  test("an identical rerun of the same label still resumes"):
    val (rig, first) = ingested()
    val counting = counted()
    val puts = rig.bucket.putCalls
    val objects = rig.store.snapshot
    val vectors = allVectors(rig)
    val second = rerunRig(rig, counting).ingest(World.source, "1")
    assertEquals(second.executionId, first.executionId)
    assertEquals(second.written, 0)
    assertEquals(second.skippedResumedTotal, mapped)
    assertEquals(second.rejected, Vector.empty)
    assertEquals(second.failedBatches, Vector.empty)
    assertEquals(counting.calls, 0, "a resumed run embeds nothing")
    assertEquals(rig.bucket.putCalls, puts)
    assertEquals(rig.store.snapshot, objects)
    assertEquals(allVectors(rig), vectors)
    assertEquals(rig.runStatus(second), Some("COMPLETED"))

  private def stripAttr(rig: Rig, sk: String, attr: String): Unit =
    val pk = s"RUN#embed:$runId:1"
    val item = rig.table.get(pk, sk).get
    assert(item.contains(attr), s"$sk has no $attr to strip: ${item.keySet}")
    val version = item("version").num.toLong
    rig.table.putIfVersion(
      (item - attr) + ("version" -> Attr.n(version + 1)),
      version
    )

  test("a run record or batch record with no recorded digest is refused"):
    val (metaRig, _) = ingested()
    stripAttr(metaRig, "META", "manifestSha256")
    val c1 = counted()
    assertRefused(metaRig, rerunRig(metaRig, c1), c1, World.source)
    val (batchRig, _) = ingested()
    stripAttr(batchRig, "STAGE#batch#paper-abstract-v1#000000", "itemsDigest")
    val c2 = counted()
    assertRefused(batchRig, rerunRig(batchRig, c2), c2, World.source)

  // L4: a template version bump is not a key conflict --------------------------

  test("a template bump rendering identical text needs no key conflict"):
    val (rig, first) = ingested()
    val docsBefore = documentSnapshot(rig)
    val bumpedRig = rerunRig(rig, renderer = BumpedRenderer)
    val second = bumpedRig.ingest(World.source, "2")
    assertEquals(second.rejected, Vector.empty)
    assertEquals(second.failedBatches, Vector.empty)
    assertEquals(second.written, 0)
    assertEquals(second.skipped, mapped)
    assertEquals(documentSnapshot(rig), docsBefore, "no unreferenced document")
    // Stored vectors keep their document; the template difference is drift.
    assertEquals(second.metadataDrift, mapped)
    assert(second.metadataDriftKeys.forall(_.contains("textRef")))
    allVectors(rig).foreach { case (_, rec) => fetch(rig, rec) }
    assertEquals(first.written, mapped)

  test("a template bump over a shared object store gives distinct documents"):
    val (rig, _) = ingested()
    val keysBefore = rig.store.list(SemanticDocuments.prefix).map(_.key).toSet
    val fresh = new Rig(renderer = BumpedRenderer, store = rig.store)
    val report = fresh.ingest(World.source)
    assertEquals(report.rejected, Vector.empty)
    assertEquals(report.written, mapped)
    val keysAfter = rig.store.list(SemanticDocuments.prefix).map(_.key).toSet
    assertEquals(keysAfter.size, 2 * mapped)
    assertEquals(keysBefore.subsetOf(keysAfter), true)
    allVectors(fresh).foreach { case (_, rec) =>
      assert(fetch(fresh, rec).templateVersion.endsWith("-bumped"))
    }

  test("document keys are deterministic and the textRef fits 200 characters"):
    val (rig, _) = ingested()
    val hash = sha("some text")
    val a = SemanticDocuments.key(runId, "summary:v1:1", hash, "t/1")
    assertEquals(SemanticDocuments.key(runId, "summary:v1:1", hash, "t/1"), a)
    assertNotEquals(
      SemanticDocuments.key(runId, "summary:v1:1", hash, "t/2"),
      a
    )
    assertNotEquals(
      SemanticDocuments.key(runId, "summary:v1:2", hash, "t/1"),
      a
    )
    assertNotEquals(
      SemanticDocuments.key(runId, "summary:v1:1", sha("other"), "t/1"),
      a
    )
    val huge = "template/" + ("v" * 5000)
    val long = SemanticDocuments.key(runId, "s" * 5000, hash, huge)
    assert(long.length <= 200, s"${long.length} characters")
    assert(a.length <= 200)
    val bumped = new Rig(renderer = BumpedRenderer)
    bumped.ingest(World.source)
    (allVectors(rig) ++ allVectors(bumped)).foreach { case (_, rec) =>
      val ref = metaStr(rec.metadata, "textRef").get
      assert(ref.length <= 200, s"${ref.length}: $ref")
    }
    // Two independent runs agree on every document key.
    val again = new Rig(renderer = BumpedRenderer)
    again.ingest(World.source)
    assertEquals(
      bumped.store.list(SemanticDocuments.prefix).map(_.key),
      again.store.list(SemanticDocuments.prefix).map(_.key)
    )

  // L6: facet changes under an unchanged text hash are reported as drift -------

  test("a changed facet is not rewritten and is counted as metadata drift"):
    val (rig, first) = ingested()
    assertEquals(first.metadataDrift, 0)
    val vectors = allVectors(rig)
    val objects = rig.store.snapshot
    val puts = rig.bucket.putCalls
    val workABefore = vectorOf(rig, "workA").metadata
    val nodes = World.nodesWork.map { r =>
      if r("canonicalWorkId").str == World.workA then
        r.updated("source", s("crossref")).updated("year", n(2017))
      else r
    }
    val metrics = World.metrics.map { r =>
      if r("researcherId").str == World.ada && r("window").str == window1 then
        r.updated("communityId", s(World.comm2))
      else r
    }
    val changed = new InMemoryTableSource(
      World.tables
        .updated("nodes-work", nodes)
        .updated("researcher-metrics", metrics)
    )
    val second = rig.ingest(changed, "2")
    assertEquals(second.written, 0)
    assertEquals(second.rejected, Vector.empty)
    assertEquals(second.skipped, mapped)
    assert(second.metadataDrift >= 2, second.metadataDriftKeys.toString)
    assertEquals(second.metadataDrift, second.metadataDriftKeys.size)
    val drifted = second.metadataDriftKeys
    def entry(label: String): String =
      drifted
        .find(_.startsWith(World.idOf(label)))
        .getOrElse(fail(drifted.toString))
    assert(entry("workA").contains("source"), entry("workA"))
    assert(entry("workA").contains("year"), entry("workA"))
    assert(entry("adaW1").contains("communityId"), entry("adaW1"))
    assertEquals(rig.bucket.putCalls, puts, "nothing was written")
    assertEquals(allVectors(rig), vectors, "nothing was overwritten")
    assertEquals(rig.store.snapshot, objects)
    val summary =
      rig.table.get(s"RUN#${second.executionId}", "STAGE#summary").get
    assertEquals(summary("metadataDrift").num.toInt, second.metadataDrift)
    assertEquals(
      second.perIndex.map(_.metadataDrift).sum,
      second.metadataDrift
    )
    // The stored facet is still the original one.
    assertEquals(vectorOf(rig, "workA").metadata, workABefore)
    assert(
      !workABefore.values.exists {
        case MetaValue.Str(v)   => v == "crossref"
        case MetaValue.Strs(vs) => vs.contains("crossref")
        case _                  => false
      },
      "the new source value never reached a stored vector"
    )

  test("source row order does not change the manifest, objects or vectors"):
    val a = new Rig()
    val b = new Rig()
    val ra = a.ingest(World.source)
    val rb = b.ingest(source(World.summaries.reverse))
    assertEquals(rb.manifestKey, ra.manifestKey)
    assertEquals(rb.manifestSha256, ra.manifestSha256)
    assertEquals(b.store.snapshot, a.store.snapshot)
    assertEquals(allVectors(b).toSet, allVectors(a).toSet)

  test("5,200 rows go out in batches of at most 500 and all resolve"):
    val rows = (0 until 5200).map { i =>
      val work = id("work", s"bulk$i")
      summaryRow(
        Spec(
          s"bulk$i",
          "work",
          None,
          work,
          s"Work number $i about topic${i % 37} with token${i % 1013}.",
          Seq(work),
          Seq("teamSize"),
          Seq(id("researcher", s"bulk$i"))
        )
      )
    }.toVector
    val rig = new Rig()
    val sizes = scala.collection.mutable.ArrayBuffer.empty[Int]
    rig.bucket.onPut = (_, _, batch) => sizes += batch.size
    val report = rig.ingest(source(rows))
    assertEquals(report.written, 5200)
    assertEquals(report.rejected, Vector.empty)
    assert(sizes.forall(_ <= 500), sizes.toString)
    assertEquals(sizes.sum, 5200)
    assertEquals(sizes.size, 11)
    assertEquals(
      report.perIndex.find(_.index == VectorIndexes.PaperAbstract).get.batches,
      11
    )
    assertEquals(rig.bucket.count(VectorIndexes.PaperAbstract), 5200)
    assertEquals(rig.store.list(SemanticDocuments.prefix).size, 5200)
    val sample = rig.vectors(VectorIndexes.PaperAbstract)
    assertEquals(sample.size, 5200)
    sample.take(50).foreach(fetch(rig, _))
    val again = rig.ingest(source(rows), "2")
    assertEquals(again.written, 0)
    assertEquals(again.skipped, 5200)
    assertEquals(sizes.size, 11, "a rerun makes no further PutVectors call")

  test("batch size bounds are enforced before any write"):
    val rig = new Rig()
    assertEquals(
      code(rig.ingest(World.source, "b0", batch = 0)),
      ErrorCode.BoundExceeded
    )
    assertEquals(
      code(rig.ingest(World.source, "b501", batch = 501)),
      ErrorCode.BoundExceeded
    )
    assertEquals(rig.bucket.putCalls, 0)

  extension (report: IngestReport)
    private def skippedResumedTotal: Int =
      report.perIndex.map(_.skippedResumed).sum

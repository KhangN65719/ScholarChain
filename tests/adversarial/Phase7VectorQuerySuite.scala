package scholarchain.tests.adversarial

import munit.FunSuite
import scholarchain.core.Provenance
import scholarchain.storage.*
import Phase7Storage.*

/** Independent checks of the S3 Vectors query port (decision D13 ruling 1),
  * written from the specification: the filter fields and the metadata key each
  * reads, the year-window containment rule, the topK bound, the ordering and
  * tie rule, refusal of unknown or malformed filters, absent keys, compact
  * metadata, and decision D7 (a changed vector under an existing key stays
  * refused).
  */
class Phase7VectorQuerySuite extends FunSuite:

  private def key(i: Int) = f"summary:v1:$i%064x"
  private val k1 = key(1)
  private val k2 = key(2)
  private val k3 = key(3)
  private val k4 = key(4)
  private val k5 = key(5)
  private val k6 = key(6)
  private val k7 = key(7)

  private def md(
      subject: String,
      summaryType: String,
      extra: (String, MetaValue)*
  ): Map[String, MetaValue] =
    Map(
      "summaryType" -> MetaValue.Str(summaryType),
      "subjectId" -> MetaValue.Str(subject),
      "textRef" -> MetaValue.Str(s"semantic-context/x#$subject"),
      "runId" -> MetaValue.Str(runId)
    ) ++ extra

  private def str(v: String): MetaValue = MetaValue.Str(v)
  private def num(v: Double): MetaValue = MetaValue.Num(v)
  private def strs(vs: String*): MetaValue = MetaValue.Strs(vs.toVector)

  private def vec(k: String, x: Float, y: Float, m: Map[String, MetaValue]) =
    VectorRecord(k, Vector(x, y), m)

  /** Seven vectors; the euclidean distance from (1, 0) ranks them k1, k2, k4,
    * k6, k5, k7, k3 (0, 0.141, 0.707, 0.762, 0.825, 1.273, 1.414).
    */
  private val records = Vector(
    vec(
      k1,
      1f,
      0f,
      md(
        r1,
        "researcher-profile",
        "windowStart" -> num(2019),
        "windowEnd" -> num(2023),
        "documentType" -> str("researcher-profile"),
        "source" -> strs("dblp"),
        "communityId" -> str("c1"),
        "evidenceNodeIds" -> strs(r2)
      )
    ),
    vec(
      k2,
      0.9f,
      0.1f,
      md(
        r2,
        "researcher-profile",
        "windowStart" -> num(2015),
        "windowEnd" -> num(2018),
        "documentType" -> str("researcher-profile"),
        "source" -> strs("openalex"),
        "communityId" -> str("c2"),
        "topic" -> strs("t1"),
        "institution" -> strs("i1")
      )
    ),
    vec(
      k3,
      0f,
      1f,
      md(
        w1,
        "work",
        "year" -> num(2020),
        "documentType" -> str("work"),
        "source" -> strs("dblp", "openalex"),
        "evidenceNodeIds" -> strs(r1, r2)
      )
    ),
    vec(
      k4,
      0.5f,
      0.5f,
      md(
        "community:x",
        "community",
        "windowStart" -> num(1),
        "windowEnd" -> num(9999),
        "documentType" -> str("community"),
        "communityId" -> str("community:x"),
        "evidenceNodeIds" -> strs(r1)
      )
    ),
    // Only the required keys: every optional key is absent.
    vec(k5, 0.2f, 0.2f, md("w9", "collaboration-path")),
    // A start without an end, and no year: it has no usable year at all.
    vec(k6, 0.3f, 0.3f, md("w8", "work", "windowStart" -> num(2020))),
    // Empty source and topic lists. S3 Vectors refuses an empty array (S run
    // 7), so an empty list is an absent key and an empty `Strs` is rejected on
    // write (see "an empty list is refused ..."). The other list key is set,
    // so a list on one key must not leak into the absent ones.
    vec(
      k7,
      0.1f,
      0.9f,
      md("w7", "work", "institution" -> strs("i9"))
    )
  )

  private val all = Vector(k1, k2, k3, k4, k5, k6, k7)
  private val ranked = Vector(k1, k2, k4, k6, k5, k7, k3)

  private def loaded(
      metric: String = "euclidean",
      recs: Vector[VectorRecord] = records
  ): (InMemoryVectorBucket, String) =
    val bucket = new InMemoryVectorBucket
    val name = "idx-query"
    bucket.ensureIndex(VectorIndexSpec(name, 2, metric, None))
    bucket.putVectors(name, recs)
    (bucket, name)

  private def ask(
      filter: VectorFilter = VectorFilter.empty,
      topK: Int = 100,
      embedding: Vector[Float] = Vector(1f, 0f),
      metric: String = "euclidean"
  ): Vector[String] =
    val (bucket, name) = loaded(metric)
    bucket.query(VectorQuery(name, embedding, topK, filter)).map(_.key)

  private def keysFor(filter: VectorFilter): Set[String] =
    ask(filter).toSet

  // Each filter key on its own ---------------------------------------------

  test("researcher reads subjectId and evidenceNodeIds, nothing else"):
    assertEquals(keysFor(VectorFilter(researcher = Some(r1))), Set(k1, k3, k4))
    assertEquals(keysFor(VectorFilter(researcher = Some(r2))), Set(k1, k2, k3))
    assertEquals(keysFor(VectorFilter(researcher = Some(w1))), Set(k3))
    assertEquals(
      keysFor(VectorFilter(researcher = Some("community:x"))),
      Set(k4)
    )
    assertEquals(
      keysFor(VectorFilter(researcher = Some("nobody"))),
      Set.empty[String]
    )
    // not the community, source, topic or document type
    Vector("c1", "dblp", "t1", "work").foreach { v =>
      assertEquals(
        keysFor(VectorFilter(researcher = Some(v))),
        Set.empty[String],
        v
      )
    }

  test("source, topic and institution match a member of their own list"):
    assertEquals(keysFor(VectorFilter(source = Some("dblp"))), Set(k1, k3))
    assertEquals(keysFor(VectorFilter(source = Some("openalex"))), Set(k2, k3))
    assertEquals(
      keysFor(VectorFilter(source = Some("arxiv"))),
      Set.empty[String]
    )
    assertEquals(keysFor(VectorFilter(topic = Some("t1"))), Set(k2))
    assertEquals(keysFor(VectorFilter(institution = Some("i1"))), Set(k2))
    // a partial value is not a match
    assertEquals(keysFor(VectorFilter(source = Some("dbl"))), Set.empty[String])
    assertEquals(
      keysFor(VectorFilter(source = Some("DBLP"))),
      Set.empty[String]
    )
    // lists are not interchangeable
    assertEquals(keysFor(VectorFilter(topic = Some("dblp"))), Set.empty[String])
    assertEquals(keysFor(VectorFilter(source = Some("t1"))), Set.empty[String])
    assertEquals(
      keysFor(VectorFilter(institution = Some("t1"))),
      Set.empty[String]
    )

  test(
    "community reads communityId only; documentType reads documentType only"
  ):
    assertEquals(keysFor(VectorFilter(community = Some("c1"))), Set(k1))
    assertEquals(keysFor(VectorFilter(community = Some("c2"))), Set(k2))
    assertEquals(
      keysFor(VectorFilter(community = Some("community:x"))),
      Set(k4)
    )
    // k1's subject is r1 but its community is c1
    assertEquals(keysFor(VectorFilter(community = Some(r1))), Set.empty[String])
    assertEquals(
      keysFor(VectorFilter(documentType = Some("researcher-profile"))),
      Set(k1, k2)
    )
    assertEquals(keysFor(VectorFilter(documentType = Some("work"))), Set(k3))
    assertEquals(
      keysFor(VectorFilter(documentType = Some("community"))),
      Set(k4)
    )
    // k5, k6 and k7 have summaryType work or collaboration-path but no
    // documentType key, so they never match it
    assertEquals(
      keysFor(VectorFilter(documentType = Some("collaboration-path"))),
      Set.empty[String]
    )
    assertEquals(
      keysFor(VectorFilter(documentType = Some("community:x"))),
      Set.empty[String]
    )

  test("the empty filter matches every vector"):
    assertEquals(keysFor(VectorFilter.empty), all.toSet)
    assert(VectorFilter.empty.isEmpty)
    assertEquals(VectorFilter.empty.constraintCount, 0)

  // Combinations -----------------------------------------------------------

  test("constraints combine with AND"):
    assertEquals(
      keysFor(
        VectorFilter(
          researcher = Some(r2),
          documentType = Some("researcher-profile")
        )
      ),
      Set(k1, k2)
    )
    assertEquals(
      keysFor(
        VectorFilter(
          researcher = Some(r2),
          documentType = Some("researcher-profile"),
          source = Some("dblp")
        )
      ),
      Set(k1)
    )
    assertEquals(
      keysFor(VectorFilter(topic = Some("t1"), institution = Some("i1"))),
      Set(k2)
    )
    assertEquals(
      keysFor(VectorFilter(source = Some("dblp"), yearFrom = Some(2019))),
      Set(k1, k3)
    )

  test("all eight fields together select exactly one vector"):
    val f = VectorFilter(
      researcher = Some(r1),
      yearFrom = Some(2019),
      yearTo = Some(2023),
      source = Some("dblp"),
      community = Some("c1"),
      documentType = Some("researcher-profile")
    )
    assertEquals(f.constraintCount, 5)
    assertEquals(keysFor(f), Set(k1))
    val g = VectorFilter(
      researcher = Some(r2),
      yearFrom = Some(2015),
      yearTo = Some(2018),
      source = Some("openalex"),
      topic = Some("t1"),
      institution = Some("i1"),
      community = Some("c2"),
      documentType = Some("researcher-profile")
    )
    assertEquals(g.constraintCount, 7)
    assertEquals(keysFor(g), Set(k2))

  test("contradictory constraints give no result, not a fallback"):
    assertEquals(
      keysFor(VectorFilter(source = Some("dblp"), community = Some("c2"))),
      Set.empty[String]
    )
    assertEquals(
      keysFor(VectorFilter(documentType = Some("work"), topic = Some("t1"))),
      Set.empty[String]
    )
    assertEquals(
      keysFor(VectorFilter(researcher = Some(r1), community = Some("c2"))),
      Set.empty[String]
    )

  // Year window containment ------------------------------------------------

  private def years(from: Option[Int], to: Option[Int]): Set[String] =
    keysFor(VectorFilter(yearFrom = from, yearTo = to))

  test("a windowed vector matches only a range that contains its window"):
    // k1 = 2019-2023, k2 = 2015-2018, k4 = 1-9999 (all years), k3 year 2020
    assertEquals(years(Some(2019), Some(2023)), Set(k1, k3))
    assertEquals(years(Some(2015), Some(2018)), Set(k2))
    assertEquals(years(Some(2015), Some(2023)), Set(k1, k2, k3))
    assertEquals(years(Some(1), Some(9999)), Set(k1, k2, k3, k4))
    // overlap is not containment
    assertEquals(years(Some(2018), Some(2020)), Set(k3))
    assertEquals(years(Some(2020), Some(2022)), Set(k3))
    assertEquals(years(Some(2016), Some(2018)), Set.empty[String])
    assertEquals(years(Some(2015), Some(2017)), Set.empty[String])
    assertEquals(years(Some(2020), Some(2023)), Set(k3))
    assertEquals(years(Some(2019), Some(2022)), Set(k3))

  test("year bounds are inclusive and either end may be open"):
    assertEquals(years(Some(2020), Some(2020)), Set(k3))
    assertEquals(years(Some(2019), Some(2019)), Set.empty[String])
    assertEquals(years(Some(2021), Some(2021)), Set.empty[String])
    assertEquals(years(Some(2020), None), Set(k3))
    assertEquals(years(None, Some(2022)), Set(k2, k3))
    assertEquals(years(None, Some(2023)), Set(k1, k2, k3))
    assertEquals(years(Some(2024), None), Set.empty[String])
    assertEquals(years(None, Some(2014)), Set.empty[String])
    // the all-years window needs a range that covers it
    assertEquals(years(Some(2), Some(9999)).contains(k4), false)
    assertEquals(years(Some(1), Some(9998)).contains(k4), false)
    assertEquals(years(Some(1), None).contains(k4), true)
    assertEquals(years(None, Some(9999)).contains(k4), true)

  test("a vector with no usable year never matches a year filter"):
    // k5 has no year keys, k6 only a start, k7 neither
    Vector[(Option[Int], Option[Int])](
      (Some(1), Some(9999)),
      (Some(2020), None),
      (None, Some(9999)),
      (Some(2000), Some(2030))
    ).foreach { (from, to) =>
      val hit = years(from, to)
      Vector(k5, k6, k7).foreach(k => assert(!hit.contains(k), s"$k $from $to"))
    }

  // topK ---------------------------------------------------------------------

  test("results are nearest first and equal the hand-computed distances"):
    val (bucket, name) = loaded()
    val hits = bucket.query(VectorQuery(name, Vector(1f, 0f), 100))
    assertEquals(hits.map(_.key), ranked)
    assertEquals(hits.map(_.distance), hits.map(_.distance).sorted)
    def l2(x: Float, y: Float) = math.sqrt(
      math.pow(1.0 - x.toDouble, 2) + math.pow(0.0 - y.toDouble, 2)
    )
    val expected = Map(
      k1 -> l2(1f, 0f),
      k2 -> l2(0.9f, 0.1f),
      k3 -> l2(0f, 1f),
      k4 -> l2(0.5f, 0.5f),
      k5 -> l2(0.2f, 0.2f),
      k6 -> l2(0.3f, 0.3f),
      k7 -> l2(0.1f, 0.9f)
    )
    hits.foreach(h =>
      assertEqualsDouble(h.distance, expected(h.key), 1e-9, h.key)
    )
    // each hit carries its stored metadata
    hits.foreach(h =>
      assertEquals(h.metadata, records.find(_.key == h.key).get.metadata)
    )

  test("cosine ranks by angle: nearest first, orthogonal last"):
    val (bucket, name) = loaded("cosine")
    val hits = bucket.query(VectorQuery(name, Vector(1f, 0f), 100))
    assertEquals(hits.map(_.key).take(2), Vector(k1, k2))
    assertEquals(hits.last.key, k3)
    assertEqualsDouble(hits.head.distance, 0.0, 1e-9)
    assertEqualsDouble(hits.last.distance, 1.0, 1e-9)
    assertEquals(hits.map(_.distance), hits.map(_.distance).sorted)

  test("topK limits the result and filters apply before the cut"):
    assertEquals(ask(topK = 1), Vector(k1))
    assertEquals(ask(topK = 3), ranked.take(3))
    assertEquals(ask(topK = 7), ranked)
    assertEquals(ask(topK = 100), ranked)
    assertEquals(ask(topK = 10000), ranked)
    // k3 is the farthest vector, yet it is the answer to its own filter
    assertEquals(
      ask(VectorFilter(documentType = Some("work")), topK = 1),
      Vector(k3)
    )
    assertEquals(
      ask(VectorFilter(source = Some("openalex")), topK = 1),
      Vector(k2)
    )
    assertEquals(
      ask(VectorFilter(source = Some("dblp")), topK = 5),
      Vector(k1, k3)
    )
    assertEquals(
      ask(VectorFilter(source = Some("arxiv")), topK = 5),
      Vector.empty
    )

  test("topK outside 1 to 10,000 is refused"):
    Vector(0, -1, Int.MinValue).foreach { k =>
      assertEquals(
        code(VectorQuery("i", Vector(1f), k)),
        ErrorCode.InvalidQuery,
        k.toString
      )
    }
    Vector(10001, 20000, Int.MaxValue).foreach { k =>
      assertEquals(
        code(VectorQuery("i", Vector(1f), k)),
        ErrorCode.BoundExceeded,
        k.toString
      )
    }
    VectorQuery("i", Vector(1f), 1)
    VectorQuery("i", Vector(1f), 10000)

  test("a query is refused on its own malformed parts"):
    val (bucket, name) = loaded()
    assertEquals(
      code(bucket.query(VectorQuery("missing", Vector(1f, 0f), 5))),
      ErrorCode.InvalidKey
    )
    assertEquals(
      code(bucket.query(VectorQuery(name, Vector(1f, 0f, 0f), 5))),
      ErrorCode.EmbeddingInvalid
    )
    assertEquals(
      code(bucket.query(VectorQuery(name, Vector(1f), 5))),
      ErrorCode.EmbeddingInvalid
    )
    Vector(
      Vector.empty[Float],
      Vector(Float.NaN, 0f),
      Vector(Float.PositiveInfinity, 0f),
      Vector(0f, Float.NegativeInfinity)
    ).foreach { e =>
      assertEquals(code(VectorQuery(name, e, 5)), ErrorCode.EmbeddingInvalid)
    }
    Vector("", " ", "\t").foreach { blank =>
      assertEquals(
        code(VectorQuery(blank, Vector(1f), 5)),
        ErrorCode.InvalidQuery
      )
    }
    // a zero vector has no direction under cosine
    val (cosine, cname) = loaded("cosine")
    assertEquals(
      code(cosine.query(VectorQuery(cname, Vector(0f, 0f), 5))),
      ErrorCode.InvalidQuery
    )
    // but it is a real point under euclidean
    assertEquals(
      bucket.query(VectorQuery(name, Vector(0f, 0f), 1)).map(_.key).size,
      1
    )

  test("an empty index answers with nothing, not an error"):
    val bucket = new InMemoryVectorBucket
    bucket.ensureIndex(VectorIndexSpec("empty", 2, "cosine", None))
    assertEquals(
      bucket.query(VectorQuery("empty", Vector(1f, 0f), 10)),
      Vector.empty
    )

  // Ties ---------------------------------------------------------------------

  test(
    "equal distances are ordered by ascending key, whatever the insert order"
  ):
    val same = (1 to 6).toVector
    def build(order: Vector[Int]) =
      val bucket = new InMemoryVectorBucket
      bucket.ensureIndex(VectorIndexSpec("t", 2, "cosine", None))
      order.foreach(i =>
        bucket.putVectors("t", Vector(vec(key(i), 1f, 1f, md(s"s$i", "work"))))
      )
      bucket
    val orders = Vector(
      same,
      same.reverse,
      Vector(4, 2, 6, 1, 5, 3),
      Vector(3, 6, 1, 4, 2, 5)
    )
    orders.foreach { order =>
      val bucket = build(order)
      val hits = bucket.query(VectorQuery("t", Vector(1f, 1f), 10)).map(_.key)
      assertEquals(hits, same.map(key), order.toString)
      // repeated queries agree
      assertEquals(
        bucket.query(VectorQuery("t", Vector(1f, 1f), 10)).map(_.key),
        hits
      )
      // a cut through the tie keeps the lowest keys
      assertEquals(
        bucket.query(VectorQuery("t", Vector(1f, 1f), 3)).map(_.key),
        same.take(3).map(key)
      )
    }

  test("a tie across a filtered cut is still ordered by key"):
    val bucket = new InMemoryVectorBucket
    bucket.ensureIndex(VectorIndexSpec("t", 2, "euclidean", None))
    val recs = Vector(5, 3, 9, 1, 7).map(i =>
      vec(key(i), 0.5f, 0.5f, md(s"s$i", "work", "documentType" -> str("work")))
    ) :+ vec(
      key(2),
      0.5f,
      0.5f,
      md("s2", "work", "documentType" -> str("other"))
    )
    bucket.putVectors("t", recs)
    val hits = bucket
      .query(
        VectorQuery(
          "t",
          Vector(0f, 0f),
          3,
          VectorFilter(documentType = Some("work"))
        )
      )
      .map(_.key)
    assertEquals(hits, Vector(key(1), key(3), key(5)))

  // Unknown and malformed filters ------------------------------------------

  test("an unknown filter key is refused by name, never ignored"):
    Vector(
      "author",
      "Researcher",
      "year",
      "yearfrom",
      "",
      "documentType ",
      "topK"
    )
      .foreach { bad =>
        val e = intercept[StorageException](
          VectorFilter.parse(ujson.Obj(bad -> "x"))
        )
        assertEquals(e.code, ErrorCode.UnknownFilterKey, bad)
      }
    val mixed = intercept[StorageException](
      VectorFilter.parse(ujson.Obj("researcher" -> r1, "bogus" -> 1))
    )
    assertEquals(mixed.code, ErrorCode.UnknownFilterKey)
    assert(mixed.getMessage.contains("bogus"), mixed.getMessage)
    assertEquals(
      VectorFilter.Keys,
      Set(
        "researcher",
        "yearFrom",
        "yearTo",
        "source",
        "topic",
        "institution",
        "community",
        "documentType"
      )
    )

  test("a filter that is not a JSON object is refused"):
    Vector(
      ujson.Arr(),
      ujson.Arr(ujson.Obj("researcher" -> r1)),
      ujson.Str("researcher"),
      ujson.Num(1),
      ujson.Bool(true),
      ujson.Null
    ).foreach { bad =>
      assertEquals(
        code(VectorFilter.parse(bad)),
        ErrorCode.InvalidFilter,
        bad.toString
      )
    }

  test("values of the wrong type or range are refused, null included"):
    val texts = Vector(
      "researcher",
      "source",
      "topic",
      "institution",
      "community",
      "documentType"
    )
    texts.foreach { name =>
      Vector(
        ujson.Num(1),
        ujson.Bool(true),
        ujson.Null,
        ujson.Arr("a"),
        ujson.Obj("a" -> "b")
      ).foreach { v =>
        assertEquals(
          code(VectorFilter.parse(ujson.Obj(name -> v))),
          ErrorCode.InvalidFilter,
          s"$name $v"
        )
      }
    }
    Vector("yearFrom", "yearTo").foreach { name =>
      Vector(
        ujson.Str("2020"),
        ujson.Num(2020.5),
        ujson.Num(Double.NaN),
        ujson.Bool(true),
        ujson.Null,
        ujson.Arr(2020),
        ujson.Num(0),
        ujson.Num(-5),
        ujson.Num(10000),
        ujson.Num(1e12)
      ).foreach { v =>
        assertEquals(
          code(VectorFilter.parse(ujson.Obj(name -> v))),
          ErrorCode.InvalidFilter,
          s"$name $v"
        )
      }
    }
    assertEquals(
      code(VectorFilter.parse(ujson.Obj("yearFrom" -> 2023, "yearTo" -> 2019))),
      ErrorCode.InvalidFilter
    )
    // a whole number written as 2020.0 is a whole number
    assertEquals(
      VectorFilter.parse(ujson.Obj("yearFrom" -> ujson.Num(2020.0))).yearFrom,
      Some(2020)
    )

  test("string values must be nonblank, trimmed, control-free and short"):
    Vector(
      "researcher",
      "source",
      "topic",
      "institution",
      "community",
      "documentType"
    )
      .foreach { name =>
        Vector("", " ", " x", "x ", "a\nb", "a\tb", "x" * 201).foreach { bad =>
          assertEquals(
            code(VectorFilter.parse(ujson.Obj(name -> bad))),
            ErrorCode.InvalidFilter,
            s"$name '${bad.take(10)}'"
          )
        }
        assertEquals(
          code(VectorFilter.parse(ujson.Obj(name -> "a\u0000b"))),
          ErrorCode.NullCharacter,
          name
        )
        VectorFilter.parse(ujson.Obj(name -> ("x" * 200)))
      }

  test("the direct constructor enforces the same rules"):
    Vector(0, -1, 10000, Int.MaxValue).foreach { y =>
      assertEquals(
        code(VectorFilter(yearFrom = Some(y))),
        ErrorCode.InvalidFilter
      )
      assertEquals(
        code(VectorFilter(yearTo = Some(y))),
        ErrorCode.InvalidFilter
      )
    }
    assertEquals(
      code(VectorFilter(yearFrom = Some(2020), yearTo = Some(2019))),
      ErrorCode.InvalidFilter
    )
    VectorFilter(yearFrom = Some(2020), yearTo = Some(2020))
    VectorFilter(yearFrom = Some(1), yearTo = Some(9999))
    assertEquals(code(VectorFilter(source = Some(""))), ErrorCode.InvalidFilter)

  test("a valid JSON filter equals the constructed one and queries the same"):
    val json = ujson.Obj(
      "researcher" -> r1,
      "yearFrom" -> 2019,
      "yearTo" -> 2023,
      "source" -> "dblp",
      "documentType" -> "researcher-profile",
      "community" -> "c1"
    )
    val parsed = VectorFilter.parse(json)
    assertEquals(
      parsed,
      VectorFilter(
        researcher = Some(r1),
        yearFrom = Some(2019),
        yearTo = Some(2023),
        source = Some("dblp"),
        community = Some("c1"),
        documentType = Some("researcher-profile")
      )
    )
    assertEquals(keysFor(parsed), Set(k1))
    assertEquals(VectorFilter.parse(ujson.Obj()), VectorFilter.empty)
    assertEquals(keysFor(VectorFilter.parse(ujson.Obj())), all.toSet)

  test("hostile filter values match nothing and never act as patterns"):
    Vector(
      "x' OR 1=1 --",
      "*",
      ".*",
      "%",
      "$ne",
      "{\"$gt\": \"\"}",
      "dblp OR openalex",
      "dblp,openalex"
    ).foreach { v =>
      Vector(
        VectorFilter(researcher = Some(v)),
        VectorFilter(source = Some(v)),
        VectorFilter(topic = Some(v)),
        VectorFilter(institution = Some(v)),
        VectorFilter(community = Some(v)),
        VectorFilter(documentType = Some(v))
      ).foreach(f => assertEquals(keysFor(f), Set.empty[String], v))
    }

  // Absent optional keys never match --------------------------------------

  test("a vector with only the required keys matches no facet filter"):
    // k5 (subject w9) has no optional key at all
    Vector(
      VectorFilter(source = Some("dblp")),
      VectorFilter(topic = Some("t1")),
      VectorFilter(institution = Some("i1")),
      VectorFilter(community = Some("c1")),
      VectorFilter(documentType = Some("work")),
      VectorFilter(documentType = Some("collaboration-path")),
      VectorFilter(yearFrom = Some(1), yearTo = Some(9999)),
      VectorFilter(yearFrom = Some(1)),
      VectorFilter(yearTo = Some(9999))
    ).foreach(f => assert(!keysFor(f).contains(k5), f.toString))
    assertEquals(keysFor(VectorFilter(researcher = Some("w9"))), Set(k5))
    assertEquals(
      keysFor(
        VectorFilter(researcher = Some("w9"), documentType = Some("work"))
      ),
      Set.empty[String]
    )

  test("an absent list key reads as empty: it never matches, no wildcard"):
    // k7 has no source or topic key, which is how an empty list is stored
    Vector(
      VectorFilter(source = Some("dblp")),
      VectorFilter(source = Some("openalex")),
      VectorFilter(topic = Some("t1"))
    ).foreach(f => assert(!keysFor(f).contains(k7), f.toString))
    // only k2 has a topic; k2 and k7 each have their own institution
    assertEquals(keysFor(VectorFilter(topic = Some("t1"))), Set(k2))
    assertEquals(keysFor(VectorFilter(institution = Some("i1"))), Set(k2))
    assertEquals(keysFor(VectorFilter(institution = Some("i9"))), Set(k7))
    // a list under one key is not read as a list under another
    assertEquals(keysFor(VectorFilter(source = Some("i9"))), Set.empty[String])
    assertEquals(keysFor(VectorFilter(topic = Some("i9"))), Set.empty[String])

  test("an empty list is refused on every list key; absent is the empty list"):
    val listKeys =
      Vector(
        "source",
        "topic",
        "institution",
        "evidenceWorkIds",
        "evidenceNodeIds"
      )
    val rule =
      "S3 Vectors rejects empty arrays; confirmed on the real service in S run 7"
    listKeys.foreach { k =>
      // the in-process validator: MetadataInvalid, naming the key and the rule
      val e = intercept[StorageException](
        VectorMetadata.validate(md("w7", "work", k -> strs()))
      )
      assertEquals(e.code, ErrorCode.MetadataInvalid, k)
      assert(e.getMessage.contains(k), e.getMessage)
      assert(e.getMessage.contains(rule), e.getMessage)
      // a one-element list and an absent key are both fine
      VectorMetadata.validate(md("w7", "work", k -> strs("x")))
      VectorMetadata.validate(md("w7", "work"))
      // the wire rules refuse the same shape on the SDK Document, whatever the
      // in-process check says
      val wire = intercept[StorageException](
        S3VectorsRules.check(
          key(7),
          VectorDocuments.metadata(md("w7", "work", k -> strs())),
          AwsVectorBucket.NonFilterableKeys
        )
      )
      assertEquals(wire.code, ErrorCode.AwsRejected, k)
      assert(wire.getMessage.contains(S3VectorsRules.EmptyArrayMessage), k)
      assert(wire.getMessage.contains(k), wire.getMessage)
    }
    // the fake refuses the record and stores nothing
    val (bucket, name) = loaded(recs = Vector(records.head))
    val before = bucket.count(name)
    listKeys.foreach { k =>
      assertEquals(
        code(
          bucket.putVectors(
            name,
            Vector(vec(k7, 0.1f, 0.9f, md("w7", "work", k -> strs())))
          )
        ),
        ErrorCode.MetadataInvalid,
        k
      )
    }
    assertEquals(bucket.count(name), before)
    // the writer rejects only the bad record and writes the good one
    val spec = VectorIndexSpec("idx-writer", 2, "euclidean", None)
    val report = new VectorWriter(
      new InMemoryVectorBucket,
      RetryPolicy(1, 0, 0),
      NoSleep
    ).write(
      spec,
      Vector(
        vec(k6, 0.3f, 0.3f, md("w8", "work")),
        vec(k7, 0.1f, 0.9f, md("w7", "work", "topic" -> strs()))
      )
    )
    assertEquals(report.written, 1)
    assertEquals(
      report.rejected.map(r => (r.key, r.code)),
      Vector((k7, ErrorCode.MetadataInvalid))
    )

  test("no vector set by the loader carries topic or institution"):
    val facets = SummaryFacets(
      Map(w1 -> 2020),
      Map(w1 -> "dblp"),
      Map((r1, windowLabel) -> "c1")
    )
    val rows = Vector(
      summaryRow(key(1), "work", w1, Seq(w1)),
      summaryRow(key(2), "researcher-profile", r1, Seq(w1)),
      summaryRow(key(3), "community", "community:x", Seq(w1)),
      summaryRow(key(4), "collaboration-path", "path:x", Seq(w1))
    )
    rows.foreach { row =>
      val r = SemanticVectorLoader.record(row, Model, "ref", facets)
      assert(!r.metadata.contains("topic"), r.key)
      assert(!r.metadata.contains("institution"), r.key)
    }

  // Metadata stays compact ------------------------------------------------

  private val base = md(w1, "work")

  test("facet keys are accepted only with their fixed value shape"):
    def bad(k: String, v: MetaValue) =
      assertEquals(
        code(VectorMetadata.validate(base + (k -> v))),
        ErrorCode.MetadataInvalid,
        s"$k $v"
      )
    // year: a whole number from 1 to 9999
    bad("year", str("2020"))
    bad("year", num(0))
    bad("year", num(10000))
    bad("year", num(2020.5))
    bad("year", num(-1))
    bad("year", num(Double.NaN))
    bad("year", strs("2020"))
    // documentType and communityId: one short string
    bad("documentType", strs("work"))
    bad("communityId", strs("c1"))
    bad("documentType", num(1))
    bad("communityId", MetaValue.Flag(true))
    // source, topic, institution: a list of short references
    bad("source", str("dblp"))
    bad("topic", str("t1"))
    bad("institution", num(1))
    // the length and prose rules still hold for each
    bad("documentType", str("x" * 201))
    bad("communityId", str("a b c d e f g h i j k l m"))
    bad("documentType", str("line one\nline two"))
    Vector("source", "topic", "institution").foreach { k =>
      bad(k, strs("a", "b", "c", "d", "e", "f"))
      bad(k, strs("x" * 201))
      bad(
        k,
        strs(
          "one two three four five six seven eight nine ten eleven twelve thirteen"
        )
      )
      bad(k, strs("fine", "two\nlines"))
    }
    assertEquals(
      code(VectorMetadata.validate(base + ("communityId" -> str("a\u0000b")))),
      ErrorCode.NullCharacter
    )
    // an empty list is no longer a good shape: the service refuses it
    Vector("source", "topic", "institution").foreach(k => bad(k, strs()))
    // the good shapes pass; an empty institution list is the absent key
    VectorMetadata.validate(
      base ++ Map(
        "year" -> num(2020),
        "documentType" -> str("work"),
        "communityId" -> str("community:x"),
        "source" -> strs("a", "b", "c", "d", "e"),
        "topic" -> strs("t")
      )
    )
    VectorMetadata.validate(base + ("institution" -> strs("i")))
    VectorMetadata.validate(base + ("year" -> num(1)))
    VectorMetadata.validate(base + ("year" -> num(9999)))

  test("the facets cannot smuggle text: reserved text keys stay refused"):
    VectorMetadata.TextKeys.foreach { k =>
      assertEquals(
        code(VectorMetadata.validate(base + (k -> str("short")))),
        ErrorCode.MetadataInvalid,
        k
      )
    }
    Vector("documentType", "communityId").foreach { k =>
      assert(VectorMetadata.Allowed.contains(k))
      assert(!VectorMetadata.TextKeys.contains(k))
    }

  test("the 2 KB and 50-key limits still bound the new facets"):
    val wide = Vector("source", "topic", "institution").map(k =>
      k -> strs((1 to 5).map(i => s"$k-$i-" + ("x" * 170))*)
    )
    assertEquals(
      code(VectorMetadata.validate(base ++ wide)),
      ErrorCode.MetadataInvalid
    )
    // one list of five long references stays within 2 KB
    VectorMetadata.validate(base + wide.head)

  // Loader output, end to end ---------------------------------------------

  private object Model extends Embedder:
    val modelVersion = "test-embed-1"
    val dimension = 3
    def embed(text: String): Vector[Float] =
      Vector(text.length.toFloat % 7 + 1f, 1f, 2f)

  private def summaryRow(
      id: String,
      kind: String,
      subject: String,
      workIds: Seq[String],
      text: String = "A long summary. " * 400,
      p: Provenance = provenance(),
      windowed: Boolean = true
  ): Row =
    val common: Row = Map(
      "summaryId" -> s(id),
      "summaryType" -> s(kind),
      "subjectId" -> s(subject),
      "summaryText" -> s(text),
      "evidenceWorkIds" -> arr(workIds*),
      "evidenceNodeIds" -> arr(r1, r2),
      "provenance" -> provJson(p)
    )
    if windowed then
      common ++ Map(
        "window" -> s(windowLabel),
        "windowStart" -> n(2019),
        "windowEnd" -> n(2023)
      )
    else common

  private def meta(r: VectorRecord, k: String): Option[MetaValue] =
    r.metadata.get(k)

  test("the loader sets each facet from its Spark column and nothing more"):
    val facets = SummaryFacets(
      Map(w1 -> 2020, w2 -> 2021),
      Map(w1 -> "dblp", w2 -> "openalex", w3 -> "dblp"),
      Map((r1, windowLabel) -> "c1")
    )
    val work =
      SemanticVectorLoader.record(
        summaryRow(key(1), "work", w1, Seq(w1, w2, w3)),
        Model,
        "ref",
        facets
      )
    assertEquals(meta(work, "documentType"), Some(str("work")))
    assertEquals(meta(work, "year"), Some(num(2020)))
    assertEquals(meta(work, "source"), Some(strs("dblp", "openalex")))
    assertEquals(meta(work, "communityId"), None)
    val profile = SemanticVectorLoader.record(
      summaryRow(key(2), "researcher-profile", r1, Seq(w1)),
      Model,
      "ref",
      facets
    )
    assertEquals(meta(profile, "documentType"), Some(str("researcher-profile")))
    assertEquals(meta(profile, "communityId"), Some(str("c1")))
    assertEquals(meta(profile, "year"), None)
    // no community known for this researcher and window
    val other = SemanticVectorLoader.record(
      summaryRow(key(3), "researcher-profile", r2, Seq(w1)),
      Model,
      "ref",
      facets
    )
    assertEquals(meta(other, "communityId"), None)
    val community = SemanticVectorLoader.record(
      summaryRow(key(4), "community", "community:x", Seq(w1)),
      Model,
      "ref",
      facets
    )
    assertEquals(meta(community, "communityId"), Some(str("community:x")))
    val path = SemanticVectorLoader.record(
      summaryRow(key(5), "collaboration-path", "path:x", Seq(w1)),
      Model,
      "ref",
      facets
    )
    assertEquals(meta(path, "documentType"), Some(str("collaboration-path")))
    assertEquals(meta(path, "communityId"), None)
    // without facets only the summary type facet exists
    val bare = SemanticVectorLoader.record(
      summaryRow(key(6), "work", w1, Seq(w1)),
      Model,
      "ref"
    )
    assertEquals(meta(bare, "documentType"), Some(str("work")))
    Vector("year", "source", "communityId", "topic", "institution").foreach(k =>
      assertEquals(meta(bare, k), None, k)
    )

  test("loader metadata is compact and carries no summary text"):
    val ids = (1 to 8).map(i => workId(i))
    val facets = SummaryFacets(
      Map.empty,
      ids.zipWithIndex.map((id, i) => id -> s"source-$i").toMap,
      Map.empty
    )
    val text = "SECRET-PROSE " + ("lorem ipsum " * 3000)
    val r = SemanticVectorLoader.record(
      summaryRow(key(1), "work", ids.head, ids, text = text),
      Model,
      "ref",
      facets
    )
    VectorMetadata.validate(r.metadata)
    assert(VectorMetadata.sizeBytes(r.metadata) <= 2048)
    assert(r.metadata.size <= 50)
    val dumped = r.metadata.values.map(v => ujson.write(v.json)).mkString
    assert(!dumped.contains("SECRET-PROSE"))
    assert(!dumped.contains("lorem"))
    assert(r.metadata.keySet.intersect(VectorMetadata.TextKeys).isEmpty)
    // eight sources are cut to the five-reference bound
    meta(r, "source") match
      case Some(MetaValue.Strs(v)) => assertEquals(v.size, 5)
      case other                   => fail(s"source was $other")
    r.metadata.values.foreach {
      case MetaValue.Str(v)   => assert(v.length <= 200)
      case MetaValue.Strs(vs) =>
        assert(vs.size <= 5)
        vs.foreach(v => assert(v.length <= 200))
      case _ => ()
    }

  private def workId(i: Int): String =
    scholarchain.core.StableIds.work("dblp", s"rec/q$i")

  test("facets come from the Spark tables and absent tables give none"):
    assertEquals(
      SummaryFacets.fromTables(new InMemoryTableSource(Map.empty), Bounds),
      SummaryFacets.empty
    )
    val tables = new InMemoryTableSource(
      Map(
        "nodes-work" -> Vector(
          workRow(w1, 2020, "t", provenance()) + ("source" -> s("dblp")),
          workRow(w2, 2021, "t", provenance())
        ),
        "researcher-metrics" -> Vector(
          Map(
            "researcherId" -> s(r1),
            "window" -> s(windowLabel),
            "communityId" -> s("c1")
          ),
          Map("researcherId" -> s(r2), "window" -> s(windowLabel))
        )
      )
    )
    val facets = SummaryFacets.fromTables(tables, Bounds)
    assertEquals(facets.workYear, Map(w1 -> 2020, w2 -> 2021))
    assertEquals(facets.workSource, Map(w1 -> "dblp"))
    assertEquals(facets.communityOf, Map((r1, windowLabel) -> "c1"))

  test("loaded summaries are found by their facets and reload is a no-op"):
    val facets = SummaryFacets(
      Map(w1 -> 2020),
      Map(w1 -> "dblp", w2 -> "openalex"),
      Map((r1, windowLabel) -> "c1")
    )
    val rows = Vector(
      summaryRow(key(1), "work", w1, Seq(w1, w2), windowed = false),
      summaryRow(key(2), "researcher-profile", r1, Seq(w1)),
      summaryRow(key(3), "researcher-profile", r2, Seq(w2)),
      summaryRow(key(4), "community", "community:x", Seq(w1))
    )
    val bucket = new InMemoryVectorBucket
    val specs = VectorIndexes.all(3, "cosine")
    val writer = new VectorWriter(bucket, RetryPolicy(2, 1, 1), NoSleep)
    val first = SemanticVectorLoader.load(
      rows,
      specs,
      Model,
      "ref",
      writer,
      Bounds,
      facets
    )
    assertEquals(first.perIndex.map(_.written).sum, 4)
    assertEquals(first.perIndex.flatMap(_.rejected), Vector.empty)
    val q = Vector(1f, 1f, 1f)
    def find(index: String, f: VectorFilter) =
      bucket.query(VectorQuery(index, q, 10, f)).map(_.key)
    assertEquals(
      find("paper-abstract-v1", VectorFilter(source = Some("openalex"))),
      Vector(key(1))
    )
    assertEquals(
      find(
        "paper-abstract-v1",
        VectorFilter(
          yearFrom = Some(2020),
          yearTo = Some(2020),
          source = Some("dblp")
        )
      ),
      Vector(key(1))
    )
    assertEquals(
      find("paper-abstract-v1", VectorFilter(yearFrom = Some(2021))),
      Vector.empty
    )
    assertEquals(
      find("researcher-profile-v1", VectorFilter(community = Some("c1"))),
      Vector(key(2))
    )
    assertEquals(
      find("researcher-profile-v1", VectorFilter(researcher = Some(r2))).toSet,
      Set(key(2), key(3))
    )
    // the profile window is 2019-2023
    assertEquals(
      find(
        "researcher-profile-v1",
        VectorFilter(yearFrom = Some(2019), yearTo = Some(2023))
      ).toSet,
      Set(key(2), key(3))
    )
    assertEquals(
      find("researcher-profile-v1", VectorFilter(yearFrom = Some(2020))),
      Vector.empty
    )
    assertEquals(
      find(
        "collaboration-community-v1",
        VectorFilter(community = Some("community:x"))
      ),
      Vector(key(4))
    )
    assertEquals(
      find("collaboration-community-v1", VectorFilter(topic = Some("any"))),
      Vector.empty
    )
    // reload: nothing new, and the answers do not move
    val second = SemanticVectorLoader.load(
      rows,
      specs,
      Model,
      "ref",
      writer,
      Bounds,
      facets
    )
    assertEquals(second.perIndex.map(_.written).sum, 0)
    assertEquals(second.perIndex.flatMap(_.rejected), Vector.empty)
    assertEquals(
      find("paper-abstract-v1", VectorFilter(source = Some("openalex"))),
      Vector(key(1))
    )

  test("a vector with both a window and a year is matched on its window"):
    val both = vec(
      key(20),
      1f,
      1f,
      md(
        "w20",
        "work",
        "windowStart" -> num(2019),
        "windowEnd" -> num(2023),
        "year" -> num(2020)
      )
    )
    val (bucket, name) = loaded(recs = Vector(both))
    def hit(from: Int, to: Int) =
      bucket
        .query(
          VectorQuery(
            name,
            Vector(1f, 0f),
            5,
            VectorFilter(yearFrom = Some(from), yearTo = Some(to))
          )
        )
        .map(_.key)
    assertEquals(hit(2019, 2023), Vector(key(20)))
    // the year alone would match, the window does not lie inside
    assertEquals(hit(2020, 2020), Vector.empty)
    assertEquals(hit(2020, 2023), Vector.empty)

  // D7 ------------------------------------------------------------------------

  test(
    "D7: a changed embedding or facet under an existing key is still refused"
  ):
    val (bucket, name) = loaded()
    val before = bucket.query(VectorQuery(name, Vector(1f, 0f), 100))
    val original = records.head
    assertEquals(
      code(
        bucket
          .putVectors(name, Vector(original.copy(embedding = Vector(0f, 1f))))
      ),
      ErrorCode.ChangedVectorContent
    )
    assertEquals(
      code(
        bucket.putVectors(
          name,
          Vector(
            original.copy(metadata =
              original.metadata + ("source" -> strs("dblp", "arxiv"))
            )
          )
        )
      ),
      ErrorCode.ChangedVectorContent
    )
    assertEquals(
      code(
        bucket.putVectors(
          name,
          Vector(original.copy(metadata = original.metadata - "communityId"))
        )
      ),
      ErrorCode.ChangedVectorContent
    )
    assertEquals(bucket.query(VectorQuery(name, Vector(1f, 0f), 100)), before)
    assertEquals(
      bucket.query(
        VectorQuery(
          name,
          Vector(1f, 0f),
          5,
          VectorFilter(source = Some("arxiv"))
        )
      ),
      Vector.empty
    )
    // an identical put is accepted and changes nothing
    bucket.putVectors(name, Vector(original))
    assertEquals(bucket.count(name), 7)
    assertEquals(bucket.query(VectorQuery(name, Vector(1f, 0f), 100)), before)

  test(
    "D7: the writer reports the refusal and the query still sees the original"
  ):
    val (bucket, name) = loaded()
    val writer = new VectorWriter(bucket, RetryPolicy(2, 1, 1), NoSleep)
    val spec = VectorIndexSpec(name, 2, "euclidean", None)
    val changed = records.head.copy(
      metadata = records.head.metadata + ("communityId" -> str("c9"))
    )
    val report = writer.write(spec, Vector(changed))
    assertEquals(report.written, 0)
    assertEquals(
      report.rejected.map(r => (r.key, r.code)),
      Vector((k1, ErrorCode.ChangedVectorContent))
    )
    assertEquals(
      bucket
        .query(
          VectorQuery(
            name,
            Vector(1f, 0f),
            5,
            VectorFilter(community = Some("c9"))
          )
        )
        .map(_.key),
      Vector.empty
    )
    assertEquals(
      bucket
        .query(
          VectorQuery(
            name,
            Vector(1f, 0f),
            5,
            VectorFilter(community = Some("c1"))
          )
        )
        .map(_.key),
      Vector(k1)
    )

  test("a query never writes: the stored vectors are the same afterwards"):
    val (bucket, name) = loaded()
    val before = bucket.getVectors(name, all.take(7))
    (1 to 3).foreach { _ =>
      bucket.query(
        VectorQuery(
          name,
          Vector(0.3f, 0.7f),
          4,
          VectorFilter(source = Some("dblp"))
        )
      )
    }
    assertEquals(bucket.getVectors(name, all.take(7)), before)
    assertEquals(bucket.count(name), 7)

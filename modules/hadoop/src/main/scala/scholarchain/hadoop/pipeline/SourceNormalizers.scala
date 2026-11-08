package scholarchain.hadoop.pipeline

import scholarchain.core.{
  AuthorMention,
  CanonicalResearcher,
  ContractSchema,
  Institution,
  SourceType,
  StableIds,
  Topic,
  Venue,
  WorkRecord
}

/** What one external record normalizes to. A normalizer never discards a
  * source-specific identifier: anything it cannot map onto a contract field
  * lands in `externalIds` or in [[identifiers]] so the join stage still sees
  * it.
  */
final case class NormalizedRecord(
    work: Option[WorkRecord] = None,
    researchers: Vector[CanonicalResearcher] = Vector.empty,
    topics: Vector[Topic] = Vector.empty,
    institutions: Vector[Institution] = Vector.empty,
    venues: Vector[Venue] = Vector.empty,
    identifiers: Vector[(IdNamespace, String)] = Vector.empty
)

/** Job 2: one normalizer per selected dataset.
  *
  * Each normalizer is a pure function from one source line to either a reject
  * diagnostic or a [[NormalizedRecord]]. Nothing here consults the filesystem,
  * the clock or a shared mutable structure, so a task can be retried or
  * speculatively executed without changing its output.
  */
object SourceNormalizers:
  /** Reads one JSON line, converting a parse failure into a reject rather than
    * an exception.
    */
  private def readJson(
      line: String
  ): Either[(RejectReason, String), ujson.Obj] =
    try
      ujson.read(line) match
        case obj: ujson.Obj => Right(obj)
        case _              =>
          Left(RejectReason.MalformedJson -> "Record is not a JSON object")
    catch
      case error: Exception =>
        Left(
          RejectReason.MalformedJson -> Option(error.getMessage)
            .getOrElse(error.getClass.getSimpleName)
        )

  private def str(value: ujson.Value): Option[String] =
    value match
      case ujson.Str(text) =>
        Some(TextNormalizer.collapseWhitespace(text)).filter(_.nonEmpty)
      case _ => None

  private def field(obj: ujson.Obj, name: String): Option[ujson.Value] =
    obj.value.get(name).filterNot(_ == ujson.Null)

  private def stringField(obj: ujson.Obj, name: String): Option[String] =
    field(obj, name).flatMap(str)

  private def intField(obj: ujson.Obj, name: String): Option[Int] =
    field(obj, name).collect { case ujson.Num(number) => number.toInt }

  private def arrayField(obj: ujson.Obj, name: String): Vector[ujson.Value] =
    field(obj, name) match
      case Some(ujson.Arr(items)) => items.toVector
      case _                      => Vector.empty

  private def objectsOf(values: Vector[ujson.Value]): Vector[ujson.Obj] =
    values.collect { case obj: ujson.Obj => obj }

  // ---------------------------------------------------------------- OpenAlex

  val openAlexStage = "openalex-normalization"

  /** OpenAlex Works. `authorships` order is the author sequence; OpenAlex also
    * states a coarse `author_position` of first/middle/last, which is kept as a
    * cross-check rather than as the ordering key because it cannot order the
    * middle authors.
    */
  def openAlex(
      line: String,
      run: RunContext
  ): Either[(RejectReason, String), NormalizedRecord] =
    readJson(line).flatMap { record =>
      val rawId = stringField(record, "id")
      val workKey = rawId.flatMap(ExactIdentifiers.openAlexOf('W', _))
      val title = stringField(record, "display_name")
      (workKey, title) match
        case (None, _) =>
          Left(
            RejectReason.MissingRequiredField -> s"OpenAlex record has no usable Work ID: ${rawId.getOrElse("absent")}"
          )
        case (Some(_), None) =>
          Left(
            RejectReason.MissingRequiredField -> "OpenAlex record has no display_name"
          )
        case (Some(key), Some(workTitle)) =>
          val sourceUri = s"https://openalex.org/$key"
          val provenance =
            run.provenance(SourceType.OpenAlex, key, sourceUri, "openalex")
          val workId = StableIds.work("openalex", key)
          val doi = stringField(record, "doi").flatMap(ExactIdentifiers.doi)
          val arxiv =
            stringField(record, "doi").flatMap(ExactIdentifiers.arxiv)
          val authorships = objectsOf(arrayField(record, "authorships"))

          val mentions = authorships.zipWithIndex.flatMap {
            case (authorship, index) =>
              val author = field(authorship, "author").collect {
                case obj: ujson.Obj => obj
              }
              author.flatMap(stringField(_, "display_name")).map { name =>
                val openAlexAuthor = author
                  .flatMap(stringField(_, "id"))
                  .flatMap(ExactIdentifiers.openAlexOf('A', _))
                val orcid = author
                  .flatMap(stringField(_, "orcid"))
                  .flatMap(ExactIdentifiers.orcid)
                val institutions =
                  objectsOf(arrayField(authorship, "institutions"))
                AuthorMention(
                  schemaVersion = ContractSchema.currentVersion,
                  mentionId = StableIds.mention(workId, index + 1),
                  workId = workId,
                  authorPosition = index + 1,
                  name = name,
                  normalizedName = TextNormalizer.nameKey(name),
                  externalIds = (openAlexAuthor
                    .map(IdNamespace.OpenAlexAuthor.wire -> _)
                    .toVector ++ orcid
                    .map(IdNamespace.Orcid.wire -> _)
                    .toVector ++ stringField(authorship, "author_position")
                    .map("openalex-author-position" -> _)
                    .toVector).toMap,
                  affiliations =
                    institutions.flatMap(stringField(_, "display_name")),
                  provenance = provenance
                )
              }
          }

          if mentions.length != authorships.length then
            Left(
              RejectReason.MissingRequiredField -> "OpenAlex authorship has no author display_name"
            )
          else
            val topics = objectsOf(arrayField(record, "topics")).flatMap {
              topic =>
                for
                  id <- stringField(topic, "id").flatMap(
                    ExactIdentifiers.openAlexOf('T', _)
                  )
                  name <- stringField(topic, "display_name")
                yield Topic(
                  schemaVersion = ContractSchema.currentVersion,
                  topicId = s"topic:v1:openalex:$id",
                  name = name,
                  parentTopicId = None,
                  provenance = provenance
                )
            }
            val institutions = authorships
              .flatMap(authorship =>
                objectsOf(arrayField(authorship, "institutions"))
              )
              .flatMap { institution =>
                for
                  id <- stringField(institution, "id").flatMap(
                    ExactIdentifiers.openAlexOf('I', _)
                  )
                  name <- stringField(institution, "display_name")
                yield Institution(
                  schemaVersion = ContractSchema.currentVersion,
                  institutionId = s"institution:v1:openalex:$id",
                  name = name,
                  countryCode = stringField(institution, "country_code"),
                  externalIds = (Vector(
                    IdNamespace.OpenAlexInstitution.wire -> id
                  ) ++ stringField(institution, "ror")
                    .flatMap(ExactIdentifiers.ror)
                    .map(IdNamespace.Ror.wire -> _)
                    .toVector).toMap,
                  provenance = provenance
                )
              }
              .distinctBy(_.institutionId)

            val researchers = authorships
              .flatMap(authorship =>
                field(authorship, "author").collect { case obj: ujson.Obj =>
                  obj
                }
              )
              .flatMap { author =>
                for
                  orcid <- stringField(author, "orcid")
                    .flatMap(ExactIdentifiers.orcid)
                  name <- stringField(author, "display_name")
                yield CanonicalResearcher(
                  schemaVersion = ContractSchema.currentVersion,
                  researcherId =
                    StableIds.researcherFromExternal("orcid", orcid),
                  displayName = name,
                  externalIds = (Vector(IdNamespace.Orcid.wire -> orcid)
                    ++ stringField(author, "id")
                      .flatMap(ExactIdentifiers.openAlexOf('A', _))
                      .map(IdNamespace.OpenAlexAuthor.wire -> _)
                      .toVector).toMap,
                  aliases = Vector.empty,
                  provenance = provenance
                )
              }
              .distinctBy(_.researcherId)

            val identifiers = (Vector(IdNamespace.OpenAlexWork -> key)
              ++ doi.map(IdNamespace.Doi -> _).toVector
              ++ arxiv.map(IdNamespace.Arxiv -> _).toVector
              ++ authorships
                .flatMap(authorship =>
                  field(authorship, "author").collect { case obj: ujson.Obj =>
                    obj
                  }
                )
                .flatMap(author =>
                  stringField(author, "orcid")
                    .flatMap(ExactIdentifiers.orcid)
                    .map(IdNamespace.Orcid -> _)
                )
              ++ institutions.flatMap(
                _.externalIds
                  .get(IdNamespace.Ror.wire)
                  .map(IdNamespace.Ror -> _)
              )
              ++ topics.map(topic =>
                IdNamespace.OpenAlexTopic -> topic.topicId
                  .split(':')
                  .last
              )).distinct

            val work = WorkRecord(
              schemaVersion = ContractSchema.currentVersion,
              workId = workId,
              source = SourceType.OpenAlex,
              sourceRecordId = key,
              title = workTitle,
              normalizedTitle = TextNormalizer.titleKey(workTitle),
              year = intField(record, "publication_year"),
              // No venue. The allowlisted OpenAlex projection carries no venue
              // field, and the record's `type` is not one: deriving a venue ID
              // from "article" would assert that every OpenAlex article shares
              // a single venue, which would collapse the `name-venue` blocking
              // strategy into one giant block for every common name — the exact
              // pathology job 4 exists to prevent.
              venueId = None,
              doi = doi,
              arxivId = arxiv,
              authors = mentions,
              // The fixture excludes abstract reconstruction by dataset
              // policy, so only a reference is ever carried, never the text.
              // `abstract_inverted_index` is a JSON object, so its presence is
              // tested with `field`; `stringField` would always return None and
              // silently drop the reference.
              abstractTextRef = field(record, "abstract_inverted_index")
                .map(_ => s"openalex:abstract/$key"),
              electronicEditionUris =
                doi.map(value => s"https://doi.org/$value").toVector,
              sourceUris = Vector(sourceUri),
              recordType =
                stringField(record, "type").getOrElse("openalex-work"),
              provenance = provenance
            )

            Right(
              NormalizedRecord(
                work = Some(work),
                researchers = researchers,
                topics = topics.distinctBy(_.topicId),
                institutions = institutions,
                identifiers = identifiers
              )
            )
    }

  // ------------------------------------------------------------------- ORCID

  val orcidStage = "orcid-normalization"

  /** ORCID person projections are identity evidence, not works. The iD's check
    * digit is verified before an identity is minted.
    */
  def orcid(
      line: String,
      run: RunContext
  ): Either[(RejectReason, String), NormalizedRecord] =
    readJson(line).flatMap { record =>
      val name = field(record, "name").collect { case obj: ujson.Obj => obj }
      val iD = name
        .flatMap(stringField(_, "path"))
        .flatMap(ExactIdentifiers.orcid)
      iD match
        case None =>
          Left(
            RejectReason.MissingRequiredField -> "ORCID record has no valid iD at name.path"
          )
        case Some(orcidId) =>
          val nested = (parent: ujson.Obj, key: String) =>
            field(parent, key)
              .collect { case obj: ujson.Obj => obj }
              .flatMap(stringField(_, "value"))
          val family = name.flatMap(nested(_, "family-name"))
          val givenNames = name.flatMap(nested(_, "given-names"))
          val creditName = name.flatMap(nested(_, "credit-name"))
          val nameParts = Vector(givenNames, family).flatten
          val displayName = creditName
            .orElse(Option.when(nameParts.nonEmpty)(nameParts.mkString(" ")))
          displayName match
            case None =>
              Left(
                RejectReason.MissingRequiredField -> s"ORCID record $orcidId has no public name"
              )
            case Some(display) =>
              val sourceUri = s"https://orcid.org/$orcidId"
              val provenance =
                run.provenance(SourceType.Orcid, orcidId, sourceUri, "orcid")
              Right(
                NormalizedRecord(
                  researchers = Vector(
                    CanonicalResearcher(
                      schemaVersion = ContractSchema.currentVersion,
                      researcherId =
                        StableIds.researcherFromExternal("orcid", orcidId),
                      displayName = display,
                      externalIds = Map(IdNamespace.Orcid.wire -> orcidId),
                      aliases = Vector(
                        creditName,
                        family.map(value =>
                          givenNames.fold(value)(first => s"$first $value")
                        )
                      ).flatten.distinct.filterNot(_ == display),
                      provenance = provenance
                    )
                  ),
                  identifiers = Vector(IdNamespace.Orcid -> orcidId)
                )
              )
    }

  // ---------------------------------------------------------------- Crossref

  val crossrefStage = "crossref-normalization"

  /** Crossref works, keyed by DOI. `author` order is the author sequence;
    * Crossref's `sequence` field distinguishes only first from additional.
    */
  def crossref(
      line: String,
      run: RunContext
  ): Either[(RejectReason, String), NormalizedRecord] =
    readJson(line).flatMap { record =>
      // The REST API wraps the work in `message`; the public data file does
      // not. Both shapes are accepted so one normalizer covers both.
      val body = field(record, "message")
        .collect { case obj: ujson.Obj => obj }
        .getOrElse(record)
      val doi = stringField(body, "DOI").flatMap(ExactIdentifiers.doi)
      val title = arrayField(body, "title").flatMap(str).headOption
      (doi, title) match
        case (None, _) =>
          Left(
            RejectReason.MissingRequiredField -> "Crossref record has no valid DOI"
          )
        case (Some(_), None) =>
          Left(
            RejectReason.MissingRequiredField -> "Crossref record has no title"
          )
        case (Some(canonicalDoi), Some(workTitle)) =>
          val sourceUri = s"https://doi.org/$canonicalDoi"
          val provenance = run.provenance(
            SourceType.Crossref,
            canonicalDoi,
            sourceUri,
            "crossref"
          )
          val workId = StableIds.work("crossref", canonicalDoi)
          val authors = objectsOf(arrayField(body, "author"))
          val mentions = authors.zipWithIndex.flatMap { case (author, index) =>
            val family = stringField(author, "family")
            val givenName = stringField(author, "given")
            val literal = stringField(author, "name")
            val parts = Vector(givenName, family).flatten
            val display = literal.orElse(
              Option.when(parts.nonEmpty)(parts.mkString(" "))
            )
            display.map { name =>
              AuthorMention(
                schemaVersion = ContractSchema.currentVersion,
                mentionId = StableIds.mention(workId, index + 1),
                workId = workId,
                authorPosition = index + 1,
                name = name,
                // A structurally known family name beats the positional
                // heuristic, so it is used directly when present.
                normalizedName =
                  family.fold(TextNormalizer.nameKey(name)) { known =>
                    givenName.fold(TextNormalizer.nameKey(known))(first =>
                      TextNormalizer.nameKey(s"$first $known")
                    )
                  },
                externalIds = (stringField(author, "ORCID")
                  .flatMap(ExactIdentifiers.orcid)
                  .map(IdNamespace.Orcid.wire -> _)
                  .toVector ++ stringField(author, "sequence")
                  .map("crossref-sequence" -> _)
                  .toVector ++ family
                  .map("crossref-family" -> _)
                  .toVector).toMap,
                // Crossref free-text affiliation names are excluded by the
                // dataset manifest's privacy note, so they are never read.
                affiliations = Vector.empty,
                provenance = provenance
              )
            }
          }
          if mentions.length != authors.length then
            Left(
              RejectReason.MissingRequiredField -> "Crossref author entry has no name"
            )
          else
            val issns = arrayField(body, "ISSN")
              .flatMap(str)
              .flatMap(ExactIdentifiers.issn)
              .distinct
            val containerTitle =
              arrayField(body, "container-title").flatMap(str).headOption
            val venueId = containerTitle.flatMap(name =>
              Some(TextNormalizer.venueKey("crossref", name)).filter(_.nonEmpty)
            )
            val year = field(body, "issued")
              .collect { case obj: ujson.Obj => obj }
              .map(issued => arrayField(issued, "date-parts"))
              .flatMap(_.headOption)
              .collect { case ujson.Arr(parts) => parts.toVector }
              .flatMap(_.headOption)
              .collect { case ujson.Num(number) => number.toInt }
            // A Crossref work carries at most one container title, so this is
            // empty or holds one venue. `NormalizedRecord.venues` is a
            // `Vector`, so the conversion happens here rather than at the call
            // site, keeping the value's type honest about its own arity.
            val venues = containerTitle
              .zip(venueId)
              .map { case (name, id) =>
                Venue(
                  schemaVersion = ContractSchema.currentVersion,
                  venueId = id,
                  name = name,
                  issns = issns,
                  externalIds =
                    issns.headOption.map(IdNamespace.Issn.wire -> _).toMap,
                  provenance = provenance
                )
              }
              .toVector
            val researchers = authors
              .flatMap { author =>
                for
                  orcid <- stringField(author, "ORCID")
                    .flatMap(ExactIdentifiers.orcid)
                  display <- stringField(author, "family")
                yield CanonicalResearcher(
                  schemaVersion = ContractSchema.currentVersion,
                  researcherId =
                    StableIds.researcherFromExternal("orcid", orcid),
                  displayName = display,
                  externalIds = Map(IdNamespace.Orcid.wire -> orcid),
                  aliases = Vector.empty,
                  provenance = provenance
                )
              }
              .distinctBy(_.researcherId)

            val identifiers = (Vector(IdNamespace.Doi -> canonicalDoi)
              ++ issns.map(IdNamespace.Issn -> _)
              ++ authors.flatMap(author =>
                stringField(author, "ORCID")
                  .flatMap(ExactIdentifiers.orcid)
                  .map(IdNamespace.Orcid -> _)
              )
              ++ objectsOf(
                authors.flatMap(author => arrayField(author, "affiliation"))
              ).flatMap(affiliation =>
                stringField(affiliation, "id")
                  .flatMap(ExactIdentifiers.ror)
                  .map(IdNamespace.Ror -> _)
              )).distinct

            val work = WorkRecord(
              schemaVersion = ContractSchema.currentVersion,
              workId = workId,
              source = SourceType.Crossref,
              sourceRecordId = canonicalDoi,
              title = workTitle,
              normalizedTitle = TextNormalizer.titleKey(workTitle),
              year = year,
              venueId = venueId,
              doi = Some(canonicalDoi),
              arxivId = ExactIdentifiers.arxiv(canonicalDoi),
              authors = mentions,
              // Crossref abstracts retain publisher rights and are excluded
              // from the fixture by dataset policy; only a reference travels.
              abstractTextRef = stringField(body, "abstract")
                .map(_ => s"crossref:abstract/$canonicalDoi"),
              electronicEditionUris = Vector(sourceUri),
              sourceUris = Vector(sourceUri),
              recordType = stringField(body, "type").getOrElse("crossref-work"),
              provenance = provenance
            )

            Right(
              NormalizedRecord(
                work = Some(work),
                researchers = researchers,
                venues = venues,
                identifiers = identifiers
              )
            )
    }

  /** The normalizer registry. A source without an entry is an unsupported
    * record, which is a reject rather than a silent pass-through.
    */
  val byDataset: Map[String, (String, RunContext) => Either[
    (RejectReason, String),
    NormalizedRecord
  ]] = Map(
    "openalex" -> openAlex,
    "orcid" -> orcid,
    "crossref" -> crossref
  )

  /** The artifacts a dataset's normalization must write for the run to be
    * meaningful, defined once and shared by the driver and the tests.
    *
    * Only the dataset's defining artifact is listed. Enrichments such as
    * topics, institutions, venues and ORCID-bearing researchers depend on the
    * data (a Crossref slice with no ORCID authors writes no researchers), so
    * the driver reads them only when the job's own stream counters show they
    * were written. A listed artifact that was not written fails the run at the
    * driver instead of being skipped.
    */
  val expectedOutputs: Map[String, Set[Artifact]] = Map(
    "openalex" -> Set(Artifact.NormalizedWorks),
    "crossref" -> Set(Artifact.NormalizedWorks),
    "orcid" -> Set(Artifact.NormalizedResearchers)
  )

  val stageOf: Map[String, String] = Map(
    "openalex" -> openAlexStage,
    "orcid" -> orcidStage,
    "crossref" -> crossrefStage
  )

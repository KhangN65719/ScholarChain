package scholarchain.hadoop.pipeline

import scholarchain.core.{
  AuthorMention,
  CanonicalResearcher,
  ContractSchema,
  SourceType,
  StableIds,
  Venue,
  WorkRecord
}

/** One normalized DBLP publication together with the side artifacts it yields.
  */
final case class DblpPublication(
    work: WorkRecord,
    venue: Option[Venue],
    identifiers: Vector[(IdNamespace, String)],
    editorNames: Vector[String],
    editorResearchers: Vector[CanonicalResearcher]
)

/** Job 1: DBLP record extraction.
  *
  * The parser is a pure function from one record's XML text to either a reject
  * diagnostic or a normalized publication. Record boundaries are not its
  * concern; [[XmlRecordStream]] guarantees it receives exactly one complete
  * element.
  */
object DblpParser:
  val stage = "dblp-extraction"
  private val dblpBase = "https://dblp.org/"

  /** Venue-bearing elements, in the precedence order DBLP uses per record type.
    * The first present element names the venue.
    */
  private val venueElements =
    Vector("journal", "booktitle", "series", "school", "publisher")

  def parse(
      xml: String,
      run: RunContext
  ): Either[(RejectReason, String), DblpPublication] =
    XmlFragment.parse(xml) match
      case Left(diagnostic) => Left(RejectReason.MalformedXml -> diagnostic)
      case Right(element)   =>
        try parseElement(element, xml, run)
        catch
          case error: IllegalArgumentException =>
            Left(RejectReason.SchemaViolation -> error.getMessage)

  private def parseElement(
      element: org.w3c.dom.Element,
      xml: String,
      run: RunContext
  ): Either[(RejectReason, String), DblpPublication] =
    val recordType = element.getTagName
    if !XmlRecordStream.publicationTags.contains(recordType) then
      Left(
        RejectReason.UnsupportedRecordType -> s"Unsupported DBLP record type: $recordType"
      )
    else
      val key = XmlFragment.attribute(element, "key")
      val title = XmlFragment.textOf(element, "title")
      (key, title) match
        case (None, _) =>
          Left(RejectReason.MissingRequiredField -> "DBLP record has no key")
        case (Some(_), None) =>
          Left(RejectReason.MissingRequiredField -> "DBLP record has no title")
        case (Some(recordKey), Some(recordTitle)) =>
          ExactIdentifiers.normalize(IdNamespace.DblpKey, recordKey) match
            case None =>
              Left(
                RejectReason.MissingRequiredField -> s"Malformed DBLP key: $recordKey"
              )
            case Some(canonicalKey) =>
              build(element, xml, run, recordType, canonicalKey, recordTitle)

  private def build(
      element: org.w3c.dom.Element,
      xml: String,
      run: RunContext,
      recordType: String,
      key: String,
      title: String
  ): Either[(RejectReason, String), DblpPublication] =
    val sourceUri = s"${dblpBase}rec/$key"
    val provenance =
      run.provenance(SourceType.Dblp, key, sourceUri, "dblp")
    val workId = StableIds.work("dblp", key)

    val electronicEditions = XmlFragment.textsOf(element, "ee").distinct
    val relativeUrls = XmlFragment.textsOf(element, "url").distinct
    val absoluteUrls = relativeUrls.map { url =>
      if url.startsWith("http") then url else s"$dblpBase$url"
    }

    // `crossref` in DBLP XML is a parent DBLP record key, not a Crossref DOI,
    // so it is deliberately not read as an identifier here.
    val linkIdentifiers =
      electronicEditions.flatMap(ExactIdentifiers.fromElectronicEdition)
    val explicitOrcids = XmlFragment
      .children(element, "author")
      .flatMap(author => XmlFragment.attribute(author, "orcid"))
      .flatMap(ExactIdentifiers.orcid)
      .map(IdNamespace.Orcid -> _)
    val identifiers =
      (Vector(
        IdNamespace.DblpKey -> key
      ) ++ linkIdentifiers ++ explicitOrcids).distinct

    val year = XmlFragment.textOf(element, "year").flatMap(_.toIntOption)
    val venueName = venueElements.iterator
      .flatMap(name => XmlFragment.textOf(element, name))
      .nextOption()
    val venueId = venueName.flatMap { name =>
      Some(TextNormalizer.venueKey("dblp", name)).filter(_.nonEmpty)
    }

    // Author occurrence order is the author sequence and is never sorted.
    val authorElements = XmlFragment.children(element, "author")
    val mentions = authorElements.zipWithIndex.flatMap { case (author, index) =>
      val name = XmlFragment.text(author)
      Option.when(name.nonEmpty && TextNormalizer.nameKey(name).nonEmpty) {
        AuthorMention(
          schemaVersion = ContractSchema.currentVersion,
          mentionId = StableIds.mention(workId, index + 1),
          workId = workId,
          authorPosition = index + 1,
          name = name,
          normalizedName = TextNormalizer.nameKey(name),
          externalIds = XmlFragment
            .attribute(author, "orcid")
            .flatMap(ExactIdentifiers.orcid)
            .map(IdNamespace.Orcid.wire -> _)
            .toMap,
          affiliations = Vector.empty,
          provenance = provenance
        )
      }
    }

    // A blank `<author/>` would shift every later author's position, so a
    // record with one is rejected rather than renumbered.
    if mentions.length != authorElements.length then
      Left(
        RejectReason.MissingRequiredField -> "DBLP record has a blank or unmatchable author element"
      )
    else if TextNormalizer.titleKey(title).isEmpty then
      Left(
        RejectReason.MissingRequiredField -> "DBLP title normalizes to empty"
      )
    else
      val editorElements = XmlFragment.children(element, "editor")
      val editorNames = editorElements.map(XmlFragment.text).filter(_.nonEmpty)
      val editorResearchers = editorElements.flatMap { editor =>
        val name = XmlFragment.text(editor)
        XmlFragment
          .attribute(editor, "orcid")
          .flatMap(ExactIdentifiers.orcid)
          .filter(_ => name.nonEmpty)
          .map { orcid =>
            CanonicalResearcher(
              schemaVersion = ContractSchema.currentVersion,
              researcherId = StableIds.researcherFromExternal("orcid", orcid),
              displayName = name,
              externalIds = Map(IdNamespace.Orcid.wire -> orcid),
              aliases = Vector.empty,
              provenance = provenance
            )
          }
      }

      val work = WorkRecord(
        schemaVersion = ContractSchema.currentVersion,
        workId = workId,
        source = SourceType.Dblp,
        sourceRecordId = key,
        title = title,
        normalizedTitle = TextNormalizer.titleKey(title),
        year = year,
        venueId = venueId,
        doi = identifiers.collectFirst { case (IdNamespace.Doi, value) =>
          value
        },
        arxivId = identifiers.collectFirst { case (IdNamespace.Arxiv, value) =>
          value
        },
        authors = mentions,
        abstractTextRef = None,
        electronicEditionUris = electronicEditions,
        sourceUris = absoluteUrls,
        recordType = recordType,
        provenance = provenance
      )

      val venue = venueName.zip(venueId).map { case (name, id) =>
        Venue(
          schemaVersion = ContractSchema.currentVersion,
          venueId = id,
          name = name,
          issns = Vector.empty,
          externalIds = Map("dblp-venue" -> TextNormalizer.fold(name)),
          provenance = provenance
        )
      }

      Right(
        DblpPublication(
          work = work,
          venue = venue,
          identifiers = identifiers,
          editorNames = editorNames,
          editorResearchers = editorResearchers
        )
      )

  /** DBLP `www` and `person` records are identity evidence rather than
    * publications, so they are normalized to canonical researchers and kept out
    * of the publication and pair paths entirely.
    */
  def parseIdentity(
      xml: String,
      run: RunContext
  ): Either[(RejectReason, String), CanonicalResearcher] =
    XmlFragment.parse(xml) match
      case Left(diagnostic) => Left(RejectReason.MalformedXml -> diagnostic)
      case Right(element)   =>
        if !XmlRecordStream.identityTags.contains(element.getTagName) then
          Left(
            RejectReason.UnsupportedRecordType -> s"Not a DBLP identity record: ${element.getTagName}"
          )
        else
          val key = XmlFragment.attribute(element, "key")
          val names = XmlFragment.textsOf(element, "author")
          (key, names.headOption) match
            case (Some(personKey), Some(primaryName)) =>
              val provenance = run.provenance(
                SourceType.Dblp,
                personKey,
                s"${dblpBase}pid/$personKey",
                "dblp"
              )
              val orcids = XmlFragment
                .children(element, "author")
                .flatMap(author => XmlFragment.attribute(author, "orcid"))
                .flatMap(ExactIdentifiers.orcid)
                .distinct
              Right(
                CanonicalResearcher(
                  schemaVersion = ContractSchema.currentVersion,
                  researcherId =
                    StableIds.researcherFromExternal("dblp", personKey),
                  displayName = primaryName,
                  externalIds = orcids.headOption
                    .map(IdNamespace.Orcid.wire -> _)
                    .toMap + ("dblp-pid" -> personKey),
                  aliases = names.drop(1).distinct,
                  provenance = provenance
                )
              )
            case (None, _) =>
              Left(
                RejectReason.MissingRequiredField -> "DBLP identity record has no key"
              )
            case (_, None) =>
              Left(
                RejectReason.MissingRequiredField -> "DBLP identity record has no name"
              )

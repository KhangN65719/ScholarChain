package scholarchain.er.eval.labeling

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scholarchain.er.eval.Verdict
import scholarchain.er.pairs.Hashing

/** Names a reviewer types or an external file writes for a verdict. */
object LabelNames:
  def parse(text: String): Option[Verdict] =
    text.trim.toLowerCase(java.util.Locale.ROOT) match
      case "same" | "match"                       => Some(Verdict.Match)
      case "different" | "non-match" | "nonmatch" => Some(Verdict.NonMatch)
      case "unsure"                               => Some(Verdict.Unsure)
      case _                                      => None

  def name(verdict: Verdict): String =
    verdict match
      case Verdict.Match    => "same"
      case Verdict.NonMatch => "different"
      case Verdict.Unsure   => "unsure"

  val accepted = "same, different, unsure (or match, non-match)"

/** An external opinion about a pair. It is shown only on request and is never
  * used to decide anything.
  */
final case class Suggestion(
    pairId: String,
    label: Verdict,
    reason: Option[String]
)

final case class SuggestionSource(file: String, sha256: String)

final case class SuggestionSet(
    source: SuggestionSource,
    byPair: Map[String, Suggestion],
    unknownPairIds: Vector[String]
)

/** Reads `pair_id,suggested_label,reason`. Quoted fields may hold commas,
  * doubled quotes and line breaks. Every malformed row is reported with its
  * line number and the file is refused as a whole, so a partly imported
  * suggestion list cannot pass unnoticed. Pair IDs that are not in the pairs
  * file are listed, not silently dropped.
  */
object Suggestions:
  val header: Vector[String] = Vector("pair_id", "suggested_label", "reason")

  private def rows(
      text: String
  ): Either[String, Vector[(Int, Vector[String])]] =
    val found = Vector.newBuilder[(Int, Vector[String])]
    var fields = Vector.empty[String]
    val current = new StringBuilder
    var quoted = false
    var wasQuoted = false
    var line = 1
    var rowStart = 1
    var quoteStart = 1
    var error = Option.empty[String]
    def endField(): Unit =
      fields = fields :+ current.toString
      current.clear()
      wasQuoted = false
    def endRow(): Unit =
      endField()
      if !(fields.size == 1 && fields.head.trim.isEmpty) then
        found += rowStart -> fields
      fields = Vector.empty
    var i = 0
    while i < text.length && error.isEmpty do
      val c = text(i)
      if quoted then
        if c == '"' then
          if i + 1 < text.length && text(i + 1) == '"' then
            current.append('"')
            i += 1
          else quoted = false
        else if c == '\r' && i + 1 < text.length && text(i + 1) == '\n' then ()
        else
          if c == '\n' then line += 1
          current.append(c)
      else
        c match
          case '"' if current.isEmpty && !wasQuoted =>
            quoted = true
            wasQuoted = true
            quoteStart = line
          case '"' =>
            error = Some(s"line $line: stray quote inside an unquoted field")
          case ','  => endField()
          case '\r' => ()
          case '\n' =>
            endRow()
            line += 1
            rowStart = line
          case other => current.append(other)
      i += 1
    error match
      case Some(message)  => Left(message)
      case None if quoted =>
        Left(s"line $quoteStart: quoted field is never closed")
      case None =>
        if current.nonEmpty || fields.nonEmpty || wasQuoted then endRow()
        Right(found.result())

  def parse(
      text: String,
      source: SuggestionSource,
      knownPairIds: Set[String]
  ): Either[Vector[String], SuggestionSet] =
    val clean = text.stripPrefix("﻿")
    rows(clean) match
      case Left(message) => Left(Vector(message))
      case Right(all)    =>
        all.headOption match
          case None =>
            Left(Vector("the file is empty; expected a header row"))
          case Some((number, first)) if first.map(_.trim) != header =>
            Left(
              Vector(
                s"line $number: header must be exactly '${header.mkString(",")}'"
              )
            )
          case Some(_) =>
            val problems = Vector.newBuilder[String]
            val parsed = all.tail.flatMap { (number, fields) =>
              if fields.size != 3 then
                problems += s"line $number: expected 3 columns but found ${fields.size}"
                None
              else
                val id = fields(0).trim
                val label = LabelNames.parse(fields(1))
                if id.isEmpty then
                  problems += s"line $number: pair_id is blank"
                  None
                else if label.isEmpty then
                  problems += s"line $number: suggested_label " +
                    s"'${fields(1).trim}' is not one of ${LabelNames.accepted}"
                  None
                else
                  Some(
                    Suggestion(
                      id,
                      label.get,
                      Option(fields(2).trim).filter(_.nonEmpty)
                    )
                  )
            }
            parsed
              .groupBy(_.pairId)
              .filter(_._2.size > 1)
              .keys
              .toVector
              .sorted
              .foreach(id =>
                problems += s"pair_id '$id' appears more than once"
              )
            val found = problems.result()
            if found.nonEmpty then Left(found)
            else
              val (known, unknown) =
                parsed.partition(s => knownPairIds.contains(s.pairId))
              Right(
                SuggestionSet(
                  source,
                  known.map(s => s.pairId -> s).toMap,
                  unknown.map(_.pairId).sorted
                )
              )

  def load(
      path: Path,
      knownPairIds: Set[String]
  ): Either[Vector[String], SuggestionSet] =
    val bytes = Files.readAllBytes(path)
    parse(
      new String(bytes, UTF_8),
      SuggestionSource(
        path.getFileName.toString,
        Hashing.sha256Bytes(bytes)
      ),
      knownPairIds
    )

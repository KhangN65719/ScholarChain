package scholarchain.hadoop.pipeline

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/** Byte-level reader that yields one complete XML element per call, never
  * splitting an element across two readers.
  *
  * The contract mirrors Hadoop's split model and is what makes the DBLP job
  * correct on a multi-split input:
  *
  *   - a record is yielded only when its opening `<` lies at an offset in
  *     `[splitStart, splitEnd)`;
  *   - once started, a record is read to its closing tag even if that runs past
  *     `splitEnd`.
  *
  * Together those two rules mean every record is emitted exactly once across
  * the whole file: the reader owning the split that contains a record's start
  * emits it, and every other reader skips it. A record straddling a boundary
  * therefore is neither lost nor duplicated.
  *
  * Matching is done on bytes. All recognized tag names are ASCII, and UTF-8
  * never encodes an ASCII byte inside a multi-byte sequence, so byte matching
  * cannot false-match inside non-ASCII content.
  */
final class XmlRecordStream(
    input: InputStream,
    recordTags: Set[String],
    splitStart: Long,
    splitEnd: Long
):
  require(recordTags.nonEmpty, "At least one record tag is required")
  require(
    recordTags.forall(_.matches("[A-Za-z][A-Za-z0-9._-]*")),
    "Record tags must be XML names"
  )
  require(splitStart >= 0 && splitEnd >= splitStart, "Malformed split bounds")

  private val tagBytes =
    recordTags.iterator.map(tag => tag -> tag.getBytes(UTF_8)).toMap
  private var position = splitStart
  private var pushback = -1
  private var exhausted = false

  private def read(): Int =
    if pushback >= 0 then
      val value = pushback
      pushback = -1
      position += 1
      value
    else
      val value = input.read()
      if value >= 0 then position += 1
      value

  private def unread(value: Int): Unit =
    if value >= 0 then
      pushback = value
      position -= 1

  /** True when the byte sequence terminating an XML name has been reached. */
  private def isNameTerminator(value: Int): Boolean =
    value == ' ' || value == '\t' || value == '\n' || value == '\r' ||
      value == '>' || value == '/'

  private def readName(): String =
    val name = new StringBuilder
    var next = read()
    while next >= 0 && !isNameTerminator(next) && name.length <= 64 do
      name.append(next.toChar)
      next = read()
    unread(next)
    name.toString

  /** Finds the next opening tag whose `<` lies before `splitEnd`. */
  private def seekRecordStart(): Option[(String, Long)] =
    var found: Option[(String, Long)] = None
    while found.isEmpty && !exhausted do
      val value = read()
      if value < 0 then exhausted = true
      else if value == '<' then
        val anglePosition = position - 1
        if anglePosition >= splitEnd then exhausted = true
        else
          val name = readName()
          if tagBytes.contains(name) then found = Some(name -> anglePosition)
      // This scanner does not track document-level comment or CDATA regions.
      // Record-shaped text inside those regions remains a known limitation.
      else if position >= splitEnd && found.isEmpty then
        // Keep scanning only while a start tag could still begin before the
        // boundary; past it there is nothing this reader may claim.
        exhausted = true
    found

  /** Reads from an already-consumed opening name through the matching close.
    *
    * Depth is tracked per tag rather than by string suffix, so a self-closing
    * child (`<ee/>`) does not close the record and a same-named nested element
    * does not end it early. Quotes protect `>` in attribute values. Comment and
    * CDATA regions are not tracked; their record-shaped contents remain a known
    * limitation.
    */
  private def readRecordBody(name: String): String =
    val text = new StringBuilder
    text.append('<').append(name)
    val tag = new StringBuilder
    tag.append(name)
    // The outer start tag has not met its `>` yet, so it raises depth to one
    // below. A self-closing outer tag leaves depth at zero and ends at once.
    var depth = 0
    var inTag = true
    var closed = false
    var quote = -1
    while !closed do
      val value = read()
      if value < 0 then
        exhausted = true
        closed = true
      else text.append(value.toChar)
      if value < 0 then ()
      else if inTag && quote >= 0 then
        tag.append(value.toChar)
        if value == quote then quote = -1
      else if inTag && (value == '\'' || value == '"') then
        quote = value
        tag.append(value.toChar)
      else if inTag && value == '>' then
        val rendered = tag.toString
        val isClose = rendered.startsWith("/")
        val selfClosing = rendered.endsWith("/")
        val body = if isClose then rendered.drop(1) else rendered
        val tagName = body.takeWhile(character => !isNameTerminator(character))
        if tagName == name then
          if isClose then depth -= 1
          else if !selfClosing then depth += 1
        if depth == 0 then closed = true
        inTag = false
        tag.setLength(0)
      else if inTag then tag.append(value.toChar)
      else if value == '<' then
        inTag = true
        tag.setLength(0)
    // Bytes were appended one at a time, so the builder holds Latin-1 code
    // units; reinterpreting them as UTF-8 restores the original characters.
    val bytes = text.toString.map(_.toByte).toArray
    try UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString
    catch
      case _: CharacterCodingException =>
        // NUL is illegal in XML 1.0: the mapper must reject this input rather
        // than accept replacement characters as source data.
        new String(bytes, UTF_8) + "\u0000"

  /** The next complete record and the file offset of its opening `<`. */
  def next(): Option[(Long, String)] =
    if exhausted then None
    else
      seekRecordStart() match
        case None                 => None
        case Some((name, offset)) =>
          Some(offset -> readRecordBody(name))

  def currentPosition: Long = position

  def iterator: Iterator[(Long, String)] =
    Iterator.continually(next()).takeWhile(_.isDefined).map(_.get)

object XmlRecordStream:
  /** DBLP publication record types. `www` and `person` are identity evidence
    * and are handled by the identity path, not the publication path.
    */
  val publicationTags: Set[String] = Set(
    "article",
    "inproceedings",
    "proceedings",
    "book",
    "incollection",
    "phdthesis",
    "mastersthesis",
    "data"
  )

  val identityTags: Set[String] = Set("www", "person")

  val allTags: Set[String] = publicationTags ++ identityTags

  /** The element name of a record already extracted by this reader, read off
    * the leading `<name` without a full parse. Used to route a record to the
    * publication or the identity path before paying for DOM construction.
    */
  def rootTag(record: String): Option[String] =
    val trimmed = record.trim
    Option
      .when(trimmed.startsWith("<"))(
        trimmed.drop(1).takeWhile(character => character.isLetterOrDigit)
      )
      .filter(_.nonEmpty)

  /** Reads every record in a complete document. Used by unit tests and by the
    * local bounded runner; the Hadoop record reader uses the split-aware
    * constructor instead.
    */
  def records(
      xml: String,
      tags: Set[String] = publicationTags
  ): Vector[String] =
    val bytes = xml.getBytes(UTF_8)
    new XmlRecordStream(
      new ByteArrayInputStream(bytes),
      tags,
      0L,
      bytes.length.toLong
    ).iterator.map(_._2).toVector

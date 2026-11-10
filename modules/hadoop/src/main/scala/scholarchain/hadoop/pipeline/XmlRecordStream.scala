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
  * Comment (`<!-- -->`) and CDATA (`<![CDATA[ ]]>`) regions contribute no
  * record, whether or not a space follows the opener, including a region that
  * crosses a read buffer or a split boundary. A reader that starts mid-file
  * cannot know from its own bytes whether it starts inside such a region, so
  * the caller may begin the input at `scanStart`, up to
  * [[XmlRecordStream.lookbackBytes]] before `splitStart`. The region state is
  * then established from that earlier point, and any record opening before
  * `splitStart` is read through and left to the earlier reader. A comment or
  * CDATA section that opens more than the look-back before a split and runs
  * across it is not recognized by that split's reader. An unterminated region
  * runs to end of input and yields nothing.
  *
  * Matching is done on bytes. All recognized tag names are ASCII, and UTF-8
  * never encodes an ASCII byte inside a multi-byte sequence, so byte matching
  * cannot false-match inside non-ASCII content.
  */
final class XmlRecordStream(
    input: InputStream,
    recordTags: Set[String],
    scanStart: Long,
    splitStart: Long,
    splitEnd: Long
):
  /** A reader whose input begins exactly at its split start. */
  def this(
      input: InputStream,
      recordTags: Set[String],
      splitStart: Long,
      splitEnd: Long
  ) = this(input, recordTags, splitStart, splitStart, splitEnd)

  require(recordTags.nonEmpty, "At least one record tag is required")
  require(
    recordTags.forall(_.matches("[A-Za-z][A-Za-z0-9._-]*")),
    "Record tags must be XML names"
  )
  require(
    scanStart >= 0 && scanStart <= splitStart && splitEnd >= splitStart,
    "Malformed split bounds"
  )

  private val tagBytes =
    recordTags.iterator.map(tag => tag -> tag.getBytes(UTF_8)).toMap
  private var position = scanStart
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

  /** Consumes `expected` if the next bytes are exactly it. On a mismatch the
    * offending byte is pushed back, so the caller resumes scanning from it. The
    * expected text never contains `<`, so the bytes consumed before a mismatch
    * cannot hide a tag start.
    */
  private def matchLiteral(expected: String): Boolean =
    var matched = true
    var index = 0
    while matched && index < expected.length do
      val value = read()
      if value == expected.charAt(index) then index += 1
      else
        unread(value)
        matched = false
    matched

  /** Consumes through the end of a comment (`-->`) or CDATA section (`]]>`).
    * `mark` is the repeated byte of the terminator. End of input inside the
    * region ends the scan, so an unterminated region contributes no record.
    */
  private def skipRegion(mark: Char): Unit =
    var run = 0
    var done = false
    while !done do
      val value = read()
      if value < 0 then
        exhausted = true
        done = true
      else if value == mark then run += 1
      else if value == '>' && run >= 2 then done = true
      else run = 0

  /** Finds the next opening tag whose `<` lies in `[splitStart, splitEnd)`,
    * skipping comment and CDATA regions and any record that starts in the
    * look-back zone before `splitStart`.
    */
  private def seekRecordStart(): Option[(String, Long)] =
    var found: Option[(String, Long)] = None
    while found.isEmpty && !exhausted do
      val value = read()
      if value < 0 then exhausted = true
      else if value == '<' then
        val anglePosition = position - 1
        if anglePosition >= splitEnd then exhausted = true
        else
          val following = read()
          if following == '!' then
            if matchLiteral("--") then skipRegion('-')
            else if matchLiteral("[CDATA[") then skipRegion(']')
          else
            unread(following)
            val name = readName()
            if tagBytes.contains(name) then
              if anglePosition >= splitStart then
                found = Some(name -> anglePosition)
              else
                // Started in the look-back zone, so an earlier reader owns it.
                // It is read through only to keep the scan state correct.
                readRecordBody(name)
      else if position >= splitEnd && found.isEmpty then
        // Keep scanning only while a start tag could still begin before the
        // boundary; past it there is nothing this reader may claim.
        exhausted = true
    found

  /** Reads from an already-consumed opening name through the matching close.
    *
    * Depth is tracked per tag rather than by string suffix, so a self-closing
    * child (`<ee/>`) does not close the record and a same-named nested element
    * does not end it early. Quotes protect `>` in attribute values. A comment
    * or CDATA section inside the record is skipped through its terminator, so a
    * closing tag, a quote or a `>` inside one neither ends the record nor
    * changes its quote state.
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
    var regionMark = -1
    var run = 0
    while !closed do
      val value = read()
      if value < 0 then
        exhausted = true
        closed = true
      else text.append(value.toChar)
      if value < 0 then ()
      else if regionMark >= 0 then
        if value == regionMark then run += 1
        else if value == '>' && run >= 2 then
          regionMark = -1
          inTag = false
          tag.setLength(0)
        else run = 0
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
      else if inTag then
        tag.append(value.toChar)
        if tag.length == 3 && tag.toString == "!--" then
          regionMark = '-'
          run = 0
        else if tag.length == 8 && tag.toString == "![CDATA[" then
          regionMark = ']'
          run = 0
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
  /** How far before its split a reader begins scanning to learn whether the
    * split starts inside a comment or CDATA section.
    */
  val lookbackBytes: Long = 1L << 20

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

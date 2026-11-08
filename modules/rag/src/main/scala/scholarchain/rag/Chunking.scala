package scholarchain.rag

import scala.collection.Searching.{Found, InsertionPoint}
import scholarchain.core.StableIds
import scholarchain.storage.{ErrorCode, Fail}

/** Chunk size and overlap, in characters. */
final case class ChunkPolicy(maxChars: Int, overlapChars: Int):
  require(maxChars >= 50, "maxChars must be at least 50")
  require(
    overlapChars >= 0 && overlapChars <= maxChars / 2,
    "overlapChars must be from 0 to half of maxChars"
  )

object ChunkPolicy:
  /** Version of the chunking rules below. */
  val Version = "rag-chunking/1"
  val Default: ChunkPolicy = ChunkPolicy(1000, 100)

/** One chunk: `[start, end)` of the source text, its ordinal and its stable ID.
  */
final case class TextChunk(
    ordinal: Int,
    start: Int,
    end: Int,
    text: String,
    chunkId: String
)

/** Chunking for permitted abstracts or longer text. Pure and deterministic.
  *
  *   - A text of at most `maxChars` is one chunk, unchanged.
  *   - Otherwise each chunk ends at the last word start at or before
  *     `start + maxChars` (a word start is a non-space character after a space,
  *     so a chunk keeps the spaces that follow its last word). A chunk is never
  *     longer than `maxChars` and no word is split.
  *   - The next chunk starts at the first word start at or after
  *     `end - overlapChars`, and always after the previous start, so the text
  *     shared by two chunks is at most `overlapChars` and whole words.
  *   - A single word longer than `maxChars` is refused (`BoundExceeded`), not
  *     split and not cut. A whitespace run that leaves no word start within
  *     `maxChars` of a chunk start is refused too (`BoundExceeded`, with a
  *     message that names the run): chunking it without loss would need chunks
  *     of blank text, which cannot be embedded. A blank text is refused
  *     (`MissingText`).
  *   - Ordinals count from 0. The chunk ID is `StableIds.chunk` over the entity
  *     ID, the source version, the document type, the ordinal and the SHA-256
  *     of the chunk text (the README stable-ID principle).
  *
  * Nothing is dropped: `reassemble` rebuilds the text exactly from the chunks.
  * The Phase 6 summaries are short, so they are one chunk each and the policy
  * is not applied to them unless a text exceeds `maxChars`.
  */
object Chunker:
  private def isSpace(c: Char): Boolean = Character.isWhitespace(c)

  private def boundaries(text: String): Vector[Int] =
    val starts = text.indices.filter { p =>
      !isSpace(text(p)) && (p == 0 || isSpace(text(p - 1)))
    }
    (starts :+ text.length).toVector

  /** The largest boundary in `(from, limit]`. */
  private def lastUpTo(b: Vector[Int], from: Int, limit: Int): Option[Int] =
    val found = b.search(limit) match
      case Found(i)          => Some(b(i))
      case InsertionPoint(0) => None
      case InsertionPoint(i) => Some(b(i - 1))
    found.filter(_ > from)

  /** The smallest boundary at or after `target`. */
  private def firstFrom(b: Vector[Int], target: Int): Int =
    b.search(target) match
      case Found(i)          => b(i)
      case InsertionPoint(i) => b(i)

  /** Refuses with the reason there is no word start in `(start, start +
    * maxChars]`: the word at `start` is longer than `maxChars`, or the word
    * fits but the whitespace run after it leaves no word start within reach.
    */
  private def noCutPoint(
      entityId: String,
      text: String,
      start: Int,
      maxChars: Int
  ): Nothing =
    val wordEnd = text.indices.indexWhere(
      p => p >= start && isSpace(text(p)),
      start
    ) match
      case -1 => text.length
      case p  => p
    if wordEnd - start > maxChars then
      Fail(
        ErrorCode.BoundExceeded,
        s"A word of $entityId starting at $start is longer than " +
          s"$maxChars characters; refusing to split or cut it"
      )
    else
      val runEnd = text.indices.indexWhere(
        p => p >= wordEnd && !isSpace(text(p)),
        wordEnd
      ) match
        case -1 => text.length
        case p  => p
      Fail(
        ErrorCode.BoundExceeded,
        s"A whitespace run of ${runEnd - wordEnd} characters of $entityId " +
          s"starting at $wordEnd leaves no word start within $maxChars " +
          s"characters of the chunk starting at $start; refusing to cut " +
          "inside it"
      )

  def chunk(
      entityId: String,
      sourceVersion: String,
      documentType: String,
      text: String,
      policy: ChunkPolicy = ChunkPolicy.Default
  ): Vector[TextChunk] =
    Fail.unless(
      text.exists(!isSpace(_)),
      ErrorCode.MissingText,
      s"Nothing to chunk for $entityId: the text is blank"
    )
    val b = boundaries(text)
    val n = text.length
    val chunks = Vector.newBuilder[TextChunk]
    var start = 0
    var ordinal = 0
    var done = false
    while !done do
      val end =
        if start + policy.maxChars >= n then n
        else
          lastUpTo(b, start, start + policy.maxChars).getOrElse(
            noCutPoint(entityId, text, start, policy.maxChars)
          )
      val piece = text.substring(start, end)
      chunks += TextChunk(
        ordinal,
        start,
        end,
        piece,
        StableIds.chunk(
          entityId,
          sourceVersion,
          documentType,
          Some(ordinal),
          piece
        )
      )
      if end == n then done = true
      else
        start = firstFrom(b, math.max(end - policy.overlapChars, start + 1))
        ordinal += 1
    chunks.result()

  /** Rebuilds the text from chunks of one `chunk` call: each chunk contributes
    * what lies beyond the previous end. Chunks must follow without a gap.
    */
  def reassemble(chunks: Vector[TextChunk]): String =
    require(chunks.nonEmpty, "No chunks to reassemble")
    val out = new StringBuilder
    var covered = 0
    chunks.sortBy(_.ordinal).foreach { c =>
      require(c.start <= covered, s"Gap before chunk ${c.ordinal}")
      require(c.start == 0 || out.nonEmpty, "The first chunk must start at 0")
      out.append(c.text.substring(covered - c.start))
      covered = c.end
    }
    out.toString

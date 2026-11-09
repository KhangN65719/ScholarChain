package scholarchain.storage

import java.text.Normalizer
import java.util.Locale
import scholarchain.core.{StableIds, YearWindow}

/** Upper bounds of the runtime read ports. A request above a bound is refused
  * with `InvalidQuery`; nothing is clipped silently.
  */
object ReadLimits:
  val MaxPage = 200
  val MaxMetricRows = 100
  val MaxPathDepth = 16
  val MaxPathResults = 100
  val MaxPathTimeoutMillis = 30000L
  val MaxCandidates = 50

  def bounded(value: Int, max: Int, what: String): Int =
    Fail.unless(
      value >= 1 && value <= max,
      ErrorCode.InvalidQuery,
      s"$what must be from 1 to $max, got $value"
    )
    value

  def nodeId(value: String, what: String): String =
    Fail.unless(
      StableIds.isCoauthorNode(value),
      ErrorCode.InvalidQuery,
      s"$what is not a core node ID: $value"
    )
    value

/** Spark's window text: `all` for years 1 to 9999, else `<start>-<end>`. */
object WindowLabels:
  def of(window: YearWindow): String =
    if window.startYear == 1 && window.endYear == 9999 then "all"
    else s"${window.startYear}-${window.endYear}"

/** A page of researcher IDs. `after` is the last ID of the previous page;
  * `nextAfter` is set only when more members follow.
  */
final case class MemberPage(
    members: Vector[String],
    total: Long,
    nextAfter: Option[String]
)

object MemberPage:
  /** Keyset page over IDs sorted ascending. */
  def of(
      sorted: Vector[String],
      after: Option[String],
      limit: Int
  ): MemberPage =
    val bound = ReadLimits.bounded(limit, ReadLimits.MaxPage, "limit")
    val rest = after.fold(sorted)(last => sorted.filter(_ > last))
    val page = rest.take(bound)
    MemberPage(
      page,
      sorted.size.toLong,
      if rest.size > bound then page.lastOption else None
    )

object Names:
  /** Lower case, accents removed, every run of non-letters and non-digits one
    * space. Two spellings that differ only in case, accents or punctuation
    * normalize alike; nothing fuzzier is attempted.
    */
  def normalize(name: String): String =
    Normalizer
      .normalize(name, Normalizer.Form.NFKD)
      .replaceAll("\\p{M}+", "")
      .toLowerCase(Locale.ROOT)
      .replaceAll("[^\\p{L}\\p{N}]+", " ")
      .trim

  /** The normalized tokens in sorted order, so `Smith, John` and `John Smith`
    * share a key.
    */
  def tokenKey(normalized: String): String =
    normalized.split(' ').filter(_.nonEmpty).sorted.mkString(" ")

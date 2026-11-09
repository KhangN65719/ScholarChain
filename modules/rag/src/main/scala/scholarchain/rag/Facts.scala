package scholarchain.rag

import scholarchain.core.YearWindow
import scholarchain.storage.WindowLabels

/** Deterministic evidence is a core `EvidenceItem` whose `text` is one compact
  * JSON fact. A fact has a `kind` and the stored values, nothing else. The
  * verifier reads facts only from items that are not `TextChunk`, so text a
  * retriever returns can never pose as a fact.
  *
  * Kinds: `metric`, `ranking`, `component`, `community`, `identity`,
  * `coauthor`, `path`, `path-status`, `publication`.
  */
object Facts:
  type Fields = Seq[(String, ujson.Value)]

  def windowFields(window: YearWindow): Fields =
    Seq(
      "window" -> ujson.Str(WindowLabels.of(window)),
      "windowStart" -> ujson.Num(window.startYear),
      "windowEnd" -> ujson.Num(window.endYear)
    )

  def obj(fields: Fields): ujson.Obj = ujson.Obj.from(fields)

  def strs(values: Iterable[String]): ujson.Value =
    ujson.Arr.from(values.map(v => ujson.Str(v)))

  def optStr(value: Option[String]): ujson.Value =
    value.fold[ujson.Value](ujson.Null)(ujson.Str(_))

  def optNum(value: Option[Double]): ujson.Value =
    value.fold[ujson.Value](ujson.Null)(ujson.Num(_))

  def s(value: ujson.Value, key: String): Option[String] =
    value.objOpt.flatMap(_.get(key)).flatMap(_.strOpt)

  def n(value: ujson.Value, key: String): Option[Double] =
    value.objOpt.flatMap(_.get(key)).flatMap(_.numOpt)

  def b(value: ujson.Value, key: String): Option[Boolean] =
    value.objOpt.flatMap(_.get(key)).flatMap(_.boolOpt)

  def list(value: ujson.Value, key: String): Vector[ujson.Value] =
    value.objOpt
      .flatMap(_.get(key))
      .flatMap(_.arrOpt)
      .map(_.toVector)
      .getOrElse(Vector.empty)

  def kind(value: ujson.Value): Option[String] = s(value, "kind")

  /** Every number anywhere in a fact. */
  def numbers(value: ujson.Value): Vector[Double] = value match
    case ujson.Num(v) => Vector(v)
    case ujson.Arr(v) => v.toVector.flatMap(numbers)
    case ujson.Obj(v) => v.toVector.flatMap(entry => numbers(entry._2))
    case _            => Vector.empty

  /** Every string anywhere in a fact, so names and titles can be set aside
    * before the digits of a claim are read.
    */
  def strings(value: ujson.Value): Vector[String] = value match
    case ujson.Str(v) => Vector(v)
    case ujson.Arr(v) => v.toVector.flatMap(strings)
    case ujson.Obj(v) => v.toVector.flatMap(entry => strings(entry._2))
    case _            => Vector.empty

  /** Keys whose string values come from stored data: names and titles (and the
    * source label of a work, and an ORCID). They are not instructions, and they
    * are handed to a model only under an explicit untrusted key.
    */
  private val dataKeys: Set[String] = Set(
    "displayName",
    "researcherName",
    "coauthorName",
    "sourceName",
    "targetName",
    "fromName",
    "toName",
    "title",
    "orcid"
  )

  private def dataKeysOf(fact: ujson.Value): Set[String] =
    if kind(fact).contains("publication") then dataKeys + "source"
    else dataKeys

  /** Splits a fact into the part with no data string and the part that holds
    * only the data strings, at the same positions. The second is `Null` when
    * the fact has none.
    */
  def split(fact: ujson.Value): (ujson.Value, ujson.Value) =
    val keys = dataKeysOf(fact)
    def go(value: ujson.Value): (ujson.Value, ujson.Value) = value match
      case ujson.Obj(fields) =>
        val trusted = ujson.Obj()
        val data = ujson.Obj()
        fields.foreach { (k, v) =>
          if keys.contains(k) && v.strOpt.isDefined then data(k) = v
          else
            val (t, d) = go(v)
            trusted(k) = t
            if d != ujson.Null then data(k) = d
        }
        (trusted, if data.value.isEmpty then ujson.Null else data)
      case ujson.Arr(items) =>
        val parts = items.toVector.map(go)
        (
          ujson.Arr.from(parts.map(_._1)),
          if parts.forall(_._2 == ujson.Null) then ujson.Null
          else
            ujson.Arr.from(
              parts.map(p => if p._2 == ujson.Null then ujson.Obj() else p._2)
            )
        )
      case other => (other, ujson.Null)
    go(fact)

  /** The inverse of `split`, for a reader that wants the whole fact back. */
  def merge(trusted: ujson.Value, data: ujson.Value): ujson.Value =
    (trusted, data) match
      case (ujson.Obj(t), ujson.Obj(d)) =>
        val out = ujson.Obj()
        t.foreach { (k, v) =>
          out(k) = d.get(k).map(merge(v, _)).getOrElse(v)
        }
        d.foreach { (k, v) => if !t.contains(k) then out(k) = v }
        out
      case (ujson.Arr(t), ujson.Arr(d)) =>
        ujson.Arr.from(t.toVector.zipWithIndex.map { (v, i) =>
          if i < d.size then merge(v, d(i)) else v
        })
      case (other, _) => other

  def write(value: ujson.Value): String = ujson.write(value)

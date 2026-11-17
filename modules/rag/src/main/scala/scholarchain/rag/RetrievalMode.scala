package scholarchain.rag

/** Which retrievers one workflow call runs. `Fused` is the behavior before this
  * type existed and stays the default, so a caller that names no mode gets
  * byte-identical output.
  *
  *   - `Graph` runs the graph and metric retrieval only. No vector index is
  *     read and no query text is embedded.
  *   - `Vector` runs the semantic (vector) retrieval only. No metric, path,
  *     component, community, ranking or Neptune read is made, not even the
  *     coauthor lookup that disambiguates a name. The answer can cite semantic
  *     context and, for a lookup, the identity record; it states no exact
  *     figure, because semantic context never supports one.
  *   - `Fused` runs both and assembles one evidence bundle.
  *
  * Every mode ends in the same synthesis and verification, so an unsupported
  * claim is dropped or weakened the same way in each.
  */
enum RetrievalMode(val wire: String):
  case Graph extends RetrievalMode("graph")
  case Vector extends RetrievalMode("vector")
  case Fused extends RetrievalMode("fused")

  def runsGraph: Boolean = this != RetrievalMode.Vector
  def runsVector: Boolean = this != RetrievalMode.Graph

object RetrievalMode:
  /** The mode of a caller that names none. */
  val Default: RetrievalMode = RetrievalMode.Fused

  /** The wire spellings, exact and lower case. */
  val wires: List[String] = RetrievalMode.values.toList.map(_.wire)

  /** `None` for any text that is not exactly `graph`, `vector` or `fused`. */
  def fromWire(text: String): Option[RetrievalMode] =
    RetrievalMode.values.find(_.wire == text)

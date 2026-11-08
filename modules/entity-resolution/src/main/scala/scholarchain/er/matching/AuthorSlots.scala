package scholarchain.er.matching

/** Where a mention sits in the author list of its work, comparably across
  * sources.
  *
  * Every Phase 4 normalizer (DBLP, OpenAlex, Crossref) sets `authorPosition` to
  * the one-based occurrence index in that source's author list, and rejects a
  * record outright rather than renumber it when an author is blank. The core
  * `WorkRecord` contract enforces it. So the zero-based index is
  * `authorPosition - 1` for all three sources. The coarse labels some sources
  * add (OpenAlex first/middle/last, Crossref first/additional) cannot order the
  * middle authors and are not used.
  *
  * Two sources can still disagree on the list itself (truncation, a different
  * order, a missing author), and then an index in one list names a different
  * person in the other. The indexes are compared only when the two lists have
  * the same length and the same person at every index.
  */
object AuthorSlots:
  private val familyAligned = 0.97
  private val forenameAligned = 0.7

  private def samePerson(a: String, b: String): Boolean =
    a == b || {
      val left = Names.parse(a)
      val right = Names.parse(b)
      !left.isEmpty && !right.isEmpty && {
        val comparison = NameComparison.compare(left, right)
        comparison.family >= familyAligned &&
        comparison.forename.forall(_ >= forenameAligned)
      }
    }

  /** The zero-based index of a mention in its work's author list, or None when
    * it is unknown, out of range, or does not name the author found there.
    */
  def index(side: SideRecord): Option[Int] =
    Option
      .when(side.kind == EntityKind.Researcher)(side.authorPosition)
      .flatten
      .map(_ - 1)
      .filter(i => i >= 0 && i < side.authorNames.size)
      .filter(i => samePerson(side.name, side.authorNames(i)))

  /** True when both author lists are the same list: equal length and the same
    * person at each index.
    */
  def listsAlign(left: SideRecord, right: SideRecord): Boolean =
    left.authorNames.nonEmpty &&
      left.authorNames.size == right.authorNames.size &&
      left.authorNames.zip(right.authorNames).forall(samePerson)

  /** Two slots of one work record: both are researcher mentions with the same
    * work ID (one source record), and both carry a known author position, and
    * the positions differ. Needs no DOI, arXiv ID or author list. An unknown
    * position is false, never a guess, and the same position is the same slot.
    */
  def distinctSlotsOfOneRecord(left: SideRecord, right: SideRecord): Boolean =
    left.kind == EntityKind.Researcher &&
      right.kind == EntityKind.Researcher &&
      left.workId == right.workId &&
      ((left.authorPosition, right.authorPosition) match
        case (Some(a), Some(b)) => a >= 1 && b >= 1 && a != b
        case _                  => false)

  /** The work-level key two mentions share, if any. A shared normalized DOI is
    * checked first. Otherwise a shared normalized arXiv ID counts, but not when
    * both sides name a work DOI and the two DOIs differ: that is a published
    * version distinct from the one the other side lists.
    */
  def sharedWorkKey(left: SideRecord, right: SideRecord): Option[WorkKey] =
    val doiShared = left.workDoi.isDefined && left.workDoi == right.workDoi
    val arxivShared =
      left.workArxiv.isDefined && left.workArxiv == right.workArxiv
    val doisDiffer =
      left.workDoi.isDefined && right.workDoi.isDefined &&
        left.workDoi != right.workDoi
    if doiShared then Some(WorkKey.Doi)
    else if arxivShared && !doisDiffer then Some(WorkKey.Arxiv)
    else None

  /** Two mentions of one work, shared by DOI or arXiv ID, at known and
    * different indexes of one aligned author list. The key that carried the
    * link says which rule code to report.
    */
  def distinctMentionsOfOneWork(
      left: SideRecord,
      right: SideRecord
  ): Option[WorkKey] =
    sharedWorkKey(left, right).filter(_ => distinctSlots(left, right))

  private def distinctSlots(left: SideRecord, right: SideRecord): Boolean =
    (index(left), index(right)) match
      case (Some(a), Some(b)) => a != b && listsAlign(left, right)
      case _                  => false

/** How two works were linked as one: by a shared DOI or a shared arXiv ID. */
enum WorkKey(val ruleCode: String, val reasonCode: String):
  case Doi
      extends WorkKey(
        "rule.same-doi-distinct-position",
        "rejected.same-doi-distinct-position"
      )
  case Arxiv
      extends WorkKey(
        "rule.same-arxiv-distinct-position",
        "rejected.same-arxiv-distinct-position"
      )

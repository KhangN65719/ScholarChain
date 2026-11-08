package scholarchain.tests.adversarial

import munit.FunSuite
import scholarchain.core.MatchOutcome
import scholarchain.er.matching.*
import scholarchain.tests.adversarial.ErFixtures.*

/** Same name, different people: a name match alone is never identity. */
class AdversarialErCommonNameSuite extends FunSuite:

  test("identical common names with nothing else in common are not accepted") {
    val a = person("a", "Wei Zhang", year = Some(2005), venue = Some("v1"))
    val b = person("b", "Wei Zhang", year = Some(2021), venue = Some("v2"))
    assertNotEquals(outcome(a, b), MatchOutcome.Accepted)
  }

  test("identical names, same year and venue, no coauthors: not accepted") {
    val a = person("a", "Wei Zhang")
    val b = person("b", "Wei Zhang")
    assertNotEquals(outcome(a, b), MatchOutcome.Accepted)
  }

  test("a mononym pair is never accepted on the name") {
    assertNotEquals(
      outcome(person("a", "Li"), person("b", "Li")),
      MatchOutcome.Accepted
    )
    assertNotEquals(
      outcome(person("a", "Madonna"), person("b", "Madonna")),
      MatchOutcome.Accepted
    )
  }

  test("empty names never merge") {
    assertNotEquals(
      outcome(person("a", ""), person("b", "")),
      MatchOutcome.Accepted
    )
  }

  test("an initial collision does not merge John and Jane Smith") {
    val shared = Vector("Alice Wong", "Bo Chen")
    val john = person("a", "John Smith", shared, Vector("MIT"))
    val jane = person("b", "Jane Smith", shared, Vector("MIT"))
    assertNotEquals(outcome(john, jane), MatchOutcome.Accepted)
  }

  test("J. Smith against two different full names stays unaccepted") {
    val initial = person("a", "J. Smith")
    assertNotEquals(
      outcome(initial, person("b", "Jane Smith")),
      MatchOutcome.Accepted
    )
    assertNotEquals(
      outcome(initial, person("c", "John Smith")),
      MatchOutcome.Accepted
    )
  }

  test("two mentions of one work are distinct people, even with equal names") {
    val a = person("a", "Wei Zhang", workId = Some("w-shared"))
    val b = person("b", "Wei Zhang", workId = Some("w-shared"))
    val r = decide(a, b)
    assertEquals(r.decision.decision, MatchOutcome.Rejected)
    assertEquals(r.path, MatchPath.DeterministicBibliographic)
  }

  test("a shared coauthor from a huge team does not prove identity") {
    val team = Vector.tabulate(300)(i => s"Member $i Name")
    val a = person("a", "Wei Zhang", team)
    val b = person("b", "Wei Zhang", team)
    assertNotEquals(outcome(a, b), MatchOutcome.Accepted)
  }

  test("different families are rejected however similar the rest is") {
    val shared = Vector("Alice Wong", "Bo Chen")
    val a = person("a", "Wei Zhang", shared, Vector("MIT"))
    val b = person("b", "Wei Zhao", shared, Vector("MIT"))
    assertNotEquals(outcome(a, b), MatchOutcome.Accepted)
  }

  test("a source reliability edge does not turn a name match into identity") {
    val a =
      person("a", "Wei Zhang", source = scholarchain.core.SourceType.Orcid)
    val b =
      person("b", "Wei Zhang", source = scholarchain.core.SourceType.Crossref)
    assertNotEquals(outcome(a, b), MatchOutcome.Accepted)
  }

  test("works: a generic short title with no authors is not accepted") {
    assertNotEquals(
      outcome(work("w1", "Introduction"), work("w2", "Introduction")),
      MatchOutcome.Accepted
    )
    assertNotEquals(
      outcome(work("w1", "Editorial"), work("w2", "Editorial")),
      MatchOutcome.Accepted
    )
  }

  test(
    "works: the same long title with different authors is not a rule accept"
  ) {
    val title = "A Very Long And Specific Survey Of Everything"
    val a = work("w1", title, Vector("Ada Lovelace"))
    val b = work("w2", title, Vector("Grace Hopper"))
    val r = decide(a, b)
    assertNotEquals(r.path, MatchPath.DeterministicBibliographic)
    assertNotEquals(r.decision.decision, MatchOutcome.Accepted)
  }

  test(
    "works: the same title and authors seven years apart is not a rule accept"
  ) {
    val title = "A Very Long And Specific Survey Of Everything"
    val a = work("w1", title, Vector("Ada Lovelace"), year = Some(2015))
    val b = work("w2", title, Vector("Ada Lovelace"), year = Some(2022))
    assertNotEquals(decide(a, b).path, MatchPath.DeterministicBibliographic)
  }

  test(
    "works: a one-year gap still matches the rule, a two-year gap does not"
  ) {
    val title = "A Very Long And Specific Survey Of Everything"
    def at(y: Int) = work(s"w$y", title, Vector("Ada Lovelace"), year = Some(y))
    assertEquals(outcome(at(2020), at(2021)), MatchOutcome.Accepted)
    assertNotEquals(
      decide(at(2020), at(2022)).path,
      MatchPath.DeterministicBibliographic
    )
  }

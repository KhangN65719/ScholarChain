package scholarchain.tests.analytics

import scala.collection.mutable

// Plain single-machine reference algorithms. They are run over the pipeline's
// own edge table, so they check the distributed results against a second,
// simple computation of the same definitions.
object Oracles:
  type Edge = (String, String)

  def neighbours(edges: Vector[Edge]): Map[String, Set[String]] =
    val both = edges.flatMap((a, b) => Vector(a -> b, b -> a))
    both.groupBy(_._1).view.mapValues(_.map(_._2).toSet).toMap

  def components(nodes: Set[String], edges: Vector[Edge]): Vector[Set[String]] =
    val adjacent = neighbours(edges)
    val seen = mutable.Set.empty[String]
    val found = mutable.ArrayBuffer.empty[Set[String]]
    nodes.toVector.sorted.foreach { start =>
      if !seen(start) then
        val members = distances(start, adjacent).keySet
        seen ++= members
        found += members
    }
    found.toVector

  // Breadth-first distances from a source.
  def distances(
      source: String,
      adjacent: Map[String, Set[String]]
  ): Map[String, Int] =
    val dist = mutable.Map(source -> 0)
    val queue = mutable.Queue(source)
    while queue.nonEmpty do
      val current = queue.dequeue()
      adjacent.getOrElse(current, Set.empty).foreach { next =>
        if !dist.contains(next) then
          dist(next) = dist(current) + 1
          queue.enqueue(next)
      }
    dist.toMap

  // Coreness by removing a minimum degree node at a time.
  def coreness(nodes: Set[String], edges: Vector[Edge]): Map[String, Int] =
    val adjacent = neighbours(edges)
    val degree = mutable.Map.from(
      nodes.map(n => n -> adjacent.getOrElse(n, Set.empty).size)
    )
    val remaining = mutable.Set.from(nodes)
    val core = mutable.Map.empty[String, Int]
    var level = 0
    while remaining.nonEmpty do
      val next = remaining.minBy(n => (degree(n), n))
      level = Math.max(level, degree(next))
      core(next) = level
      remaining -= next
      adjacent.getOrElse(next, Set.empty).foreach { other =>
        if remaining(other) then degree(other) = degree(other) - 1
      }
    core.toMap

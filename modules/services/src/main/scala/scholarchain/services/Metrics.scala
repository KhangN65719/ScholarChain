package scholarchain.services

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import scala.jdk.CollectionConverters.*

/** In-process counters, rendered as plain text. Labels are route patterns and
  * port names, never raw paths or identifiers, so cardinality stays bounded.
  */
final class Metrics:
  private val counters = new ConcurrentHashMap[String, LongAdder]

  private def clean(value: String): String =
    value.replace("\\", "").replace("\"", "").replace("\n", " ")

  def inc(name: String, labels: (String, String)*): Unit =
    val rendered = labels.map((k, v) => s"""$k="${clean(v)}"""").mkString(",")
    val key = if rendered.isEmpty then name else s"$name{$rendered}"
    counters.computeIfAbsent(key, _ => new LongAdder).increment()

  def render(extra: Seq[String] = Seq.empty): String =
    val lines = counters.asScala.toVector.map((k, v) => s"$k ${v.sum}").sorted
    (lines ++ extra).mkString("", "\n", "\n")

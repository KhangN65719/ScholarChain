package scholarchain.evaluation.runtime

/** Command-line arguments of the evaluation entry points: `--name value`
  * options and bare `--flag` switches. An option or switch the entry point does
  * not declare is refused by name (never ignored), so a misspelt flag cannot
  * change a run silently.
  */
final class EvalArgs private (
    private val options: Map[String, String],
    private val switches: Set[String]
):
  def flag(name: String): Boolean = switches.contains(name)
  def get(name: String): Option[String] = options.get(name)

  def required(name: String): String =
    options.getOrElse(
      name,
      throw new IllegalArgumentException(s"--$name is required")
    )

  def int(name: String, default: Int): Int =
    options
      .get(name)
      .map { v =>
        v.toIntOption.getOrElse(
          throw new IllegalArgumentException(
            s"--$name must be a whole number, got '$v'"
          )
        )
      }
      .getOrElse(default)

  def long(name: String, default: Long): Long =
    options
      .get(name)
      .map { v =>
        v.toLongOption.getOrElse(
          throw new IllegalArgumentException(
            s"--$name must be a whole number, got '$v'"
          )
        )
      }
      .getOrElse(default)

  def double(name: String): Option[Double] =
    options.get(name).map { v =>
      v.toDoubleOption
        .filter(_.isFinite)
        .getOrElse(
          throw new IllegalArgumentException(
            s"--$name must be a number, got '$v'"
          )
        )
    }

  def requiredDouble(name: String): Double =
    double(name).getOrElse(
      throw new IllegalArgumentException(s"--$name is required")
    )

  def names: Set[String] = options.keySet ++ switches

object EvalArgs:
  /** @param optionNames
    *   the `--name value` options the entry point accepts
    * @param switchNames
    *   the bare `--flag` switches it accepts
    */
  def parse(
      args: Seq[String],
      optionNames: Set[String],
      switchNames: Set[String]
  ): EvalArgs =
    val options = Map.newBuilder[String, String]
    val seen = scala.collection.mutable.HashSet.empty[String]
    val switches = Set.newBuilder[String]
    var i = 0
    while i < args.size do
      val raw = args(i)
      if !raw.startsWith("--") || raw.length < 3 then
        throw new IllegalArgumentException(s"unexpected argument '$raw'")
      val name = raw.drop(2)
      if !seen.add(name) then
        throw new IllegalArgumentException(s"--$name given twice")
      if switchNames.contains(name) then
        switches += name
        i += 1
      else if optionNames.contains(name) then
        if i + 1 >= args.size || args(i + 1).startsWith("--") then
          throw new IllegalArgumentException(s"--$name needs a value")
        options += name -> args(i + 1)
        i += 2
      else throw new IllegalArgumentException(s"unknown option --$name")
    new EvalArgs(options.result(), switches.result())

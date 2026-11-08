package scholarchain.tests.adversarial

import java.io.{ByteArrayInputStream, DataInputStream}
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scholarchain.spark.DriverGuard

// Reads the compiled classes of the Spark module and its sources without
// calling any of its code, in the style of the leakage scans of the ER module.
object SparkClassFiles:
  val sourceRoot: Path = Paths.get("modules/spark/src/main/scala")

  // The directory holding the compiled scholarchain/spark classes.
  def classesRoot: Path =
    val source = DriverGuard.getClass.getProtectionDomain.getCodeSource
    val root = Paths.get(source.getLocation.toURI)
    if !Files.isDirectory(root) then
      throw new AssertionError(s"expected a class directory, found $root")
    root

  private def walk(dir: Path, suffix: String): Vector[Path] =
    if !Files.isDirectory(dir) then Vector.empty
    else
      val stream = Files.walk(dir)
      try
        stream.iterator.asScala.toVector
          .filter(p => Files.isRegularFile(p) && p.toString.endsWith(suffix))
          .sortBy(_.toString)
      finally stream.close()

  def classFiles: Vector[Path] =
    walk(classesRoot.resolve("scholarchain/spark"), ".class")

  def scalaSources: Vector[Path] = walk(sourceRoot, ".scala")

  // Whole file as text, so constant pool strings and class names are found.
  def text(bytes: Array[Byte]): String = new String(bytes, ISO_8859_1)

  final case class Pool(
      utf8: Vector[String],
      methods: Vector[(String, String)]
  )

  // The Utf8 constants and the (owner, name) of every method reference of a
  // class file, read from its constant pool.
  def pool(bytes: Array[Byte]): Pool =
    val in = new DataInputStream(new ByteArrayInputStream(bytes))
    in.readInt()
    in.readUnsignedShort()
    in.readUnsignedShort()
    val count = in.readUnsignedShort()
    val utf = mutable.Map.empty[Int, String]
    val classes = mutable.Map.empty[Int, Int]
    val natures = mutable.Map.empty[Int, (Int, Int)]
    val refs = mutable.ArrayBuffer.empty[(Int, Int)]
    var index = 1
    while index < count do
      val tag = in.readUnsignedByte()
      tag match
        case 1     => utf(index) = in.readUTF()
        case 3 | 4 => in.skipNBytes(4)
        case 5 | 6 =>
          in.skipNBytes(8)
          index += 1
        case 7                => classes(index) = in.readUnsignedShort()
        case 8 | 16 | 19 | 20 => in.skipNBytes(2)
        case 9                => in.skipNBytes(4)
        case 10 | 11          =>
          refs += ((in.readUnsignedShort(), in.readUnsignedShort()))
        case 12 =>
          natures(index) = (in.readUnsignedShort(), in.readUnsignedShort())
        case 15      => in.skipNBytes(3)
        case 17 | 18 => in.skipNBytes(4)
        case other   => throw new AssertionError(s"constant pool tag $other")
      index += 1
    val resolved = refs.toVector.map { (owner, nature) =>
      (utf(classes(owner)), utf(natures(nature)._1))
    }
    Pool(utf.toVector.sortBy(_._1).map(_._2), resolved)

  // Removes block and line comments, keeping string literals.
  def withoutComments(source: String): String =
    val out = new StringBuilder
    var i = 0
    var inString = false
    while i < source.length do
      val c = source.charAt(i)
      val next = if i + 1 < source.length then source.charAt(i + 1) else ' '
      if inString then
        out.append(c)
        if c == '\\' && i + 1 < source.length then
          out.append(next)
          i += 1
        else if c == '"' then inString = false
        i += 1
      else if c == '/' && next == '/' then
        while i < source.length && source.charAt(i) != '\n' do i += 1
      else if c == '/' && next == '*' then
        i += 2
        while i + 1 < source.length &&
          !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')
        do i += 1
        i += 2
      else
        if c == '"' then inString = true
        out.append(c)
        i += 1
    out.toString

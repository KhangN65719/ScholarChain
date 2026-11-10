package scholarchain.hadoop

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path => NioPath}
import munit.FunSuite
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.{TaskAttemptID, TaskType}
import org.apache.hadoop.mapreduce.lib.input.FileSplit
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl
import scholarchain.hadoop.jobs.DblpXmlRecordReader
import scholarchain.hadoop.pipeline.XmlRecordStream

/** Drives `DblpXmlRecordReader` itself, through a real `FileSplit`, to pin the
  * look-back seek in `initialize`.
  *
  * Reverting that seek to `raw.seek(start)` starts every later split's scan at
  * its own boundary. A reader that begins inside a comment or CDATA section
  * then reads the region's tail as live markup and emits a ghost record. Each
  * boundary test below fails under that revert, and the control assertions
  * reproduce the reverted scan to show the ghost.
  */
class DblpXmlRecordReaderLookbackSuite extends FunSuite:
  private val real = """<article key="real/1"><title>Real.</title></article>"""
  private val ghostTag =
    """<article key="ghost/1"><title>G.</title></article>"""

  private def withFile[A](xml: String)(body: Path => A): A =
    val file: NioPath = Files.createTempFile("dblp-lookback", ".xml")
    try
      Files.write(file, xml.getBytes(UTF_8))
      body(new Path(file.toUri))
    finally Files.deleteIfExists(file)

  /** Records one reader emits for `[start, start + length)` of the file. */
  private def readSplit(
      path: Path,
      start: Long,
      length: Long
  ): Vector[(Long, String)] =
    val context = new TaskAttemptContextImpl(
      new Configuration(false),
      new TaskAttemptID("lookback", 1, TaskType.MAP, 0, 0)
    )
    val reader = new DblpXmlRecordReader
    try
      reader.initialize(
        new FileSplit(path, start, length, Array.empty),
        context
      )
      val out = Vector.newBuilder[(Long, String)]
      while reader.nextKeyValue() do
        out += (reader.getCurrentKey.get -> reader.getCurrentValue.toString)
      out.result()
    finally reader.close()

  /** Both splits of one boundary, in file order. */
  private def twoSplits(
      path: Path,
      length: Long,
      boundary: Long
  ): Vector[(Long, String)] =
    readSplit(path, 0L, boundary) ++ readSplit(
      path,
      boundary,
      length - boundary
    )

  /** What the reader would emit if it began scanning at `start`, which is the
    * behavior of the reverted `raw.seek(start)`.
    */
  private def revertedSplit(xml: String, start: Long): Vector[String] =
    val bytes = xml.getBytes(UTF_8)
    val input = new ByteArrayInputStream(bytes)
    input.skip(start)
    new XmlRecordStream(
      input,
      XmlRecordStream.allTags,
      start,
      start,
      bytes.length.toLong
    ).iterator.map(_._2).toVector

  private def byteLength(xml: String): Long = xml.getBytes(UTF_8).length.toLong

  private def checkRegion(label: String, xml: String): Unit =
    val length = byteLength(xml)
    val realOffset = xml.indexOf(real).toLong
    withFile(xml) { path =>
      // Every boundary, including the ones inside the region and inside the
      // ghost record's own tag.
      (0L to length).foreach { boundary =>
        assertEquals(
          twoSplits(path, length, boundary),
          Vector(realOffset -> real),
          s"$label: boundary $boundary"
        )
      }
      // The boundary that makes the revert visible: it lands exactly on the
      // ghost's opening `<`, inside the region.
      val ghostOffset = xml.indexOf(ghostTag).toLong
      assertEquals(readSplit(path, 0L, ghostOffset), Vector.empty, label)
      assertEquals(
        readSplit(path, ghostOffset, length - ghostOffset),
        Vector(realOffset -> real),
        label
      )
      // Control: the reverted scan emits the ghost, so this test would fail.
      assert(
        revertedSplit(xml, ghostOffset).contains(ghostTag),
        s"$label: control did not reproduce the reverted behavior"
      )
    }

  test(
    "a comment holding a record, split inside it, yields only the live one"
  ) {
    checkRegion("comment", s"<dblp><!-- $ghostTag -->$real</dblp>")
  }

  test("a CDATA section holding a record, split inside it, yields it once") {
    checkRegion("cdata", s"<dblp><![CDATA[ $ghostTag ]]>$real</dblp>")
  }

  test("a tight comment opener does not hide the region from the look-back") {
    checkRegion("tight", s"<dblp><!--$ghostTag-->$real</dblp>")
  }

  test("a region inside the look-back is recognized across a large file") {
    // The comment opens just under the look-back before the boundary.
    val filler = "x" * (XmlRecordStream.lookbackBytes.toInt - 4096)
    // The padding makes the reader's scan start non-zero, so the seek is used.
    val pad = "p" * 8192
    val xml = s"<dblp>$pad<!-- $filler$ghostTag -->$real</dblp>"
    val length = byteLength(xml)
    val ghostOffset = xml.indexOf(ghostTag).toLong
    val realOffset = xml.indexOf(real).toLong
    assert(ghostOffset - XmlRecordStream.lookbackBytes > 0)
    assert(ghostOffset - XmlRecordStream.lookbackBytes < xml.indexOf("<!--"))
    withFile(xml) { path =>
      assertEquals(
        twoSplits(path, length, ghostOffset),
        Vector(realOffset -> real)
      )
    }
  }

  test("a region opening beyond the look-back is not recognized (limit)") {
    // The comment opens more than 1 MiB before the boundary, so the later
    // reader begins inside it and cannot know. Its tail is read as live markup:
    // the ghost is emitted by that reader, exactly once, and the earlier reader
    // emits nothing. The live record after the region is still read once.
    val filler = "x" * (XmlRecordStream.lookbackBytes.toInt + 4096)
    val xml = s"<dblp><!-- $filler$ghostTag -->$real</dblp>"
    val length = byteLength(xml)
    val ghostOffset = xml.indexOf(ghostTag).toLong
    val realOffset = xml.indexOf(real).toLong
    withFile(xml) { path =>
      assertEquals(readSplit(path, 0L, ghostOffset), Vector.empty)
      assertEquals(
        twoSplits(path, length, ghostOffset),
        Vector(ghostOffset -> ghostTag, realOffset -> real)
      )
    }
  }

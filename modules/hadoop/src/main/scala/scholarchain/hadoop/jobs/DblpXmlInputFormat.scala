package scholarchain.hadoop.jobs

import java.io.InputStream
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.io.compress.{
  CompressionCodecFactory,
  SplittableCompressionCodec
}
import org.apache.hadoop.mapreduce.{
  InputSplit,
  JobContext,
  RecordReader,
  TaskAttemptContext
}
import org.apache.hadoop.mapreduce.lib.input.{FileInputFormat, FileSplit}
import scholarchain.hadoop.pipeline.XmlRecordStream

/** Splits DBLP XML on element boundaries rather than on byte offsets.
  *
  * This is the input format the specification's first requirement turns on:
  * "read complete DBLP publication records without splitting an XML record
  * across mappers". A plain `TextInputFormat` would hand a mapper a fragment
  * whenever a split landed inside an element.
  *
  * Splitting is permitted only for an uncompressed or splittably-compressed
  * file. A gzip-compressed dump — which is the published DBLP form — is not
  * splittable, so the whole file becomes one split and one mapper. That is a
  * correctness requirement, not a tuning choice: the compressed stream has no
  * addressable interior.
  */
class DblpXmlInputFormat extends FileInputFormat[LongWritable, Text]:
  override def isSplitable(context: JobContext, file: Path): Boolean =
    val codec =
      new CompressionCodecFactory(context.getConfiguration).getCodec(file)
    codec == null || codec.isInstanceOf[SplittableCompressionCodec]

  override def createRecordReader(
      split: InputSplit,
      context: TaskAttemptContext
  ): RecordReader[LongWritable, Text] =
    new DblpXmlRecordReader

object DblpXmlInputFormat:
  def recordTags(configuration: Configuration): Set[String] =
    Option(configuration.get(JobConfig.recordTags))
      .filter(_.trim.nonEmpty)
      .map(_.split(",").iterator.map(_.trim).filter(_.nonEmpty).toSet)
      .getOrElse(XmlRecordStream.allTags)

/** Yields one complete XML element per record.
  *
  * The key is the byte offset of the element's opening `<`, which is a stable
  * identifier for the record's position in the dump and makes a mapper's input
  * reproducible. The value is the element's full text.
  *
  * The reader claims a record if and only if its opening `<` falls inside this
  * split, and then reads past the split end to finish it. See
  * [[XmlRecordStream]] for why that yields exactly-once coverage.
  */
class DblpXmlRecordReader extends RecordReader[LongWritable, Text]:
  private var stream: XmlRecordStream = null
  private var input: InputStream = null
  private var start = 0L
  private var end = 0L
  private val key = new LongWritable
  private val value = new Text
  private var hasRecord = false

  override def initialize(
      split: InputSplit,
      context: TaskAttemptContext
  ): Unit =
    val fileSplit = split.asInstanceOf[FileSplit]
    val configuration = context.getConfiguration
    val path = fileSplit.getPath
    start = fileSplit.getStart
    end = start + fileSplit.getLength
    val fileSystem = path.getFileSystem(configuration)
    val raw = fileSystem.open(path)
    val codec = new CompressionCodecFactory(configuration).getCodec(path)
    // The scan begins before the split so the reader learns whether the split
    // starts inside a comment or CDATA section; see `XmlRecordStream`.
    val scanStart =
      if codec == null then math.max(0L, start - XmlRecordStream.lookbackBytes)
      else 0L
    input = if codec == null then
      raw.seek(scanStart)
      raw
    else
      // A compressed file is not split by `isSplitable`, so the single split
      // starts at zero and the decompressed stream is read from its head.
      require(start == 0L, "A compressed DBLP file must form a single split")
      codec.createInputStream(raw)
    stream = new XmlRecordStream(
      input,
      DblpXmlInputFormat.recordTags(configuration),
      scanStart,
      start,
      end
    )

  override def nextKeyValue(): Boolean =
    stream.next() match
      case Some((offset, record)) =>
        key.set(offset)
        value.set(record)
        hasRecord = true
        true
      case None =>
        hasRecord = false
        false

  override def getCurrentKey: LongWritable = key

  override def getCurrentValue: Text = value

  override def getProgress: Float =
    if end <= start then 0.0f
    else
      math.max(
        0.0f,
        math.min(
          1.0f,
          (stream.currentPosition - start).toFloat / (end - start).toFloat
        )
      )

  override def close(): Unit =
    if input != null then input.close()

package scholarchain.hadoop.pipeline

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.{Element, Node}
import org.xml.sax.{ErrorHandler, SAXParseException}

/** A hardened DOM reader for one standalone XML record.
  *
  * Security posture, which matters because the parser runs on untrusted dump
  * bytes inside every map task: DOCTYPE declarations are refused outright, so
  * no named entity, parameter entity or billion-laughs expansion is reachable,
  * and no external resource of any kind is resolved. DBLP's published named
  * entities (`&uuml;` and the rest of the HTML 4 set) are rewritten to numeric
  * character references by [[DblpEntities]] before parsing, so no DTD is
  * loaded. Any other entity name is undeclared and is a reject, not a fetch.
  *
  * A `DocumentBuilderFactory` is created per parse. It is not thread-safe, and
  * sharing one across tasks would be the mutable shared state the job
  * specification forbids.
  */
object XmlFragment:
  private def factory(): DocumentBuilderFactory =
    val created = DocumentBuilderFactory.newInstance()
    created.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    created.setFeature(
      "http://apache.org/xml/features/disallow-doctype-decl",
      true
    )
    created.setFeature(
      "http://xml.org/sax/features/external-general-entities",
      false
    )
    created.setFeature(
      "http://xml.org/sax/features/external-parameter-entities",
      false
    )
    created.setFeature(
      "http://apache.org/xml/features/nonvalidating/load-external-dtd",
      false
    )
    created.setXIncludeAware(false)
    created.setExpandEntityReferences(false)
    created.setNamespaceAware(false)
    created.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    created.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    created

  private object Strict extends ErrorHandler:
    def warning(error: SAXParseException): Unit = ()
    def error(error: SAXParseException): Unit = throw error
    def fatalError(error: SAXParseException): Unit = throw error

  /** Parses one record. Returns `Left` with a diagnostic instead of throwing,
    * because a malformed record is expected traffic that belongs in the reject
    * output.
    */
  def parse(xml: String): Either[String, Element] =
    try
      val builder = factory().newDocumentBuilder()
      builder.setErrorHandler(Strict)
      builder.setEntityResolver((_, _) =>
        throw new SAXParseException(
          "External entity resolution is disabled",
          null
        )
      )
      val document =
        builder.parse(
          new ByteArrayInputStream(DblpEntities.resolve(xml).getBytes(UTF_8))
        )
      Option(document.getDocumentElement)
        .toRight("Record has no document element")
    catch
      case error: Exception =>
        Left(
          Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
        )

  def children(element: Element, name: String): Vector[Element] =
    val nodes = element.getChildNodes
    (0 until nodes.getLength).iterator
      .map(nodes.item)
      .collect {
        case node: Element if node.getTagName == name => node
      }
      .toVector

  /** Direct children in document order, which is the author sequence for DBLP
    * and must not be reordered.
    */
  def childElements(element: Element): Vector[Element] =
    val nodes = element.getChildNodes
    (0 until nodes.getLength).iterator
      .map(nodes.item)
      .collect { case node: Element => node }
      .toVector

  def child(element: Element, name: String): Option[Element] =
    children(element, name).headOption

  /** Concatenated descendant text. Inline markup such as `<i>` inside a DBLP
    * title contributes its text, so the title is preserved rather than
    * truncated at the first markup element.
    */
  def text(node: Node): String =
    val builder = new StringBuilder
    def walk(current: Node): Unit =
      if current.getNodeType == Node.TEXT_NODE ||
        current.getNodeType == Node.CDATA_SECTION_NODE
      then builder.append(current.getNodeValue)
      else
        val nodes = current.getChildNodes
        (0 until nodes.getLength).foreach(index => walk(nodes.item(index)))
    walk(node)
    TextNormalizer.collapseWhitespace(builder.toString)

  def textOf(element: Element, name: String): Option[String] =
    child(element, name).map(text).filter(_.nonEmpty)

  def textsOf(element: Element, name: String): Vector[String] =
    children(element, name).map(text).filter(_.nonEmpty)

  def attribute(element: Element, name: String): Option[String] =
    Option(element.getAttribute(name))
      .map(TextNormalizer.collapseWhitespace)
      .filter(_.nonEmpty)

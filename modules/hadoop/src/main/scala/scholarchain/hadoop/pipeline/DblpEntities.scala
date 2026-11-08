package scholarchain.hadoop.pipeline

import scala.util.matching.Regex

/** DBLP's named character entities, resolved without a DTD.
  *
  * The full DBLP dump uses named entities (`&uuml;`, `&eacute;`, ...) that
  * `dblp.dtd` declares: 65 of them (`reg`, `micro`, `times` and the Latin-1
  * letters Agrave to yuml without the division sign). The parser refuses
  * DOCTYPE declarations outright, so those names would otherwise be undeclared.
  * Instead of loading the DTD, each known name is rewritten to the equivalent
  * numeric character reference before parsing. No entity is ever declared,
  * expanded or fetched, so the XXE and billion-laughs posture of
  * [[XmlFragment]] is unchanged.
  *
  * Only names in [[table]] are rewritten. Any other name stays as written and
  * the parser rejects the record as an undeclared entity, which becomes a
  * counted `MalformedXml` reject rather than a silent drop. The five XML
  * predefined entities are deliberately absent: the parser already owns them.
  *
  * The table is the W3C HTML 4 entity set (Latin-1, symbols and special
  * characters) minus the XML predefined names: 248 names. It is a superset of
  * the DTD: 183 of the 248 names are not declared in `dblp.dtd` (for example
  * `divide`), so they are accepted here although the DTD would not define them.
  * The table was written from the published HTML 4 DTD and was compared with
  * the pinned `dblp.dtd` by hand in Phase 5 repair round 1. The DTD file is not
  * vendored in the repository; `DblpDtdEntitiesSuite` hand-transcribes its 65
  * declared names and codes and asserts each is in the table with the same code
  * and that exactly 183 names are extra. The comparison is therefore only as
  * accurate as that transcription.
  */
object DblpEntities:
  private val latin1: Vector[String] =
    ("nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr " +
      "deg plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest " +
      "Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml " +
      "Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times " +
      "Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig " +
      "agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml " +
      "igrave iacute icirc iuml eth ntilde ograve oacute ocirc otilde ouml divide " +
      "oslash ugrave uacute ucirc uuml yacute thorn yuml").split(" ").toVector

  private val greekUpper: Vector[String] =
    ("Alpha Beta Gamma Delta Epsilon Zeta Eta Theta Iota Kappa Lambda Mu Nu Xi Omicron Pi Rho")
      .split(" ")
      .toVector

  private val greekUpperTail: Vector[String] =
    "Sigma Tau Upsilon Phi Chi Psi Omega".split(" ").toVector

  private val greekLower: Vector[String] =
    ("alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho " +
      "sigmaf sigma tau upsilon phi chi psi omega").split(" ").toVector

  private val others: Map[String, Int] = Map(
    "OElig" -> 338,
    "oelig" -> 339,
    "Scaron" -> 352,
    "scaron" -> 353,
    "Yuml" -> 376,
    "fnof" -> 402,
    "circ" -> 710,
    "tilde" -> 732,
    "thetasym" -> 977,
    "upsih" -> 978,
    "piv" -> 982,
    "ensp" -> 8194,
    "emsp" -> 8195,
    "thinsp" -> 8201,
    "zwnj" -> 8204,
    "zwj" -> 8205,
    "lrm" -> 8206,
    "rlm" -> 8207,
    "ndash" -> 8211,
    "mdash" -> 8212,
    "lsquo" -> 8216,
    "rsquo" -> 8217,
    "sbquo" -> 8218,
    "ldquo" -> 8220,
    "rdquo" -> 8221,
    "bdquo" -> 8222,
    "dagger" -> 8224,
    "Dagger" -> 8225,
    "bull" -> 8226,
    "hellip" -> 8230,
    "permil" -> 8240,
    "prime" -> 8242,
    "Prime" -> 8243,
    "lsaquo" -> 8249,
    "rsaquo" -> 8250,
    "oline" -> 8254,
    "frasl" -> 8260,
    "euro" -> 8364,
    "image" -> 8465,
    "weierp" -> 8472,
    "real" -> 8476,
    "trade" -> 8482,
    "alefsym" -> 8501,
    "larr" -> 8592,
    "uarr" -> 8593,
    "rarr" -> 8594,
    "darr" -> 8595,
    "harr" -> 8596,
    "crarr" -> 8629,
    "lArr" -> 8656,
    "uArr" -> 8657,
    "rArr" -> 8658,
    "dArr" -> 8659,
    "hArr" -> 8660,
    "forall" -> 8704,
    "part" -> 8706,
    "exist" -> 8707,
    "empty" -> 8709,
    "nabla" -> 8711,
    "isin" -> 8712,
    "notin" -> 8713,
    "ni" -> 8715,
    "prod" -> 8719,
    "sum" -> 8721,
    "minus" -> 8722,
    "lowast" -> 8727,
    "radic" -> 8730,
    "prop" -> 8733,
    "infin" -> 8734,
    "ang" -> 8736,
    "and" -> 8743,
    "or" -> 8744,
    "cap" -> 8745,
    "cup" -> 8746,
    "int" -> 8747,
    "there4" -> 8756,
    "sim" -> 8764,
    "cong" -> 8773,
    "asymp" -> 8776,
    "ne" -> 8800,
    "equiv" -> 8801,
    "le" -> 8804,
    "ge" -> 8805,
    "sub" -> 8834,
    "sup" -> 8835,
    "nsub" -> 8836,
    "sube" -> 8838,
    "supe" -> 8839,
    "oplus" -> 8853,
    "otimes" -> 8855,
    "perp" -> 8869,
    "sdot" -> 8901,
    "lceil" -> 8968,
    "rceil" -> 8969,
    "lfloor" -> 8970,
    "rfloor" -> 8971,
    "lang" -> 9001,
    "rang" -> 9002,
    "loz" -> 9674,
    "spades" -> 9824,
    "clubs" -> 9827,
    "hearts" -> 9829,
    "diams" -> 9830
  )

  /** Entity name to Unicode code point. */
  val table: Map[String, Int] =
    latin1.zipWithIndex.map { case (name, index) =>
      name -> (160 + index)
    }.toMap ++
      greekUpper.zipWithIndex.map { case (name, index) =>
        name -> (913 + index)
      } ++
      greekUpperTail.zipWithIndex.map { case (name, index) =>
        name -> (931 + index)
      } ++
      greekLower.zipWithIndex.map { case (name, index) =>
        name -> (945 + index)
      } ++
      others

  private val reference: Regex = "&([A-Za-z][A-Za-z0-9]*);".r
  private val cdataOpen = "<![CDATA["
  private val cdataClose = "]]>"

  private def rewrite(segment: String): String =
    if !segment.contains('&') then segment
    else
      reference.replaceAllIn(
        segment,
        found =>
          table.get(found.group(1)) match
            case Some(codePoint) =>
              Regex.quoteReplacement(s"&#$codePoint;")
            case None => Regex.quoteReplacement(found.matched)
      )

  /** Rewrites known named entities to numeric references. CDATA sections are
    * left untouched, because `&uuml;` inside one is literal text.
    */
  def resolve(xml: String): String =
    if !xml.contains('&') then xml
    else
      val out = new StringBuilder
      var from = 0
      var done = false
      while !done do
        val open = xml.indexOf(cdataOpen, from)
        if open < 0 then
          out.append(rewrite(xml.substring(from)))
          done = true
        else
          out.append(rewrite(xml.substring(from, open)))
          val close = xml.indexOf(cdataClose, open)
          if close < 0 then
            out.append(xml.substring(open))
            done = true
          else
            val end = close + cdataClose.length
            out.append(xml.substring(open, end))
            from = end
      out.toString

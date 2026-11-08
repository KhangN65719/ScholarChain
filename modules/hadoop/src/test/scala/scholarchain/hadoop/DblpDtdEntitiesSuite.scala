package scholarchain.hadoop

import munit.FunSuite
import scholarchain.hadoop.pipeline.DblpEntities

/** Phase 5 review pending check: the entity table against the pinned
  * `dblp.dtd`.
  *
  * The DTD (dated 2023-05-10, 13134 bytes, kept outside the repository at
  * `scholarchain-data/dblp/dblp.dtd`) declares 65 general entities: `reg`,
  * `micro`, `times` and the 62 Latin-1 letters Agrave to yuml without the
  * division sign. The list below is transcribed from those `<!ENTITY>` lines.
  * It is a hand transcription, not a copy of the file, so the test needs no
  * file outside the repository.
  */
class DblpDtdEntitiesSuite extends FunSuite:
  private val declared: Map[String, Int] =
    ("reg=174 micro=181 times=215 " +
      "Agrave=192 Aacute=193 Acirc=194 Atilde=195 Auml=196 Aring=197 AElig=198 " +
      "Ccedil=199 Egrave=200 Eacute=201 Ecirc=202 Euml=203 Igrave=204 Iacute=205 " +
      "Icirc=206 Iuml=207 ETH=208 Ntilde=209 Ograve=210 Oacute=211 Ocirc=212 " +
      "Otilde=213 Ouml=214 Oslash=216 Ugrave=217 Uacute=218 Ucirc=219 Uuml=220 " +
      "Yacute=221 THORN=222 szlig=223 agrave=224 aacute=225 acirc=226 atilde=227 " +
      "auml=228 aring=229 aelig=230 ccedil=231 egrave=232 eacute=233 ecirc=234 " +
      "euml=235 igrave=236 iacute=237 icirc=238 iuml=239 eth=240 ntilde=241 " +
      "ograve=242 oacute=243 ocirc=244 otilde=245 ouml=246 oslash=248 ugrave=249 " +
      "uacute=250 ucirc=251 uuml=252 yacute=253 thorn=254 yuml=255")
      .split(" ")
      .map { pair =>
        val Array(name, code) = pair.split("=")
        name -> code.toInt
      }
      .toMap

  test("the transcription holds the 65 entities the DTD declares") {
    assertEquals(declared.size, 65)
  }

  test("every entity the DTD declares is in the table with the same code") {
    val wrong = declared.filter { case (name, code) =>
      !DblpEntities.table.get(name).contains(code)
    }
    assertEquals(wrong, Map.empty[String, Int])
  }

  test("the table is a documented superset: 183 HTML 4 names the DTD omits") {
    val extra = DblpEntities.table.keySet.diff(declared.keySet)
    assertEquals(extra.size, 183)
    // The division sign is the one Latin-1 letter-range name the DTD skips.
    assert(extra.contains("divide"))
    assertEquals(DblpEntities.table.size, 248)
  }

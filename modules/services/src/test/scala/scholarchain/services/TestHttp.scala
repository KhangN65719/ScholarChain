package scholarchain.services

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import scala.jdk.CollectionConverters.*
import scholarchain.services.runtime.LocalRuntime

final case class Resp(status: Int, headers: Map[String, String], body: String):
  def header(name: String): Option[String] = headers.get(name.toLowerCase)
  def json: ujson.Value = ujson.read(body)

object TestHttp:
  private val client = HttpClient
    .newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .connectTimeout(Duration.ofSeconds(5))
    .build()

  def send(
      method: String,
      url: String,
      body: Option[String] = None,
      headers: Map[String, String] = Map.empty
  ): Resp =
    val publisher = body.fold(HttpRequest.BodyPublishers.noBody())(b =>
      HttpRequest.BodyPublishers.ofString(b, UTF_8)
    )
    val builder = HttpRequest
      .newBuilder(URI.create(url))
      .timeout(Duration.ofSeconds(30))
      .method(method, publisher)
    headers.foreach((k, v) => builder.header(k, v))
    val response =
      client.send(builder.build(), HttpResponse.BodyHandlers.ofString(UTF_8))
    val hs = response
      .headers()
      .map()
      .asScala
      .toMap
      .collect { case (k, v) if !v.isEmpty => k.toLowerCase -> v.get(0) }
    Resp(response.statusCode(), hs, response.body())

/** One local runtime with the helpers a test needs. */
final class Rt(val runtime: LocalRuntime):
  def fast: String = runtime.fastUrl
  def slow: String = runtime.slowUrl
  private def bearer(token: String) = Map("Authorization" -> s"Bearer $token")

  def get(
      base: String,
      path: String,
      token: Option[String] = None
  ): Resp =
    TestHttp.send(
      "GET",
      base + path,
      None,
      bearer(token.getOrElse(runtime.readToken))
    )

  def getFast(path: String, token: Option[String] = None): Resp =
    get(fast, path, token)

  def getSlow(path: String, token: Option[String] = None): Resp =
    get(slow, path, token)

  def post(
      body: String,
      headers: Map[String, String] = Map.empty,
      token: Option[String] = None,
      contentType: String = "application/json"
  ): Resp =
    TestHttp.send(
      "POST",
      slow + "/v1/query",
      Some(body),
      bearer(token.getOrElse(runtime.queryToken)) ++
        Map("Content-Type" -> contentType) ++ headers
    )

  def ask(
      question: String,
      extra: Map[String, ujson.Value] = Map.empty,
      headers: Map[String, String] = Map.empty
  ): Resp =
    val o = ujson.Obj("question" -> question)
    extra.foreach((k, v) => o(k) = v)
    post(ujson.write(o), headers)

  def anonymous(base: String, path: String): Resp =
    TestHttp.send("GET", base + path)

  def close(): Unit = runtime.close()

object Fixture:
  val ada =
    "How many distinct coauthors does Ada Quillfeather have in 2000-2009?"
  val boris =
    "How many distinct coauthors does Boris Marlowe have in 2000-2009?"
  val nobody =
    "How many distinct coauthors does Zzyzx Qwertyuiop have in 2000-2009?"

  def start(overrides: Map[String, String] = Map.empty): Rt =
    new Rt(LocalRuntime.start(overrides))

  def keysDeep(v: ujson.Value): Set[String] = v match
    case ujson.Obj(m) => m.keySet.toSet ++ m.values.flatMap(keysDeep)
    case ujson.Arr(a) => a.flatMap(keysDeep).toSet
    case _            => Set.empty

  def objects(v: ujson.Value): Vector[ujson.Obj] = v match
    case o: ujson.Obj => Vector(o) ++ o.value.values.toVector.flatMap(objects)
    case a: ujson.Arr => a.value.toVector.flatMap(objects)
    case _            => Vector.empty

  def strings(v: ujson.Value): Vector[String] = v match
    case ujson.Str(s) => Vector(s)
    case o: ujson.Obj => o.value.values.toVector.flatMap(strings)
    case a: ujson.Arr => a.value.toVector.flatMap(strings)
    case _            => Vector.empty

  def citedEvidenceIds(answer: ujson.Value): Vector[String] =
    answer("claims").arr.toVector
      .flatMap(c => c("evidenceIds").arr.toVector.map(_.str))
      .distinct

  def bundleIds(answer: ujson.Value): Set[String] =
    answer("evidence")("items").arr.map(_("evidenceId").str).toSet

  private val researcherId =
    "researcher:v1:orcid:[A-Za-z0-9_-]+".r

  def researcherIds(text: String): Vector[String] =
    researcherId.findAllIn(text).toVector.distinct

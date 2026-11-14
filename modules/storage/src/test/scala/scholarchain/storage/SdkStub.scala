package scholarchain.storage

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import scala.collection.mutable
import software.amazon.awssdk.awscore.exception.{
  AwsErrorDetails,
  AwsServiceException
}

/** A stubbed SDK client: a dynamic proxy over the client interface, so no AWS
  * call can happen. A method with no handler fails the test. Every call is
  * recorded as (method name, first argument).
  */
final class SdkStub[C](iface: Class[C]):
  val calls = mutable.ArrayBuffer.empty[(String, AnyRef)]
  private val handlers = mutable.Map.empty[String, AnyRef => AnyRef]

  def on(name: String)(handler: AnyRef => AnyRef): SdkStub[C] =
    handlers.update(name, handler)
    this

  def requests(name: String): Vector[AnyRef] =
    calls.toVector.filter(_._1 == name).map(_._2)

  def names: Vector[String] = calls.toVector.map(_._1)

  private val seconds = mutable.ArrayBuffer.empty[(String, AnyRef)]

  /** The second argument of each call of a method (a request body, say). */
  def secondArguments(name: String): Vector[AnyRef] =
    seconds.toVector.filter(_._1 == name).map(_._2)

  val client: C = Proxy
    .newProxyInstance(
      iface.getClassLoader,
      Array[Class[?]](iface),
      new InvocationHandler:
        def invoke(proxy: Object, method: Method, args: Array[Object]): Object =
          method.getName match
            case "close"       => null
            case "serviceName" => "stub"
            case "toString"    => s"SdkStub(${iface.getSimpleName})"
            case "hashCode" => Integer.valueOf(System.identityHashCode(proxy))
            case "equals"   => java.lang.Boolean.valueOf(proxy eq args(0))
            case name       =>
              val first = if args == null || args.isEmpty then null else args(0)
              calls += ((name, first))
              if args != null && args.length > 1 then
                seconds += ((name, args(1)))
              handlers.get(name) match
                case Some(handler) => handler(first)
                case None          =>
                  throw new AssertionError(s"Unexpected SDK call $name")
    )
    .asInstanceOf[C]

object SdkErrors:
  def error(status: Int, code: String): AwsServiceException =
    AwsServiceException
      .builder()
      .statusCode(status)
      .message(s"stub $code")
      .awsErrorDetails(
        AwsErrorDetails.builder().errorCode(code).errorMessage(code).build()
      )
      .build()
      .asInstanceOf[AwsServiceException]

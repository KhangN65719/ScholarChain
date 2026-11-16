package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** Work order 89 item F1: `ObjectStore.listSizes`, the listing that reads no
  * per-object metadata. The AWS adapter is covered in AwsObjectStoreSuite.
  */
class ObjectStoreListSizesSuite extends munit.FunSuite:
  private val a = "semantic-context/documents/a.json"
  private val b = "semantic-context/documents/b.json"
  private val other = "curated/x/y.json"

  private def fill(store: ObjectStore): Unit =
    store.putImmutable(b, "bbbbbb".getBytes(UTF_8), "ref:b")
    store.putImmutable(a, "aaa".getBytes(UTF_8), "ref:a")
    store.putImmutable(other, "o".getBytes(UTF_8), "ref:o")
    ()

  private val expected = Vector((a, 3L), (b, 6L))

  test("the default derives listSizes from list, so an old store compiles") {
    var lists = 0
    val old = new ObjectStore:
      protected def write(k: String, bytes: Array[Byte], ref: String): Unit = ()
      def head(key: String): Option[ObjectMeta] = None
      def get(key: String): Option[Array[Byte]] = None
      def list(prefix: String): Vector[ObjectMeta] =
        lists += 1
        Vector(ObjectMeta("k2", 7L, "s", "r"), ObjectMeta("k1", 4L, "s", "r"))
          .filter(_.key.startsWith(prefix))
    assertEquals(old.listSizes("k"), Vector(("k2", 7L), ("k1", 4L)))
    assertEquals(old.listSizes("zzz"), Vector.empty)
    assertEquals(lists, 2)
  }

  test("InMemoryObjectStore listSizes matches list, sorted, prefix-filtered") {
    val store = new InMemoryObjectStore
    fill(store)
    assertEquals(store.listSizes("semantic-context/documents/"), expected)
    assertEquals(
      store.listSizes(""),
      store.list("").map(m => (m.key, m.bytes))
    )
  }

  test("LocalFsObjectStore listSizes matches list and skips sidecar files") {
    val dir = Files.createTempDirectory("listsizes")
    val store = new LocalFsObjectStore(dir)
    fill(store)
    assertEquals(store.listSizes("semantic-context/documents/"), expected)
    assertEquals(
      store.listSizes(""),
      store.list("").map(m => (m.key, m.bytes))
    )
    assertEquals(store.listSizes("missing/"), Vector.empty)
    assertEquals(
      new LocalFsObjectStore(dir.resolve("absent")).listSizes(""),
      Vector.empty
    )
  }

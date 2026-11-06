package scholarchain.core

import upickle.default.{ReadWriter, read, write}

object ContractWire:
  def encode[A: ReadWriter](record: A): String =
    require(record != null, "A wire record must be non-null")
    write(record)

  def decode[A: ReadWriter](json: String): A =
    val record = read[A](json)
    require(record != null, "A wire record must be non-null")
    record

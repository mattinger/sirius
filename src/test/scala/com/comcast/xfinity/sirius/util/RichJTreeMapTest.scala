package com.comcast.xfinity.sirius.util

import com.comcast.xfinity.sirius.NiceTest

class RichJTreeMapTest extends NiceTest {

  it ("must properly implement foreach, applying the passed in function to each kv in order") {
    val underTest = new RichJTreeMap[String, String]()
    underTest.put("hello", "world")
    underTest.put("why", "do the dbas spontaneously change ports?")
    underTest.put("sincerely", "developers")

    var accum = List[(String, String)]()
    underTest.foreach((k, v) => accum = ((k, v) :: accum))

    // Note this is the sorted result of the above inserts
    val expected = List(
      ("hello", "world"),
      ("sincerely", "developers"),
      ("why", "do the dbas spontaneously change ports?")
    )
    assert(expected === accum.reverse)
  }

  it ("must properly implement filter, mutating the underlying collection") {
    val underTest = new RichJTreeMap[String, String]()
    underTest.put("Well", "I'll tell you why")
    underTest.put("It's", "To keep us on our toes")

    underTest.filter((k, v) => k == "It's" && v == "To keep us on our toes")

    assert(1 === underTest.size)
    assert("To keep us on our toes" === underTest.get("It's"))
  }

  it ("must properly implement dropWhile, mutating the underlying collection " +
      "and not doing more work than is necessary") {
    val underTest = new RichJTreeMap[String, String]()
    underTest.put("A", "1")
    underTest.put("B", "2")
    underTest.put("C", "3")

    var lastCheckedKV: Option[(String, String)] = None

    underTest.dropWhile(
      (k, v) => {
        lastCheckedKV = Some((k, v))
        k != "B" && v != "2"
      }
    )

    assert(2 === underTest.size)
    assert("2" === underTest.get("B"))
    assert("3" === underTest.get("C"))
    assert(Some(("B", "2")) === lastCheckedKV)
  }
}
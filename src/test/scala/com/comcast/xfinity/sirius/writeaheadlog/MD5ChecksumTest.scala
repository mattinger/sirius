package com.comcast.xfinity.sirius.writeaheadlog

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FunSpec}
import org.mockito.Mockito._
import java.security.MessageDigest
import org.apache.commons.codec.binary.Base64

@RunWith(classOf[JUnitRunner])
class MD5ChecksumTest extends FunSpec with BeforeAndAfter {

  var checksumGenerator: MD5Checksum = _

  var mockMessageDigest: MessageDigest = _
  var mockCodec: Base64 = _

  val HASH = "some hash".getBytes()
  val ENCODED_HASH = "some encoded hash"
  val DATA = "some thing to checksum"


  before {
    mockCodec = mock(classOf[Base64])
    mockMessageDigest = mock(classOf[MessageDigest])
    checksumGenerator = new MD5ChecksumForTesting(mockMessageDigest)
    checksumGenerator.checksumCodec = mockCodec

  }

  describe("An MD5Checksum") {
    it("should generate a checksum that verfies") {
      when(mockMessageDigest.digest(DATA.getBytes())).thenReturn(HASH)
      when(mockCodec.encodeToString(HASH)).thenReturn(ENCODED_HASH)

      val checksum = checksumGenerator.generateChecksum(DATA)

      assert(checksum === ENCODED_HASH)
      assert(checksumGenerator.validateChecksum(DATA, checksum))
    }


    it("should not verify a tampered with checksum") {
      var checksum = checksumGenerator.generateChecksum(DATA)
      checksum += "ggg"

      assert(!checksumGenerator.validateChecksum(DATA, checksum))
    }

    it("should be using an MD5 MessageDigest") {
      val digest = new MD5Checksum() {}.getMessageDigest()
      assert(digest.toString().startsWith("MD5"))
    }

  }

  class MD5ChecksumForTesting(mD: MessageDigest) extends MD5Checksum {
    override def getMessageDigest(): MessageDigest = {
      mD
    }

  }

}

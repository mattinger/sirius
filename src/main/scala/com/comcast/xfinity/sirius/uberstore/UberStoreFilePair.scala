package com.comcast.xfinity.sirius.uberstore

import com.comcast.xfinity.sirius.api.impl.OrderedEvent
import com.comcast.xfinity.sirius.writeaheadlog.SiriusLog
import data.UberDataFile
import seqindex.{PersistedSeqIndex, SeqIndex}
import com.comcast.xfinity.sirius.uberstore.seqindex.DiskOnlySeqIndex

object UberStoreFilePair {

  /**
   * Create an UberStoreFilePair based in baseDir.  baseDir is NOT
   * created, it must exist. The files within baseDir will
   * be created if they do not exist however.
   *
   * @param baseDir directory to base the UberStoreFilePair in
   * @param useMemBackedIndex true to use PersistedSeqIndex, false
   *            to use DiskOnlySeqIndex.  The former offers less
   *            disk activity at the expense of higher memory
   *            overhead (roughly 64b per entry), where the latter
   *            offers lower memory overhead (effectively none)
   *            at the expense of more disk activity. In theory
   *            the higher disk activity of DiskOnlySeqIndex should
   *            be negated by the operating system filesystem cache.
   *            Default is true (use PersistedSeqIndex).
   *
   * @return an instantiated UberStoreFilePair
   */
  def apply(baseDir: String, startingSeq: Long, useMemBackedIndex: Boolean = true): UberStoreFilePair = {
    val baseName = "%s/%s".format(baseDir, startingSeq)
    val dataFile = UberDataFile("%s.data".format(baseName))
    val index = {
      val indexFileName = "%s.index".format(baseName)
      if (useMemBackedIndex) {
        PersistedSeqIndex(indexFileName)
      } else {
        DiskOnlySeqIndex(indexFileName)
      }
    }
    repairIndex(index, dataFile)
    new UberStoreFilePair(dataFile, index)
  }

  /**
   * Recovers missing entries at the end of index from dataFile.
   *
   * Assumes that UberDataFile is proper, that is events are there in order,
   * and there are no dups.
   *
   * Has the side effect of updating index.
   *
   * @param index the SeqIndex to update
   * @param dataFile the UberDataFile to update
   */
  private[uberstore] def repairIndex(index: SeqIndex, dataFile: UberDataFile) {
    val (includeFirst, lastOffset) = index.getMaxSeq match {
      case None => (true, 0L)
      case Some(seq) => (false, index.getOffsetFor(seq).get) // has to exist
    }

    dataFile.foldLeftRange(lastOffset, Long.MaxValue)(includeFirst) (
      (shouldInclude, off, evt) => {
        if (shouldInclude) {
          index.put(evt.sequence, off)
        }
        true
      }
    )

  }
}

/**
 * THIS SHOULD NOT BE USED DIRECTLY, use UberStoreFilePair#apply instead,
 * it will do all of the proper wiring.  This very well may be
 * made private in the future!
 *
 * Expectedly high performance sequence number based append only
 * storage.  Stores all data in dataFile, and sequence -> data
 * mappings in index.
 *
 * @param dataFile the UberDataFile to store data in
 * @param index the SeqIndex to use
 */
class UberStoreFilePair(dataFile: UberDataFile,
                index: SeqIndex) extends SiriusLog {

  /**
   * @inheritdoc
   */
  def writeEntry(event: OrderedEvent) {
    if (isClosed) {
      throw new IllegalStateException("Attempting to write to closed UberStoreFilePair")
    }
    if (event.sequence < getNextSeq) {
      throw new IllegalArgumentException("Writing events out of order is bad news bears")
    }
    val offset = dataFile.writeEvent(event)
    index.put(event.sequence, offset)
  }

  /**
   * @inheritdoc
   */
  def getNextSeq = index.getMaxSeq match {
    case None => 1L
    case Some(seq) => seq + 1
  }

  /**
   * @inheritdoc
   */
  def foldLeft[T](acc0: T)(foldFun: (T, OrderedEvent) => T): T =
    foldLeftRange(0, Long.MaxValue)(acc0)(foldFun)

  /**
   * @inheritdoc
   */
  def foldLeftRange[T](startSeq: Long, endSeq: Long)(acc0: T)(foldFun: (T, OrderedEvent) => T): T = {
    val (startOffset, endOffset) = index.getOffsetRange(startSeq, endSeq)
    dataFile.foldLeftRange(startOffset, endOffset)(acc0)(
      (acc, _, evt) => foldFun(acc, evt)
    )
  }

  /**
   * Close underlying file handles or connections.  This UberStoreFilePair should not be used after
   * close is called.
   */
  def close() {
    if (!dataFile.isClosed) {
      dataFile.close()
    }
    if (!index.isClosed) {
      index.close()
    }
  }

  /**
   * Consider this closed if either of its underlying objects are closed, no good writes
   * will be able to go through in that case.
   *
   * @return whether this is "closed," i.e., unable to be written to
   */
  def isClosed =
    dataFile.isClosed || index.isClosed

  override def finalize() {
    close()
  }
}
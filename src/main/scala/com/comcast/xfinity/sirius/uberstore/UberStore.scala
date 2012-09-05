package com.comcast.xfinity.sirius.uberstore

import com.comcast.xfinity.sirius.api.impl.OrderedEvent
import com.comcast.xfinity.sirius.writeaheadlog.SiriusLog
import scalax.io.CloseableIterator
import data.UberDataFile
import seqindex.{PersistedSeqIndex, SeqIndex}
import com.comcast.xfinity.sirius.api.impl.persistence.{BoundedLogRange, EntireLog, LogRange}

object UberStore {

  /**
   * Create an UberStore based in baseDir.  baseDir is NOT
   * created, it must exist. The files within baseDir will
   * be created if they do not exist however.
   *
   * @param baseDir directory to base the UberStore in
   *
   * @return an instantiated UberStore
   */
  def apply(baseDir: String): UberStore = {
    val baseName = "%s/1".format(baseDir)
    val dataFile = UberDataFile("%s.data".format(baseName))
    val index = PersistedSeqIndex("%s.index".format(baseName))
    repairIndex(index, dataFile)
    new UberStore(dataFile, index)
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
    val lastOffset = index.getMaxSeq match {
      case None => 0L
      case Some(seq) => index.getOffsetFor(seq).get // has to exist
    }

    dataFile.foldLeftRange(lastOffset, Long.MaxValue)(()) (
      (acc, off, evt) =>
        if (index.getOffsetFor(evt.sequence) == None) {
          index.put(evt.sequence, off)
        } else {
          // no-op
        }
    )

  }
}

/**
 * THIS SHOULD NOT BE USED DIRECTLY, use UberStore#apply instead,
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
class UberStore(dataFile: UberDataFile,
                index: SeqIndex) extends SiriusLog {

  /**
   * @inheritdoc
   */
  def writeEntry(event: OrderedEvent) {
    if (isClosed) {
      throw new IllegalStateException("Attempting to write to closed UberStore")
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
   * Retrieve a specified subrange of events from the log for sequential iteration.
   *
   * This method will be disappearing in the future in favor of foldLeftRange (private
   * API at the moment).
   *
   * Requesting large log ranges is HIGHLY discouraged, we load the entire range of
   * data into memory in this implementation.
   *
   * @param logRange LogRange specifying what subset of the log to return
   *
   * @return a StaticEventIterator of the events within the subrange
   */
  def createIterator(logRange: LogRange): CloseableIterator[OrderedEvent] = {
    val (startSeq, endSeq) = logRange match {
      case EntireLog => (0L, Long.MaxValue)
      case BoundedLogRange(begin, end) => (begin, end)
    }

    val events = foldLeftRange(startSeq, endSeq)(List[OrderedEvent]())(
      (acc, evt) => evt :: acc
    ).reverse

    CloseableIterator(events.iterator)
  }

  // foldLeft over sequence numbers startSeq -> endSeq, inclusive, this may become public...
  private def foldLeftRange[T](startSeq: Long, endSeq: Long)(acc0: T)(foldFun: (T, OrderedEvent) => T): T = {
    val (startOffset, endOffset) = index.getOffsetRange(startSeq, endSeq)
    dataFile.foldLeftRange(startOffset, endOffset)(acc0)(
      (acc, _, evt) => foldFun(acc, evt)
    )
  }

  /**
   * Close underlying file handles or connections.  This UberStore should not be used after
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
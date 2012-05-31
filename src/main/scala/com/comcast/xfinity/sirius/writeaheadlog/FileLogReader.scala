package com.comcast.xfinity.sirius.writeaheadlog

import java.io.File
import scalax.io.Line.Terminators.NewLine
import org.slf4j.LoggerFactory
import scalax.io.Resource

/**
 * Class that reads a log from a file
 */
class FileLogReader(filePath: String, serDe: LogDataSerDe) extends LogReader {
  val logger = LoggerFactory.getLogger(classOf[FileLogReader])


  /**
   * ${@inheritDoc}
   */
  override def foldLeft[T](acc0: T)(foldFun: (T, LogData) => T): T =
    lines.foldLeft(acc0)((acc, line) => {
      foldFun(acc, serDe.deserialize(line))
    })


  private[writeaheadlog] def lines = {
    Resource.fromFile(new File(filePath)).lines(NewLine, true)
  }
}
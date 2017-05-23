package org.apache.griffin.measure.batch.rule.expr_old

import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable.{Set => MutableSet}

object ExprIdCounter {

  private val idCounter: AtomicLong = new AtomicLong(0L)

  private val existIdSet: MutableSet[String] = MutableSet.empty[String]

  private val invalidIdRegex = """^\d+$""".r

  def genId(defaultId: String): String = {
    defaultId match {
      case "" => increment.toString
      case invalidIdRegex() => increment.toString
//      case defId if (exist(defId)) => s"${increment}#${defId}"
      case defId if (exist(defId)) => s"${defId}"
      case _ => {
        insertUserId(defaultId)
        defaultId
      }
    }
  }

  private def exist(id: String): Boolean = {
    existIdSet.contains(id)
  }

  private def insertUserId(id: String): Unit = {
    existIdSet += id
  }

  private def increment(): Long = {
    idCounter.incrementAndGet()
  }

}
package training.learn.log

import training.learn.lesson.Lesson
import java.util.*

/**
 * Created by karashevich on 13/10/15.
 */
class LessonLog(lesson: Lesson) {

  var myLesson: Lesson? = lesson
  val logData = ArrayList<Pair<Date, String>>()
  var exerciseCount = 0

  fun log(actionString: String) {
    logData.add(Pair(Date(), actionString))
  }

  fun resetCounter() {
    exerciseCount = 0
  }

  val firstDate: Date
    get() = logData[0].first

  fun print() {
    for ((first) in logData) {
      println(first.toString() + ": " + first)
    }
  }

  fun exportToString(): String {
    val sb = StringBuilder()
    for ((first) in logData) {
      sb.append(first).append(": ").append(first).append(";\n")
    }
    return sb.toString()
  }

  init {
    log("Log is created. Lesson:" + lesson.name)
  }

}

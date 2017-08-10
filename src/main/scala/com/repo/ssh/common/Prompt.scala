package com.indeni.ssh.common

sealed trait Prompt {
  def matches(string: String): Boolean = {
    this match {
      case PromptRegex(regex) =>
        string.lines.toArray.last.matches(regex)

      case PromptStr(prompts) =>
        prompts.exists { prompt =>
          string.takeRight(prompt.length) == prompt
        }
    }
  }
}

case class PromptRegex(regex: String) extends Prompt

case class PromptStr(prompts: List[String]) extends Prompt

object Prompt {
  val CLOSE_CHANNEL = "close"
  val NEW_LINE = "\n"
}
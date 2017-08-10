package com.indeni.ssh.common

import com.indeni.ssh.common.IndTypes.ShellCommand


sealed trait Command

case object CloseChannel extends Command

case class SimpleCommand(cmd: ShellCommand) extends Command

/**
  *
  * @param instructions - map when received prompt -> command to execute
  * @tparam P
  */
case class ProcessPrompts[P <: Prompt](instructions: Map[P, ShellCommand]) extends Command

sealed trait Response

case object DoneProcessing extends Response


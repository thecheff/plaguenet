package com.repo.plague


abstract class Info extends Dir {
  val os: String = System.getProperty("os.name")
  val sep: String = System.getProperty("path.separator")
  val home: String = System.getProperty("user.home")
  val jrev: String = System.getProperty("java.version")
  val name: String = System.getProperty("user.name")
  private var _installDir: String = home + sep + jrev + "bin" + sep + "os" + sep + name + "_smtp.java"

  def installDir: String = _installDir

  def installDir_=(installDir: String) {
    _installDir = installDir

  }
}
package com.repo.plague

import java.io.File

class Create {


  val info = new Info {
    override val _installDir: String = _
  }
  val file = new File(info.installDir)
  file.delete();


}
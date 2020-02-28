package org.seekloud.geek.client.common

import java.io.File

import javafx.scene.paint.Color


/**
  * User: Arrow
  * Date: 2019/7/16
  * Time: 14:54
  */
object Constants {


  //获取用户头像地址
  def getAvatarSrc(name:String) = {
    if (name == ""){//默认头像地址
      "scene/img/avatar.jpg"
    }else{
      "http://10.1.29.247:30226/hestia/files/image/OnlyForTest/" + name
    }
  }
//  private val splitSymbol =

  val cachePath: String = s"${System.getProperty("user.home")}/.theia/pcClient"

  val cacheFile = new File(cachePath)

  if (!cacheFile.exists()) cacheFile.mkdirs()

  val imageCachePath: String = cachePath + "/images"
  val loginInfoCachePath: String = cachePath + "/login"
  val recordPath: String = System.getProperty("user.home") + "\\.theia\\pcClient\\record"

//  val imageCache = new File(imageCachePath)
//  if (!imageCache.exists()) {
//    imageCache.mkdirs()
//  } else {
//    imageCache.listFiles().foreach { file =>
//      val image = new Image(new FileInputStream(file))
//      Pictures.pictureMap.put(file.getName, image)
//    }
//  }

  //登录信息的临时文件
  val loginInfoCache = new File(loginInfoCachePath)
  if (!loginInfoCache.exists()) loginInfoCache.mkdirs()

  val record = new File(recordPath)
  if (!record.exists()) record.mkdirs()


  object StreamType extends Enumeration {
    val RTMP, RTP = Value
  }



  object AppWindow {
    val width = 1152
    val height = 864
  }
  object DefaultPlayer {
    val width = 640
    val height = 480
  }

  object HostStatus {
    val NOT_CONNECT = 0
    val LOADING = 1
    val CONNECT = 2
  }

  object AllowStatus {
    val NOT_ALLOW = 0 //不被允许
    val ASKING = 1 //正在申请
    val ALLOW = 2 //允许发言
  }

  object DeviceStatus {
    val NOT_READY = 0
    val OFF = 1
    val ON = 2
  }

  object CommentType{
    val USER = 0
    val SERVER = 1
  }


  object AudienceStatus {
    val LIVE = 0
    val CONNECT = 1
    val RECORD = 2
  }

  object WindowStatus{
    val HOST = 0
    val AUDIENCE_LIVE = 1
    val AUDIENCE_REC = 2
  }

  //用户列表栏每个用户的4种操作
  object HostOperateIconType{
    val MIC = 0
    val VIDEO = 1
    val ALLOW = 2
    val HOST = 3
  }
  val barrageColors = List(
    Color.PINK,
    Color.HOTPINK,
    Color.WHITE,
    Color.RED,
    Color.ORANGE,
    Color.YELLOW,
    Color.GREEN,
    Color.CYAN,
    Color.BLUE,
    Color.PURPLE,
    Color.BROWN,
    Color.BURLYWOOD,
    Color.CHOCOLATE,
    Color.GOLD,
    Color.GREY
  )




}

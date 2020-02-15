package org.seekloud.geek.pages

import java.text.SimpleDateFormat

import mhtml.Var
import org.seekloud.geek.utils.{Http, JsFunc, Page}
import org.seekloud.geek.videoJs.VideoJSBuilderNew
import org.scalajs.dom
import org.scalajs.dom.html.{Button, Input}
import org.seekloud.geek.Main
import org.seekloud.geek.common.Route
import org.seekloud.geek.videoJs._
import org.seekloud.geek.shared.ptcl.RoomProtocol.{GetRoomListReq, GetRoomListRsp, GetRoomSectionListReq, GetRoomSectionListRsp, RoomData, RoomInfoSection}
import org.seekloud.geek.shared.ptcl.CommonProtocol._
import io.circe.generic.auto._
import io.circe.syntax._
import org.seekloud.geek.pages.HomePage
import org.seekloud.geek.shared.ptcl.SuccessRsp

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js.Date
import scala.xml.Elem

/**
  * User: xgy
  * Date: 2020/2/5
  * Time: 1:06
  */

class WatchRecord(roomID: Long,videoName :String) extends Page{
  override val locationHashString: String = s"#/room/$roomID/$videoName"

  var roomList: List[RoomData] = Main.roomList

  private val roomIdData: Var[List[RoomInfoSection]] = Var(Main.roomIdData)
  private val liveRoomInfo = Var(roomList)
  private val CommentInfo: Var[Option[List[Comment]]] = Var(None)
  private val roomIdVar = Var(roomID)
  private val videoNameVar = Var(videoName)
  private val userNameVar = Var("")
  private val fileNameVar = Var("")
  private val isinvitedVar = Var(false)

  private def roomListRx(r: List[RoomInfoSection]) =
    <div class="col-md-3 col-sm-12 col-xs-12" style="background-color: #F5F5F5; margin-left:-11%;margin-right:1%;margin-top:2px;height:416px;overflow-y: auto;">
      <div class="x_title">
        <h2>录像列表</h2>
      </div>
      <ul class="list-unstyled top_profiles scroll-view">
        {
        getRoomItem(r, videoName)
        }
      </ul>
    </div>

  private def commentListRx(r: List[RoomInfoSection]) =
    <div class="col-md-3 col-sm-12 col-xs-12" style="background-color: #F5F5F5; margin-left:1%;margin-top:2px;height:377px;overflow-y: auto;">
      <div class="x_title">
        <h2>评论列表</h2>
      </div>
      <div class="comment-Video">
        <div class="commentTitle"></div>
        <input class="commentInput" placehoder="发个友善的评论" id="commentInput">666</input>
        {isinvitedVar.map{r=>
        if(r) <div class="commentSubmit" onclick={() =>addComment(fileNameVar.toString().drop(4).dropRight(1));}>发表评论</div>
        else <div class="commentNoSubmit">禁止评论</div>
      }}
      </div>
      <ul class="list-unstyled top_profiles scroll-view">
        {
        getCommitItem(videoName)
        }
      </ul>
    </div>

//  private def commentRx =
//    <div class="col-md-7" style="background-color: #F5F5F5; margin-left:129px;margin-top:2px;height:40px">
//      <div class="comment-Video">
//        <div class="commentTitle"></div>
//        <input class="commentInput" placehoder="发个友善的评论" id="commentInput">666</input>
//        {isinvitedVar.map{r=>
//        if(r) <div class="commentSubmit" onclick={() =>addComment(fileNameVar.toString().drop(4).dropRight(1));}>发表评论</div>
//        else <div class="commentNoSubmit">禁止评论</div>
//      }}
//      </div>
//    </div>



  private def getRoomItem(roomList: List[RoomInfoSection], selected: String) = {
    roomList.map { room =>
      if (room.fileName == selected) {userNameVar:=room.userName;fileNameVar:=room.fileName;isinvitedVar:=room.isInvited}
      val isSelected = if (room.fileName == selected) "actived playerSelect" else ""
      <li class={"media event eyesight " + isSelected} style="text-align:left" onclick={() => switchEyesight(room.roomId,room.fileName)}>
        <a class="pull-left border-aero profile_thumb">
          <img class="player" src={Route.imgPath("room.png")}></img>
        </a>
        <div class="media-body">
          <a class="title" href="javascript:void(0)">
            {"主讲人"+room.userName}({"会议号"+room.roomId})
          </a>
          <p style="font-size:5px">{"录像名："+room.fileName}</p>
          <p style="font-size:5px">{"保存录像时间："+room.time}</p>
          {
          if(room.isInvited) <p style="font-size:5px">{"邀请状态：已邀请"}</p>
          else <p style="font-size:5px;color:red" >{"邀请状态：未邀请"}</p>
          }
        </div>
      </li>
    }
  }

  private def getCommitItem(videoName:String) = {

    CommentInfo.map{comment1=>
      comment1.getOrElse(List.empty).filter(_.commentContent!="").map{ room=>
      val isSelected =  ""
      <li class={"media event eyesight " + isSelected} style="text-align:left">

        <div class="media-body">
          {
          if(userNameVar.toString().drop(4).dropRight(1)==dom.window.localStorage.getItem("username").toString)
          <div title="删除" onclick={()=>delComment(room.commentId);getCommentList(roomID,videoName)}><a class="title" href="javascript:void(0)">{room.userId}</a><p>{room.commentContent}</p></div>
          else <div><a class="title" href="javascript:void(0)">{room.invitationName}</a><p>{room.commentContent}</p></div>
          }
        </div>
      </li>
    }
  }}

  private def switchEyesight(roomId: Long,videoName: String): Unit ={
    val userId= dom.window.localStorage.getItem("userId").toString
    renderTest(dom.document.getElementById("my-video"),userId,videoNameVar.toString().drop(4).dropRight(1))
    dom.window.location.hash = s"#/room/$roomId/$videoName"
  }

  private def refresh: Unit ={
    dom.window.location.hash = s"#/room/$roomID/$videoName"
  }

  val container: Elem =
    VideoJSBuilderNew().buildElem("my-video")

  val background: Elem =
    <div id="home">
      <div class="home-wrapper"  >
        <div style="margin-left:15%;">
          <div class="x_content" >
            {
            roomIdData.map( l =>
              if (l.isEmpty) <div>{roomListRx(l)}{container}{commentListRx(l)}</div>
              else {
//                dom.window.setTimeout(()=>renderLive(dom.document.getElementById("my-video"), l.filter(_.roomId == roomID).head.url), 1000)
                <div>{roomListRx(l)}{container}{commentListRx(l)}</div>
              }
            )
            }
          </div>
        </div>
      </div>

    </div>

  def getRoomList: Future[Unit] = {
    val url = Route.Room.getRoomList
    val data = GetRoomListReq().asJson.noSpaces
    Http.postJsonAndParse[GetRoomListRsp](url, data).map {
      rsp =>
        try {
          if (rsp.errCode == 0) {
            roomList = rsp.roomList
            liveRoomInfo := roomList
            println(s"got it : $rsp")
          }
          else {
            println("error======" + rsp.msg)
            JsFunc.alert(rsp.msg)
          }
        }
        catch {
          case e: Exception =>
            println(e)
        }
    }
  }

  def getCommentList(roomID:Long,videoName:String): Unit = {
    val url = Route.Room.getCommentList
    val data = GetCommentReq(roomID,videoName).asJson.noSpaces
    println(s"ssss start : ")
    Http.postJsonAndParse[GetCommentRsp](url, data).map {
      rsp =>
        try {
          if (rsp.errCode == 0) {
            CommentInfo :=rsp.roomId
            println(s"ssss got it : $rsp")
          }
          else {
            println("ssss error======" + rsp.msg)
          }
    }
    catch {
      case e: Exception =>
        println("ssss error======")
    }

    }

  }

  def addComment(fileName:String): Unit = {
    val url = Route.Room.addComment
    val userId=dom.window.localStorage.getItem("userId").toLong
    val commentContent = dom.document.getElementById("commentInput").asInstanceOf[Input].value
    val data = addCommentReq(fileName,userId,commentContent).asJson.noSpaces
    Http.postJsonAndParse[SuccessRsp](url, data).map {
      rsp =>
        try {
          if (rsp.errCode == 0) {
            JsFunc.alert("评论成功")
            getCommentList(roomID,videoName)
          }
          else {
            println("error======" + rsp.msg)
            JsFunc.alert(rsp.msg)
          }
        }
        catch {
          case e: Exception =>
            println(e)
        }
    }
  }
  def delComment(commentID:Long): Unit = {
    val url = Route.Room.delComment
    val data = delCommentReq(commentID).asJson.noSpaces
    Http.postJsonAndParse[SuccessRsp](url, data).map {
      rsp =>
        try {
          if (rsp.errCode == 0) {
            JsFunc.alert("删除评论成功")
          }
          else {
            println("error======" + rsp.msg)
            JsFunc.alert(rsp.msg)
          }
        }
        catch {
          case e: Exception =>
            println(e)
        }
    }
  }

  def getRoomSecList(): Unit ={
    val userId = dom.window.localStorage.getItem("userId").toLong
    Http.postJsonAndParse[GetRoomSectionListRsp](Route.Room.getRoomSectionList, GetRoomSectionListReq(userId).asJson.noSpaces).map {
      rsp =>
        if(rsp.errCode == 0) {
          roomIdData := rsp.roomList
        } else {
          println(rsp.msg)
        }
    }
  }

  def init(): Unit = {
    dom.document.body.style = "background-image: url('/geek/static/img/blueSky.jpg');" +
      "background-attachment: fixed;" +
      "background-size: cover;" +
      "background-position: center;" +
      "background-repeat: no-repeat;"
  }


  override def render: Elem = {
    HomePage.init()
    init()
//    getRoomList
    getRoomSecList()
    getCommentList(roomID,videoName)
    println(s"ssss end : ")
//    getCommentList(1002,"sss")
    <div >
      {background}
    </div>

  }
}

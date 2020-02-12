package org.seekloud.geek.client.core

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer, TimerScheduler}
import org.seekloud.geek.client.Boot
import org.seekloud.geek.client.common.Constants.{AudienceStatus, HostStatus}
import org.seekloud.geek.client.common.{AppSettings, Routes, StageContext}
import org.seekloud.geek.client.component.WarningDialog
import org.seekloud.geek.client.controller.{HomeController, HostController}
import org.seekloud.geek.client.core.stream.LiveManager
import org.seekloud.geek.client.scene.{HomeScene, HostScene}
import org.seekloud.geek.player.sdk.MediaPlayer
import org.seekloud.geek.shared.ptcl.CommonProtocol._
import org.slf4j.LoggerFactory
import org.seekloud.geek.client.Boot.{executor, materializer, scheduler, system, timeout}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer, TimerScheduler}
import org.seekloud.geek.client.core.collector.ClientCaptureActor
import org.seekloud.geek.client.core.player.VideoPlayer
import org.seekloud.geek.client.core.stream.LiveManager.{JoinInfo, PushStream, WatchInfo}
import org.seekloud.geek.client.utils.{RoomClient, WsUtil}
import org.seekloud.geek.player.protocol.Messages.AddPicture
import org.seekloud.geek.shared.ptcl.WsProtocol
import org.seekloud.geek.shared.ptcl.WsProtocol.WsMsgFront

import scala.collection.immutable

/**
 * User: hewro
 * Date: 2020/1/31
 * Time: 14:38
 * Description: 与服务器端的roomManager进行交互
 */
object RmManager {

  private val log = LoggerFactory.getLogger(this.getClass)
  sealed trait RmCommand

  //
  var userInfo: Option[UserInfo] = None
  var roomInfo: Option[RoomInfo] = None


  private[this] def switchBehavior(ctx: ActorContext[RmCommand],
    behaviorName: String,
    behavior: Behavior[RmCommand])
    (implicit stashBuffer: StashBuffer[RmCommand]) = {
    log.debug(s"${ctx.self.path} becomes $behaviorName behavior.")
    stashBuffer.unstashAll(ctx, behavior)
  }


  //拿到homeController 和 homeScreen
  final case class GetHomeItems(homeScene: HomeScene, homeController: HomeController) extends RmCommand
  final case class SignInSuccess(userInfo: Option[UserInfo] = None, roomInfo: Option[RoomInfo] = None) extends RmCommand
  final case object Logout extends RmCommand
  final case object GoToCreateAndJoinRoom extends RmCommand //进去创建会议的页面
//  final case object GoToJoinRoom extends RmCommand //进去加入会议的页面

  final case object HostWsEstablish extends RmCommand
  final case object BackToHome extends RmCommand
  final case object HostLiveReq extends RmCommand //请求开启会议
  final case class StartLive(pull:String, push:String) extends RmCommand
  final case object StopLive extends RmCommand
  final case object PullerStopped extends RmCommand

  final case object GetPackageLoss extends RmCommand

  //ws链接
  final case class GetSender(sender: ActorRef[WsMsgFront]) extends RmCommand


  def create(stageCtx: StageContext): Behavior[RmCommand] =
    Behaviors.setup[RmCommand] { ctx =>
      log.info(s"RmManager is starting...")
      implicit val stashBuffer: StashBuffer[RmCommand] = StashBuffer[RmCommand](Int.MaxValue)
      Behaviors.withTimers[RmCommand] { implicit timer =>
        //启动client同时启动player
        val mediaPlayer = new MediaPlayer()
        mediaPlayer.init(isDebug = AppSettings.playerDebug, needTimestamp = AppSettings.needTimestamp)
        val liveManager = ctx.spawn(LiveManager.create(ctx.self, mediaPlayer), "liveManager")
        idle(stageCtx, liveManager, mediaPlayer)
      }
    }

  def idle(
    stageCtx: StageContext,
    liveManager: ActorRef[LiveManager.LiveCommand],
    mediaPlayer: MediaPlayer,
    homeController: Option[HomeController] = None
  )(
    implicit stashBuffer: StashBuffer[RmCommand],
    timer: TimerScheduler[RmCommand]
  ):Behavior[RmCommand] = {
    Behaviors.receive[RmCommand]{
      (ctx, msg) =>
        msg match {

          case GetHomeItems(homeScene, homeController) =>
            idle(stageCtx,liveManager,mediaPlayer,Some(homeController))

          case GoToCreateAndJoinRoom =>
            val hostScene = new HostScene(stageCtx.getStage)
            val hostController = new HostController(stageCtx, hostScene, ctx.self)
            def callBack(): Unit = Boot.addToPlatform(hostScene.changeToggleAction())
            liveManager ! LiveManager.DevicesOn(hostScene.gc, callBackFunc = Some(callBack))
            //todo: 建立ws连接
            ctx.self ! HostWsEstablish
            Boot.addToPlatform {
              if (homeController != null) {
                homeController.get.removeLoading()
              }
              hostController.showScene()
            }
            switchBehavior(ctx, "hostBehavior", hostBehavior(stageCtx, homeController, hostScene, hostController, liveManager, mediaPlayer))


          case SignInSuccess(userInfo, roomInfo)=>
            //todo 可以进行登录后的一些处理，比如创建临时文件等，这部分属于优化项
            Behaviors.same



          case Logout =>
            log.info(s"退出登录.")
            this.roomInfo = None
            this.userInfo = None
            homeController.get.showScene()
            Behaviors.same

          case _=>
            log.info("收到未知消息idle")
            Behaviors.same
        }
    }
  }


  //已经进行会议的场景
  private def hostBehavior(
    stageCtx: StageContext,
    homeController: Option[HomeController] = None,
    hostScene: HostScene,
    hostController: HostController,
    liveManager: ActorRef[LiveManager.LiveCommand],
    mediaPlayer: MediaPlayer,
    sender: Option[ActorRef[WsMsgFront]] = None,
    hostStatus: Int = HostStatus.NOTCONNECT, //0-直播，1-连线
    joinAudience: Option[MemberInfo] = None //组员
  )(
    implicit stashBuffer: StashBuffer[RmCommand],
    timer: TimerScheduler[RmCommand]
  ): Behavior[RmCommand] =
    Behaviors.receive[RmCommand] { (ctx, msg) =>
      msg match {
        case HostWsEstablish =>
          //与roomManager建立ws
          assert(userInfo.nonEmpty && roomInfo.nonEmpty)

          def successFunc(): Unit = {
            Boot.addToPlatform {
              WarningDialog.initWarningDialog("链接成功！")
            }
            //            hostScene.allowConnect()
            //            Boot.addToPlatform {
            //              hostController.showScene()
            //            }
          }
          def failureFunc(): Unit = {
            //            liveManager ! LiveManager.DeviceOff
            Boot.addToPlatform {
              WarningDialog.initWarningDialog("连接失败！")
            }
          }
          val url = Routes.linkRoomManager(userInfo.get.userId, roomInfo.map(_.roomId).get)
          WsUtil.buildWebSocket(ctx, url, hostController, successFunc(), failureFunc())
          Behaviors.same

        case msg: GetSender =>
          //添加给后端发消息的对象sender
          msg.sender ! WsProtocol.Test("I'm telling you")
//          msg.sender ! WsProtocol.StartLiveReq(1040)
          hostBehavior(stageCtx, homeController, hostScene, hostController, liveManager, mediaPlayer, Some(msg.sender), hostStatus)


        case HostLiveReq =>

          //http请求服务器获取拉流地址
          if (RmManager.userInfo.get.isHost.get){//房主
            RoomClient.startLive(RmManager.roomInfo.get.roomId).map{
              case Right(rsp) =>
                ctx.self ! StartLive(rsp.rtmp.serverUrl+rsp.rtmp.stream,rsp.rtmp.serverUrl+rsp.selfCode)
//                ctx.self ! StartLive("rtmp://10.1.29.247:1935/live/1002333","rtmp://10.1.29.247:1935/live/1002333")
              case Left(e) =>
                log.info(s"开始会议失败：$e")
            }
          }else{//普通成员

            RoomClient.startLive4Client(RmManager.userInfo.get.userId,RmManager.roomInfo.get.roomId).map{
              case Right(rsp) =>
                ctx.self ! StartLive(rsp.rtmp.get.serverUrl+rsp.rtmp.get.stream,rsp.rtmp.get.serverUrl+rsp.selfCode)

              case Left(e) =>
                log.info(s"开始会议失败：$e")
            }

          }

          Behaviors.same


        case StartLive(pull, push)=>

          //1.开始推流
          log.info(s"开始会议")
          liveManager ! LiveManager.PushStream(push)

          //2.开始拉流：
          RmManager.userInfo.get.pullStream = Some(pull)
          liveManager ! LiveManager.PullStream(RmManager.userInfo.get.pullStream.get,mediaPlayer,hostScene,liveManager)

          switchBehavior(ctx, "hostBehavior", hostBehavior(stageCtx, homeController, hostScene, hostController, liveManager, mediaPlayer,hostStatus=HostStatus.CONNECT))

        case GetPackageLoss =>
          liveManager ! LiveManager.GetPackageLoss
          Behaviors.same

        case StopLive =>
          liveManager ! LiveManager.StopPush
          if (RmManager.userInfo.get.isHost.get){//房主
            log.info("房主停止推流")

            RoomClient.stopLive(RmManager.roomInfo.get.roomId).map{
              case Right(value) =>
                log.info("房主停止推流成功")
              case Left(e) =>
                log.info(s"房主停止推流失败:$e")

            }
          }else{//普通成员
            log.info("普通用户停止推流")
            RoomClient.stopLive4Client(RmManager.roomInfo.get.roomId,RmManager.userInfo.get.userId).map{
              case Right(value) =>
                log.info("普通用户停止推流成功")

              case Left(e) =>
              log.info(s"普通用户停止推流失败:$e")
            }
          }
          //todo: 停止推流后，画布显示摄像头的信息
          /*背景改变*/
          hostScene.resetBack()
          /*媒体画面模式更改*/
          liveManager ! LiveManager.SwitchMediaMode(isJoin = false, reset = hostScene.resetBack)

          if (hostStatus == HostStatus.CONNECT) {//开启会议情况下
            //todo: 需要关闭player的显示
            val playId = RmManager.roomInfo.get.roomId.toString
            //停止服务器拉流显示到player上
            mediaPlayer.stop(playId, hostScene.resetBack)
            liveManager ! LiveManager.StopPull
          }

          hostController.isLive = false
          Behaviors.same

        case BackToHome =>
//          timer.cancel(HeartBeat)
//          timer.cancel(PingTimeOut)
//          sender.foreach(_ ! CompleteMsgClient)
          if (hostStatus == HostStatus.CONNECT) {//开启会议情况下
            //todo: 需要关闭player的显示
            val playId = RmManager.roomInfo.get.roomId.toString
            //停止服务器拉流显示到player上
            mediaPlayer.stop(playId, hostScene.resetBack)
            liveManager ! LiveManager.StopPull
          }
          liveManager ! LiveManager.StopPush
          liveManager ! LiveManager.DeviceOff
          Boot.addToPlatform {
            hostScene.stopPackageLoss()
            homeController.foreach(_.showScene())
          }
          hostScene.stopPackageLoss()
          System.gc()
          switchBehavior(ctx, "idle", idle(stageCtx, liveManager, mediaPlayer, homeController))


        case PullerStopped =>
          //停止拉流，切换到显示自己的视频流中
          assert(userInfo.nonEmpty)
          log.info(s"停止会议了！")
          val userId = userInfo.get.userId

          Behaviors.same
        case _=>
          Behaviors.unhandled

      }}

}

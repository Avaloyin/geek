package org.seekloud.geek.core

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer, TimerScheduler}
import org.seekloud.geek.Boot
import org.seekloud.geek.Boot.executor
import org.seekloud.geek.common.AppSettings
import org.seekloud.geek.models.dao.RoomDao
import org.seekloud.geek.shared.ptcl.RoomProtocol._
import org.seekloud.geek.shared.ptcl.{ComRsp, ErrorRsp, SuccessRsp, WsProtocol}
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import org.seekloud.geek.core.RoomDealer.ChildDead
import org.seekloud.geek.models.SlickTables
import org.seekloud.geek.protocol.RoomProtocol

import scala.collection.mutable
import scala.util.{Failure, Success}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._



/**
 * Author: Jason
 * Date: 2020/1/25
 * Time: 15:20
 */


object RoomManager {
  private val log = LoggerFactory.getLogger(this.getClass)

  private val peopleNum = 5

  trait Command

  final case class CreateRoom(req: CreateRoomReq, rsp: ActorRef[CreateRoomRsp]) extends Command

  final case class ModifyRoom(room: SlickTables.rRoom) extends Command

  final case class StartLive(req: StartLiveReq, replyTo: ActorRef[StartLiveRsp]) extends Command

  final case class StartLive4Client(req: StartLive4ClientReq, replyTo: ActorRef[StartLive4ClientRsp]) extends Command

  final case class StopLive(req: StopLiveReq, replyTo: ActorRef[SuccessRsp]) extends Command

  final case class StopLive4Client(req: StopLive4ClientReq, replyTo: ActorRef[SuccessRsp]) extends Command

  final case class JoinRoom(req: JoinRoomReq, replyTo: ActorRef[JoinRoomRsp]) extends Command

  final case class Invite(req: InviteReq, replyTo: ActorRef[InviteRsp]) extends Command

  final case class KickOff(req: KickOffReq, replyTo: ActorRef[SuccessRsp]) extends Command

  final case class Shield(req: ShieldReq, replyTo: ActorRef[SuccessRsp]) extends Command

  final case class GetRoomList(replyTo: ActorRef[GetRoomListRsp]) extends Command

  final case class GetUserInfo(req: GetUserInfoReq, replyTo: ActorRef[GetUserInfoRsp]) extends Command

  final case class UpdateRoomInfo(req: UpdateRoomInfoReq, replyTo: ActorRef[ComRsp]) extends Command

  final case class ExistRoom(roomId:Long, replyTo:ActorRef[Boolean]) extends Command

  private final case class SwitchBehavior(
    name: String,
    behavior: Behavior[Command],
    durationOpt: Option[FiniteDuration] = None,
    timeOut: TimeOut = TimeOut("busy time error")
  ) extends Command

  private case class TimeOut(msg: String) extends Command

  private final case object BehaviorChangeKey

  private case object InitTimeKey

  private val initTime = 5.minutes

  case object Test extends Command

  case class RoomDetailInfo(
    roomUserInfo: RoomUserInfo,
    rtmpInfo: RtmpInfo,
    hostCode: String,
    var userLiveCodeMap: Map[String, Long],
    roomDealer: ActorRef[RoomDealer.Command]
  )

  private[this] def switchBehavior(ctx: ActorContext[Command],
    behaviorName: String, behavior: Behavior[Command], durationOpt: Option[FiniteDuration] = None, timeOut: TimeOut = TimeOut("busy time error"))
    (implicit stashBuffer: StashBuffer[Command],
      timer: TimerScheduler[Command]) = {
    timer.cancel(BehaviorChangeKey)
    durationOpt.foreach(timer.startSingleTimer(BehaviorChangeKey, timeOut, _))
    stashBuffer.unstashAll(ctx, behavior)
  }

  def init(): Behavior[Command] =
    Behaviors.setup[Command] { ctx =>
      log.info(s"RoomManager is starting...")
      implicit val stashBuffer: StashBuffer[Command] = StashBuffer[Command](Int.MaxValue)
      Behaviors.withTimers[Command] { implicit timer =>
        val roomIdGenerator = new AtomicLong(1000L)
        val rooms = mutable.HashMap[Long, RoomDetailInfo]()
        val affiliation = mutable.HashMap[Long, List[Long]]()
//        timer.startSingleTimer(InitTimeKey, TimeOut("init"),initTime)
//        RoomDao.getAllRoom.onComplete {
//          case Success(roomList) =>
//            roomList.toList.map { r =>
//              val roomUserInfo = RoomUserInfo(r.hostid, r.title, r.desc.getOrElse(""))
//              //              val roomActor = getRoomActor(ctx, r.roomId, roomInfo)
//              val rtmpInfo = RtmpInfo(AppSettings.rtmpServer, "", Nil)
//              val liveCodeMap = decode[Map[String, Long]](r.livecode) match {
//                case Right(rsp) =>
//                  rsp
//                case Left(e) =>
//                  log.info(s"${r.livecode} decode liveCode error: ${e.getMessage}")
//                  Map[String, Long]()
//              }
//              rooms.put(r.id, RoomDetailInfo(roomUserInfo, rtmpInfo, r.hostcode, liveCodeMap, null))
//            }
//            val user2Room = roomList.toList.groupBy(_.hostid).map(i => (i._1, i._2.map(_.id)))
//            user2Room.foreach { u =>
//              affiliation.put(u._1, u._2)
//            }
//            timer.cancel(InitTimeKey)
//            log.info("Init room info successfully!")
//            ctx.self ! SwitchBehavior("idle", idle(roomIdGenerator, rooms, affiliation))
//
//          case Failure(e) =>
//            log.info(s"Init room list error due to $e")
//        }
//        busy()
        if (AppSettings.rtmpIsTest) ctx.self ! Test
        idle(roomIdGenerator, mutable.HashMap.empty, mutable.HashMap.empty)
      }
    }

  private def idle(
    roomIdGenerator: AtomicLong,
    rooms: mutable.HashMap[Long, RoomDetailInfo], //RoomUserInfo, RtmpInfo, hostLiveCode, (liveStream -> userId)
    affiliation: mutable.HashMap[Long, List[Long]]   //userId -> List(roomId)
  )(
    implicit stashBuffer: StashBuffer[Command],
    timer: TimerScheduler[Command]
  ): Behavior[Command] =
    Behaviors.receive[Command] { (ctx, msg) =>
      msg match {
        case Test =>
          val roomDetailInfo = RoomDetailInfo(RoomUserInfo(1000001, "a", "b"), RtmpInfo("a", "1000",List("1000_1", "1000_3")), "", Map.empty, null)
          val roomActor = getRoomDealer(1000, roomDetailInfo, ctx)
          Boot.grabManager ! GrabberManager.StartLive(1000, 100000L, RtmpInfo("a", "1000",List("1000_1")), "1000_1", roomActor)
          Behaviors.same

        case CreateRoom(req, rsp) =>
          val rRoom = SlickTables.rRoom(0L, req.info.roomName, Some(req.info.des), "", "", AppSettings.rtmpServer, req.userId)
          RoomDao.addRoom(rRoom).onComplete{
            case Success(roomId) =>
              var streams = List[String]()
              (1 to peopleNum).foreach { i =>
                val streamName = s"${roomId}_$i"
                streams = streamName :: streams
              }
              val rtmpInfo = RtmpInfo(AppSettings.rtmpServer, "", streams.reverse)
              var selfCode = ""
              val userLiveCodeMap: Map[String, Long] = streams.reverse.zipWithIndex.toMap.map{ s =>
                val index = s._2
                if (index == 0) {
                  selfCode = s._1
                  (s._1, req.userId)
                }
                else {
                  (s._1, -1L)
                }
              }
              val rRoomNew = SlickTables.rRoom(roomId, req.info.roomName, Some(req.info.des), "", "", AppSettings.rtmpServer, req.userId)
              val roomDetailInfo = RoomDetailInfo(req.info, rtmpInfo, selfCode, userLiveCodeMap, null)
              val roomDealer = getRoomDealer(roomId, roomDetailInfo, ctx)
              rooms.put(roomId, roomDetailInfo.copy(roomDealer = roomDealer))
              val assets = affiliation.getOrElse(req.userId, Nil)
              affiliation.put(req.userId, roomId :: assets)
              ctx.self ! ModifyRoom(rRoomNew)
              rsp ! CreateRoomRsp(roomId, selfCode)

            case Failure(e) =>
              log.info(s"add room to db failed due to $e")
              rsp ! CreateRoomFail
          }
          Behaviors.same

        case ModifyRoom(room) =>
          log.info(s"modify room-${room.id}")
          RoomDao.modifyRoom(room)
          Behaviors.same

        case r@RoomProtocol.StartRoom4Anchor(userId, roomId, actor) =>
          if (rooms.get(roomId).isDefined) {
            log.info(s"room-$roomId is starting live...233")
            if (rooms(roomId).roomDealer != null) {
              assert(rooms.contains(roomId))
              val roomOldInfo = rooms(roomId)
              val stream = roomId + "_" + System.currentTimeMillis()
              val rtmpInfoNew = roomOldInfo.rtmpInfo.copy(stream = stream)
              val roomDealer = getRoomDealer(roomId, roomOldInfo, ctx)
              val roomInfoNew = RoomDetailInfo(roomOldInfo.roomUserInfo, rtmpInfoNew, roomOldInfo.hostCode, roomOldInfo.userLiveCodeMap, roomDealer)
              rooms.put(roomId, roomInfoNew)
              roomDealer ! RoomDealer.StartLive(roomInfoNew, roomOldInfo.hostCode, roomOldInfo.roomUserInfo.userId, actor)
            }
            else {
              val roomOldInfo = rooms(roomId)
              val index = roomOldInfo.rtmpInfo.liveCode.maxBy(_.split("_").last.toInt).split("_").last.toInt
              var streams = List[String]()
              (index until index + peopleNum).foreach { i =>
                val streamName = s"${roomId}_$i"
                streams = streamName :: streams
              }
              log.info(s"room-$roomId is back to live...")
              val stream = roomId + "_" + System.currentTimeMillis()
              val rtmpInfoNew = roomOldInfo.rtmpInfo.copy(stream = stream, liveCode = streams.reverse)
              val roomDealer = getRoomDealer(roomId, roomOldInfo, ctx)
              val roomInfoNew = RoomDetailInfo(roomOldInfo.roomUserInfo, rtmpInfoNew, streams.reverse.head, roomOldInfo.userLiveCodeMap, roomDealer)
              rooms.put(roomId, roomInfoNew)
              roomDealer ! RoomDealer.StartLive(roomInfoNew, streams.reverse.head, roomOldInfo.roomUserInfo.userId, actor)
            }
          }
          else log.debug(s"${ctx.self.path}请求错误1，该房间还不存在，房间id=$roomId，用户id=$userId")
          Behaviors.same

        case r@RoomProtocol.WebSocketMsgWithActor(userId, roomId, x) =>
          getRoomDealerOpt(roomId, ctx) match{
            case Some(actor) =>
              x match {
                case msg: WsProtocol.StartLiveReq =>
                  log.info(s"room-$roomId is starting live...")
                  assert(rooms.contains(msg.roomId))
                  val roomOldInfo = rooms(msg.roomId)
                  val stream = msg.roomId + "_" + System.currentTimeMillis()
                  val rtmpInfoNew = roomOldInfo.rtmpInfo.copy(stream = stream)
                  val roomDealer = getRoomDealer(msg.roomId, roomOldInfo, ctx)
                  val roomInfoNew = RoomDetailInfo(roomOldInfo.roomUserInfo, rtmpInfoNew, roomOldInfo.hostCode, roomOldInfo.userLiveCodeMap, roomDealer)
                  rooms.put(msg.roomId, roomInfoNew)
//                  roomDealer ! RoomDealer.StartLive(roomInfoNew, roomOldInfo.hostCode, roomOldInfo.roomUserInfo.userId)

                case msg: WsProtocol.StartLive4ClientReq =>
                  val roomOldInfo = rooms(msg.roomId)
                  var selfCode = ""
                  var flag = true
                  val userCodeMap =
                    if (roomOldInfo.userLiveCodeMap.exists(_._2 == msg.userId)) {
                      roomOldInfo.userLiveCodeMap.toList.sortBy(_._1.split("_").last).map{ u =>
                        if (u._2 == msg.userId && flag){
                          flag = false
                          selfCode = u._1
                        }
                        u
                      }
                      }.toMap
                    else {
                      roomOldInfo.userLiveCodeMap.toList.sortBy(_._1.split("_").last).map{ u =>
                        if (u._2 == -1L && flag){
                          flag = false
                          selfCode = u._1
                          (u._1, msg.userId)
                        }
                        else u
                      }
                      }.toMap
                  val roomNewInfo = roomOldInfo.copy(userLiveCodeMap = userCodeMap)
                  rooms.update(roomId, roomNewInfo)
                  getRoomDealerOpt(roomId, ctx) match {
                    case Some(actor) => actor ! RoomDealer.StartLive4Client(roomNewInfo, msg.userId, selfCode)
                    case None => log.debug(s"${ctx.self.path} StartLive4Client，房间不存在，有可能该用户是主播等待房间开启，房间id=$roomId,用户id=$userId")
                  }
//                  if (roomOldInfo.userLiveCodeMap.exists(_._2 == msg.userId)) {
//
//                  }
//                  else log.info("StartLive4Client fail.")

                case msg: WsProtocol.StopLiveReq =>
                  log.info(s"stop live in room: ${msg.roomId}")
                  if (rooms.contains(msg.roomId)) {
                    val liveCodes = rooms(msg.roomId).rtmpInfo.liveCode
                    val roomOldInfo = rooms(msg.roomId)
                    val roomInfoNew = RoomDetailInfo(roomOldInfo.roomUserInfo, RtmpInfo(AppSettings.rtmpServer, "", roomOldInfo.rtmpInfo.liveCode), roomOldInfo.hostCode, roomOldInfo.userLiveCodeMap, null)
                    getRoomDealerOpt(roomId, ctx) match{
                      case Some(actor) =>actor !  RoomDealer.StopLive(roomInfoNew, RtmpInfo(AppSettings.rtmpServer, "", liveCodes))
                      case None => log.debug(s"${ctx.self.path} StopLiveReq，房间不存在，有可能该用户是主播等待房间开启，房间id=$roomId,用户id=$userId")
                    }
//                    roomOldInfo.roomDealer ! RoomDealer.StopLive(RtmpInfo(AppSettings.rtmpServer, "", liveCodes))
                    rooms.put(msg.roomId, roomInfoNew)
//                    msg.replyTo ! SuccessRsp()
                  }
                  else log.info("stop live error")


                case msg: WsProtocol.StopLive4ClientReq =>
                  log.info(s"user-${msg.userId} stop live in room: ${msg.roomId}")
                  if (rooms.contains(msg.roomId)) {
                    val roomOldInfo = rooms(msg.roomId)
                    val selfCodeOpt = roomOldInfo.userLiveCodeMap.find(_._2 == msg.userId)
                    if (selfCodeOpt.isDefined) {
                      selfCodeOpt.foreach{ r =>
                        getRoomDealerOpt(roomId, ctx)match{
                          case Some(actor) =>
                            actor !  RoomDealer.StopLive4Client(roomOldInfo, msg.userId, r._1)
                          case None => log.debug(s"${ctx.self.path} StopLive4ClientReq，房间不存在，有可能该用户是主播等待房间开启，房间id=$roomId,用户id=$userId")
                        }
                      }
                    }
                    else {
                      log.info("stop error, user doesn't exist")
                    }
                  }
                  else log.info("stop error, room doesn't exist")

                case msg: WsProtocol.ShieldReq =>
                  if (rooms.contains(msg.roomId)) {
                    val roomInfo = rooms(msg.roomId)
                    val selfCodeOpt = roomInfo.userLiveCodeMap.find(_._2 == msg.userId)
                    if (selfCodeOpt.isDefined) {
                      selfCodeOpt.foreach{ s =>
                        getRoomDealerOpt(roomId, ctx)match{
                          case Some(actor) =>actor !  RoomDealer.Shield(msg, s._1)
                          case None => log.debug(s"${ctx.self.path} ShieldReq，房间不存在，有可能该用户是主播等待房间开启，房间id=$roomId,用户id=$userId")
                        }
//                        roomInfo.roomDealer ! RoomDealer.Shield(msg, s._1)
                      }
                    }
                    else log.info( "shield error, This user doesn't exist")
                  }
                  else log.info( "shield error, This room doesn't exist")

                case msg: WsProtocol.AppointReq =>
                  if (rooms.contains(msg.roomId)) {
                    val roomInfo = rooms(msg.roomId)
                    val selfCodeOpt = roomInfo.userLiveCodeMap.find(_._2 == msg.userId)
                    if (selfCodeOpt.isDefined) {
                      selfCodeOpt.foreach{ s =>
                        getRoomDealerOpt(roomId, ctx)match{
                          case Some(actor) =>actor !  RoomDealer.Appoint(msg.userId, msg.roomId, s._1, msg.status)
                          case None => log.debug(s"${ctx.self.path} AppointReq，房间不存在，有可能该用户是主播等待房间开启，房间id=$roomId,用户id=$userId")
                        }
                      }
                    }
                    else log.info( "Appoint error, This user doesn't exist")
                  }
                  else log.info( "Appoint error, This room doesn't exist")

                case msg: WsProtocol.KickOffReq =>
                  log.info(s"user-${msg.userId} stop live in room: ${msg.roomId}")
                  assert(rooms.contains(msg.roomId))
                  val roomOldInfo = rooms(msg.roomId)
                  val selfCodeOpt = roomOldInfo.userLiveCodeMap.find(_._2 == msg.userId)
                  if (selfCodeOpt.isDefined) {
                    selfCodeOpt.foreach{ r =>
                      val userCodeMap = roomOldInfo.userLiveCodeMap.map{ u =>
                        if (u._2 == msg.userId){
                          (u._1, -1L)
                        }
                        else u
                      }
                      val roomNewInfo = roomOldInfo.copy(userLiveCodeMap = userCodeMap)
                      rooms.update(msg.roomId, roomNewInfo)
                      getRoomDealerOpt(roomId, ctx)match{
                        case Some(actor) =>actor  ! RoomDealer.StopLive4Client(roomNewInfo, msg.userId, r._1)

                        case None => log.debug(s"${ctx.self.path} KickOffReq，房间不存在，有可能该用户是主播等待房间开启，房间id=$roomId,用户id=$userId")
                      }
                    }
                  }
                  else {
                    log.debug(s"${ctx.self.path}更新用户信息失败，kick off error，房间id=$roomId,用户id=$userId")
                  }

                case msg: WsProtocol.ChangePossessionReq =>
                  if (rooms.contains(msg.roomId)) {
                    val roomInfo = rooms(msg.roomId)
                    val selfCodeOpt = roomInfo.userLiveCodeMap.find(_._2 == msg.userId)
                    if (selfCodeOpt.isDefined) {
                      selfCodeOpt.foreach{ s =>
                        val roomInfoNew = roomInfo.copy(roomUserInfo = roomInfo.roomUserInfo.copy(userId = msg.userId), hostCode = s._1)
                        rooms.update(msg.roomId, roomInfoNew)
                        getRoomDealerOpt(roomId, ctx)match{
                          case Some(actor) => actor ! RoomDealer.ChangePossession(roomInfoNew)

                          case None => log.debug(s"${ctx.self.path} ShieldReq，房间不存在，有可能该用户是主播等待房间开启，房间id=$roomId,用户id=$userId")
                        }
                        //                        roomInfo.roomDealer ! RoomDealer.Shield(msg, s._1)
                      }
                    }
                    else log.info( "shield error, This user doesn't exist")
                  }
                  else log.info( "shield error, This room doesn't exist")

                case _ => actor ! r
              }
            case None => log.debug(s"${ctx.self.path}请求错误2，该房间还不存在，房间id=$roomId，用户id=$userId")
          }
          Behaviors.same

        case r@RoomProtocol.UpdateSubscriber(_,roomId,userId, _) =>
          getRoomDealerOpt(roomId,ctx)match{
            case Some(actor) =>actor ! r
            case None => log.debug(s"${ctx.self.path}更新用户信息失败，房间不存在，有可能该用户是主播等待房间开启，房间id=$roomId,用户id=$userId")
          }
          Behaviors.same

        case r@RoomProtocol.HostCloseRoom(roomId)=>
          //如果断开websocket的用户的id能够和已经开的房间里面的信息匹配上，就说明是主播
          getRoomDealerOpt(roomId, ctx) match{
            case Some(roomActor) =>
              if (rooms.contains(roomId)) {
                val liveCodes = rooms(roomId).rtmpInfo.liveCode
                val roomOldInfo = rooms(roomId)
                val roomInfoNew = RoomDetailInfo(roomOldInfo.roomUserInfo, RtmpInfo(AppSettings.rtmpServer, "", Nil), roomOldInfo.hostCode, roomOldInfo.userLiveCodeMap, null)
                getRoomDealerOpt(roomId, ctx) match{
                  case Some(actor) =>
                    roomActor ! r

                  case None => log.debug(s"${ctx.self.path} HostCloseRoom，房间不存在，有可能该用户是主播等待房间开启，房间id=$roomId")

                }
                rooms.put(roomId, roomInfoNew)
              }


            case None =>log.debug(s"${ctx.self.path}关闭房间失败，房间不存在，id=$roomId")
          }
          Behaviors.same


        case ExistRoom(roomId,replyTo) =>
          getRoomDealerOpt(roomId,ctx) match {
            case Some(actor) =>
              replyTo ! true
            case None =>
              replyTo ! true  //Todo
          }
          Behaviors.same

        case JoinRoom(req, rsp) =>
          val roomOldInfo = rooms(req.roomId)
          RoomDao.updateUserCodeMap(req.roomId, roomOldInfo.userLiveCodeMap.asJson.noSpaces).onComplete{
            case Success(_) =>
              rsp ! JoinRoomRsp(Some(UserPushInfo(roomOldInfo.roomUserInfo)))

            case Failure(e) =>
              rsp ! JoinRoomRsp(None, 10013, s"dataBase update error: ${e.getMessage}")
          }
          Behaviors.same

        case Invite(req, rsp) =>
          Behaviors.same

//        case Shield(req, replyTo) =>
//          if (rooms.contains(req.roomId)) {
//            val roomInfo = rooms(req.roomId)
//            val selfCodeOpt = roomInfo.userLiveCodeMap.find(_._2 == req.userId)
//            if (selfCodeOpt.isDefined) {
//              selfCodeOpt.foreach{ s =>
//                roomInfo.roomDealer ! RoomActor.Shield(req, s._1)
//                replyTo ! SuccessRsp()
//              }
//            }
//            else replyTo ! SuccessRsp(100015, "This user doesn't exist")
//          }
//          else replyTo ! SuccessRsp(100014, "This room doesn't exist")
//          Behaviors.same

//        case msg: KickOff =>
//          log.info(s"user-${msg.req.userId} stop live in room: ${msg.req.roomId}")
//          assert(rooms.contains(msg.req.roomId))
//          val roomOldInfo = rooms(msg.req.roomId)
//          val selfCodeOpt = roomOldInfo.userLiveCodeMap.find(_._2 == msg.req.userId)
//          if (selfCodeOpt.isDefined) {
//            selfCodeOpt.foreach{ r =>
//              val userCodeMap = roomOldInfo.userLiveCodeMap.map{ u =>
//                if (u._2 == msg.req.userId){
//                  (u._1, -1L)
//                }
//                else u
//              }
//              val roomNewInfo = roomOldInfo.copy(userLiveCodeMap = userCodeMap)
//              rooms.update(msg.req.roomId, roomNewInfo)
//              getRoomActor(ctx, msg.req.roomId, roomOldInfo.roomUserInfo) ! RoomActor.StopLive4Client(msg.req.userId, r._1)
//              msg.replyTo ! SuccessRsp()
//            }
//          }
//          else {
//            msg.replyTo ! SuccessRsp(100019, "kick off error")
//          }
//          Behaviors.same

//        case msg: StartLive =>
//          assert(rooms.contains(msg.req.roomId))
//          val roomOldInfo = rooms(msg.req.roomId)
//          val stream = msg.req.roomId + "_" + System.currentTimeMillis()
//          val rtmpInfoNew = roomOldInfo.rtmpInfo.copy(stream = stream)
//          val roomActor = getRoomActor(ctx, msg.req.roomId, roomOldInfo.roomUserInfo)
//          rooms.put(msg.req.roomId, RoomDetailInfo(roomOldInfo.roomUserInfo, rtmpInfoNew, roomOldInfo.hostCode, roomOldInfo.userLiveCodeMap, roomActor))
//          msg.replyTo ! StartLiveRsp(rtmpInfoNew, roomOldInfo.hostCode)
//          roomActor ! RoomActor.StartLive(rtmpInfoNew, roomOldInfo.hostCode, roomOldInfo.roomUserInfo.userId)
//          Behaviors.same

//        case msg: StartLive4Client =>
//          val roomOldInfo = rooms(msg.req.roomId)
//          if (roomOldInfo.userLiveCodeMap.exists(_._2 == msg.req.userId)) {
//            msg.replyTo ! StartLive4ClientRsp(Some(roomOldInfo.rtmpInfo), roomOldInfo.userLiveCodeMap.find(_._2 == msg.req.userId).get._1)
//            roomOldInfo.roomDealer ! RoomDealer.StartLive4Client(roomOldInfo.rtmpInfo, roomOldInfo.userLiveCodeMap.find(_._2 == msg.req.userId).get._1)
//          }
//          else {
//            msg.replyTo ! StartLive4ClientFail
//          }
//          Behaviors.same

//        case msg: StopLive =>
//          log.info(s"stop live in room: ${msg.req.roomId}")
//          if ((rooms.contains(msg.req.roomId))) {
//            val liveCodes = rooms(msg.req.roomId).rtmpInfo.liveCode
//            val roomOldInfo = rooms(msg.req.roomId)
//            getRoomActor(ctx, msg.req.roomId, roomOldInfo.roomUserInfo) ! RoomActor.StopLive(RtmpInfo(AppSettings.rtmpServer, "", liveCodes))
//            rooms.put(msg.req.roomId, RoomDetailInfo(roomOldInfo.roomUserInfo, RtmpInfo(AppSettings.rtmpServer, "", Nil), roomOldInfo.hostCode, roomOldInfo.userLiveCodeMap, null))
//            msg.replyTo ! SuccessRsp()
//          }
//          else msg.replyTo ! SuccessRsp(100020, "stop live error")
//          Behaviors.same

//        case msg: StopLive4Client =>
//          log.info(s"user-${msg.req.userId} stop live in room: ${msg.req.roomId}")
//          if ((rooms.contains(msg.req.roomId))) {
//            val roomOldInfo = rooms(msg.req.roomId)
//            val selfCodeOpt = roomOldInfo.userLiveCodeMap.find(_._2 == msg.req.userId)
//            if (selfCodeOpt.isDefined) {
//              selfCodeOpt.foreach{ r =>
//                getRoomActor(ctx, msg.req.roomId, roomOldInfo.roomUserInfo) ! RoomActor.StopLive4Client(msg.req.userId, r._1)
//                msg.replyTo ! SuccessRsp()
//              }
//            }
//            else {
//              msg.replyTo ! SuccessRsp(100019, "stop error, user doesn't exist")
//            }
//          }
//          else msg.replyTo ! SuccessRsp(100021, "stop error, room doesn't exist")
//          Behaviors.same

        case msg: GetRoomList =>
          val rsp = rooms.toList.map(i => RoomData(i._2.userLiveCodeMap,  i._1, i._2.roomUserInfo, if (i._2.roomDealer == null) false else true))
          msg.replyTo ! GetRoomListRsp(rsp)
          Behaviors.same

        case msg: GetUserInfo =>
          if (affiliation.contains(msg.req.userId)) {
            affiliation(msg.req.userId) match {
              case Nil =>
                msg.replyTo ! GetUserInfoRsp(None, None)
              case roomIds =>
                val roomId = roomIds.head
                rooms.get(roomId) match {
                  case None =>
                    msg.replyTo ! GetUserInfoRsp(None, None)
                  case Some(roomInfo) =>
                    val roomData = RoomData(roomInfo.userLiveCodeMap, roomId, roomInfo.roomUserInfo, if (roomInfo.roomDealer == null) false else true)
                    val rtmpInfo = roomInfo.rtmpInfo
                    msg.replyTo ! GetUserInfoRsp(Some(roomData), Some(rtmpInfo))
                }
            }
          }
          else {
            msg.replyTo ! GetUserInfoRsp(None, None)
          }
          Behaviors.same

        case msg: UpdateRoomInfo =>
          if (rooms.contains(msg.req.roomId)) {
            val roomOldInfo = rooms(msg.req.roomId)
            val newName = if (msg.req.roomInfo.roomName.nonEmpty) {
              msg.req.roomInfo.roomName.get
            } else roomOldInfo.roomUserInfo.roomName

            val newDes = if (msg.req.roomInfo.des.nonEmpty) {
              msg.req.roomInfo.des.get
            } else roomOldInfo.roomUserInfo.des

            val newRoomInfo = RoomUserInfo(roomOldInfo.roomUserInfo.userId, newName, newDes)
            rooms.update(msg.req.roomId, RoomDetailInfo(newRoomInfo, roomOldInfo.rtmpInfo, roomOldInfo.hostCode, roomOldInfo.userLiveCodeMap, roomOldInfo.roomDealer))
            RoomDao.updateRoom(msg.req.roomId, newName, newDes).onComplete{
              case Success(_) =>
                msg.replyTo ! ComRsp()
              case Failure(e) =>
                log.info(s"update room at db failed due to $e")
                msg.replyTo ! ComRsp()
            }
            msg.replyTo ! ComRsp()
          }
          else {
            msg.replyTo ! ComRsp(1000015, "This roomId doesn't exist")
          }
          Behaviors.same

        case ChildDead(name, childRef) =>
          log.debug(s"roomManager unwatch $name, ${childRef.path}")
          ctx.unwatch(childRef)
          Behaviors.same


        case x@_ =>
          log.info(s"${ctx.self} got an unknown msg:$x")
          Behaviors.same
      }
    }

//   def getRoomActor(
//    ctx: ActorContext[Command],
//    roomId: Long,
//    roomInfo: RoomUserInfo): ActorRef[RoomActor.Command] = {
//    val childName = s"RoomActor-$roomId"
//
//    ctx.child(childName).getOrElse {
//      ctx.spawn(RoomActor.create(roomId, roomInfo), childName)
//    }.unsafeUpcast[RoomActor.Command]
//   }

  def getRoomDealer(roomId:Long, roomDetailInfo: RoomDetailInfo, ctx: ActorContext[Command]): ActorRef[RoomDealer.Command] = {
    val childrenName = s"roomDealer-${roomId}"
    ctx.child(childrenName).getOrElse {
      val actor = ctx.spawn(RoomDealer.create(roomId, roomDetailInfo), childrenName)
      ctx.watchWith(actor, RoomDealer.ChildDead(childrenName,actor))
      actor
    }.unsafeUpcast[RoomDealer.Command]
  }

  def getRoomDealerOpt(roomId:Long, ctx: ActorContext[Command]): Option[ActorRef[RoomDealer.Command]] = {
    val childrenName = s"roomDealer-${roomId}"
    //    log.debug(s"${ctx.self.path} the child = ${ctx.children},get the roomActor opt = ${ctx.child(childrenName).map(_.unsafeUpcast[RoomActor.Command])}")
    ctx.child(childrenName).map(_.unsafeUpcast[RoomDealer.Command])

  }


  private def busy()
    (
      implicit stashBuffer: StashBuffer[Command],
      timer: TimerScheduler[Command]
    ): Behavior[Command] =
    Behaviors.receive[Command] { (ctx, msg) =>
      msg match {
        case SwitchBehavior(name, behavior, durationOpt, timeOut) =>
          log.info(s"change behavior to $name")
          switchBehavior(ctx, name, behavior, durationOpt, timeOut)

        case TimeOut(m) =>
          log.debug(s"${ctx.self.path} is time out when busy, msg=$m")
          switchBehavior(ctx, "init", init())

        case x =>
          stashBuffer.stash(x)
          Behavior.same

      }
    }


}

package org.seekloud.geek.core

import java.awt.Graphics
import java.awt.image.BufferedImage
import java.io.{File, OutputStream}

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, StashBuffer, TimerScheduler}
import org.bytedeco.ffmpeg.global.{avcodec, avutil}
import org.bytedeco.javacv.{FFmpegFrameFilter, FFmpegFrameRecorder, Frame, Java2DFrameConverter}
import org.seekloud.geek.Boot
import org.seekloud.geek.common.AppSettings
import org.seekloud.geek.core.Grabber.State
import org.seekloud.geek.core.RoomDealer.getVideoDuration
import org.seekloud.geek.models.SlickTables
import org.seekloud.geek.models.dao.VideoDao
import org.seekloud.geek.shared.ptcl.Protocol.OutTarget
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

/**
 * Author: Jason
 * Date: 2020/1/28
 * Time: 12:20
 */
object Recorder {

  var audioChannels = 2 //todo 待议
  val sampleFormat = 1 //todo 待议
  var frameRate = 30
  val bitRate = 2000000

  private var peopleOnline = 0

  private val log = LoggerFactory.getLogger(this.getClass)

  sealed trait Command

  case class UpdateRoomInfo(roomId: Long, layout: Int) extends Command

  case object Init extends Command

  case class GetGrabber(id: String, grabber: ActorRef[Grabber.Command]) extends Command

  case object RestartRecord extends Command

  case class StopRecorder(msg: String) extends Command

  case class GrabberStopped(liveId: String) extends Command

  case object CloseRecorder extends Command

  case class NewFrame(liveId: String, frame: Frame) extends Command

  case class Shield(liveId: String, image: Boolean, audio: Boolean) extends Command

  case class UpdateRecorder(channel: Int, sampleRate: Int, frameRate: Double, width: Int, height: Int, liveId: String) extends Command

  case object TimerKey4Close

  final case object TimerKey4ImageRec

  case object RecordImage extends Command with DrawCommand


  sealed trait DrawCommand

  case class Image4Host(frame: Frame) extends DrawCommand

  case class Image4Others(id: String, frame: Frame) extends DrawCommand

  case class DeleteImage4Others(id: String) extends DrawCommand

  case class SetLayout(layout: Int) extends DrawCommand

  case class NewRecord4Ts(recorder4ts: FFmpegFrameRecorder) extends DrawCommand

  case object Close extends DrawCommand

  case class Ts4Host(var time: Long = 0)

  case class Ts4Others(var time: Long = 0)

  case class Image(var frame: mutable.HashMap[String,Frame] = mutable.HashMap.empty)

  case class Ts4LastImage(var time: Long = -1)

  case class Ts4LastSample(var time: Long = 0)

  def create(roomId: Long, hostId: Long, stream: String, pullLiveId:List[String], roomActor: ActorRef[RoomDealer.Command], layout: Int, outTarget: Option[OutTarget] = None): Behavior[Command] = {
    Behaviors.setup[Command] { ctx =>
      implicit val stashBuffer: StashBuffer[Command] = StashBuffer[Command](Int.MaxValue)
      Behaviors.withTimers[Command] {
        implicit timer =>
          log.info(s"recorder-$roomId start----")
          avutil.av_log_set_level(-8)
          val srcPath = AppSettings.rtmpServer + stream
          println(s"path: $srcPath")
          val recorder4ts = new FFmpegFrameRecorder(srcPath, 640, 480, audioChannels)
          recorder4ts.setInterleaved(true)
          recorder4ts.setFrameRate(frameRate)
          recorder4ts.setVideoBitrate(bitRate)
//          recorder4ts.setVideoCodec(avcodec.AV_CODEC_ID_MPEG2VIDEO)
//          recorder4ts.setAudioCodec(avcodec.AV_CODEC_ID_MP2)
//          recorder4ts.setVideoOption("preset","ultrafast")
          recorder4ts.setVideoOption("crf", "25")
          recorder4ts.setAudioQuality(0)
          recorder4ts.setSampleRate(44100)
          recorder4ts.setMaxBFrames(0)
//          recorder4ts.setFormat("mpegts")
          recorder4ts.setVideoCodec(avcodec.AV_CODEC_ID_H264)
          recorder4ts.setAudioCodec(avcodec.AV_CODEC_ID_AAC)
          recorder4ts.setFormat("flv")
          Try {
            recorder4ts.start()
          } match {
            case Success(_) =>
              log.info(s"$roomId recorder starts successfully")
            case Failure(e) =>
              log.error(s" recorder starts error: ${e.getMessage}")
          }
          val canvas = new BufferedImage(640, 480, BufferedImage.TYPE_3BYTE_BGR)
          val drawer = ctx.spawn(draw(canvas, canvas.getGraphics, Ts4LastImage(), Image(), recorder4ts,
            new Java2DFrameConverter(), new Java2DFrameConverter(),new Java2DFrameConverter, layout, "defaultImg.jpg", roomId, (640, 480)), s"drawer_$roomId")

          //          Boot.roomManager ! RoomManager.RecorderRef(roomId, ctx.self) //fixme 取消注释
          ctx.self ! Init
          idle(roomId, hostId, stream, pullLiveId, roomActor,List.empty, pullLiveId.head, recorder4ts, mutable.HashMap.empty, drawer, mutable.HashMap.empty)
      }
    }
  }

  private def idle(
    roomId: Long,
    hostId: Long,
    stream: String,
    pullLiveId:List[String],
    roomDealer: ActorRef[RoomDealer.Command],
    online: List[Int],
    host: String,
    recorder4ts: FFmpegFrameRecorder,
    ffFilter:  mutable.HashMap[Int, FFmpegFrameFilter],
    drawer: ActorRef[DrawCommand],
    grabbers: mutable.HashMap[String, ActorRef[Grabber.Command]], // id -> grabActor
    indexMap :mutable.HashMap[String, Int] = mutable.HashMap.empty,
    shieldMap :mutable.HashMap[String, State] = mutable.HashMap.empty,
    filterInUse: Option[FFmpegFrameFilter] = None,
  )(
    implicit stashBuffer: StashBuffer[Command],
    timer: TimerScheduler[Command]
  ): Behavior[Command] =
    Behaviors.receive[Command] { (ctx, msg) =>
      msg match {
        case Init =>
          val filters = mutable.HashMap[Int, FFmpegFrameFilter]()
          if (pullLiveId.size > 1) {
            (2 to pullLiveId.size).foreach { p =>
              val complexFilter = s" amix=inputs=$p:duration=longest:dropout_transition=3:weights=3"
              //音频混流
              var filterStr = complexFilter
              (1 to p).reverse.foreach { i =>
                filterStr = s"[${i - 1}:a]" + filterStr
              }
              filterStr += "[a]"
              log.debug(s"audio filter-$p: $filterStr")

              val filter = new FFmpegFrameFilter(
                filterStr,
                audioChannels
              )
              filter.setAudioChannels(audioChannels)
              filter.setSampleFormat(sampleFormat)
              filter.setAudioInputs(p)
//              filter.setFilters(filterStr)
              filters.put(p, filter)
              filter.startUnsafe()
            }
          }
//          if (ffFilter != null) {
//            ffFilter.close()
//          }
//          val complexFilter = s"amix=inputs=${pullLiveId.size}:duration=longest:dropout_transition=3:weights=1 1[a]"
//          //音频混流
//          var filterStr = complexFilter
//          (1 to pullLiveId.size).reverse.foreach { i =>
//            filterStr = s"[${i - 1}:a]" + filterStr
//          }
////          val ffFilterN = new FFmpegFrameFilter(s"[0:a][1:a] amix=inputs=${pullLiveId.size}:duration=longest:dropout_transition=3:weights=1 1[a]", audioChannels)
//          val ffFilterN = new FFmpegFrameFilter(filterStr, audioChannels)
//          ffFilterN.setAudioChannels(audioChannels)
//          ffFilterN.setSampleFormat(sampleFormat)
//          ffFilterN.setAudioInputs(2)
//          ffFilterN.start()
          idle(roomId, hostId, stream, pullLiveId, roomDealer, online, host, recorder4ts,filters, drawer, grabbers, indexMap, shieldMap, filterInUse)

        case GetGrabber(id, grabber) =>
          grabber ! Grabber.GetRecorder(ctx.self)
          grabbers.put(id, grabber)
          Behaviors.same

        case GrabberStopped(liveId) =>
          grabbers.-=(liveId)
          drawer ! DeleteImage4Others(liveId)
          Behaviors.same

        case Shield(liveId, image, audio) =>
          shieldMap.put(liveId, State(image, audio))
          if (image) drawer ! DeleteImage4Others(liveId)
          Behaviors.same

        case UpdateRecorder(channel, sampleRate, f, width, height, liveId) =>
          peopleOnline += 1
          val onlineNew = online :+ liveId.split("_").last.toInt
          shieldMap.put(liveId, State(image = true, audio = true))
          log.info(s"$roomId updateRecorder channel:$channel, sampleRate:$sampleRate, frameRate:$f, width:$width, height:$height")
          if(liveId == host) {
//            log.info(s"$roomId updateRecorder channel:$channel, sampleRate:$sampleRate, frameRate:$f, width:$width, height:$height")
            recorder4ts.setFrameRate(f)
            recorder4ts.setAudioChannels(channel)
            recorder4ts.setSampleRate(sampleRate)
            ffFilter.foreach(_._2.setAudioChannels(channel))
            ffFilter.foreach(_._2.setSampleRate(sampleRate))
            recorder4ts.setImageWidth(width)
            recorder4ts.setImageHeight(height)
//            timer.startPeriodicTimer(TimerKey4ImageRec, RecordImage, 10.millis)
            idle(roomId, hostId, stream, pullLiveId, roomDealer, onlineNew, host, recorder4ts, ffFilter, drawer, grabbers, indexMap, shieldMap, filterInUse)
          }
          else Behaviors.same

        case RecordImage =>
          drawer ! RecordImage
          Behaviors.same

        case NewFrame(liveId, frame) =>
          if (frame.image != null) {
//            println(liveId, frame.timestamp)
            if (liveId == host && shieldMap.filter(_._2.image).exists(_._1 == liveId)) {
//              recorder4ts.record(frame.clone())
              drawer ! Image4Host(frame)
            } else if (pullLiveId.contains(liveId) && shieldMap.filter(_._2.image).exists(_._1 == liveId)) {
              drawer ! Image4Others(liveId, frame)
            } else {
              log.info(s"wrong, liveId, work got wrong img")
            }
          }
          var index = liveId.split("_").last.toInt
          var newFilterInUse = filterInUse
          if (frame.samples != null) {
//            println(s"$liveId timeStamp: ${frame.timestamp}")
            val sampleFrame = frame.clone()
            if (ffFilter.nonEmpty) {
//              println(1,"index: " + index)
              if (online.size == 1 || shieldMap.count(_._2.audio) == 1) {
//                println(2, ffFilter)
                recorder4ts.recordSamples(sampleFrame.sampleRate, sampleFrame.audioChannels, sampleFrame.samples: _*)
//                log.debug(s"record sample...")
              } else {
                val fil = ffFilter(shieldMap.count(_._2.audio))
//                println(3, peopleOnline, fil)
                if (filterInUse.nonEmpty && fil != filterInUse.get) {
                  println(5)
                  filterInUse.get.close()
                  fil.start()
                  newFilterInUse = Some(fil)
                } else if (filterInUse.isEmpty) {
                  println(6)
                  fil.start()
                  newFilterInUse = Some(fil)
                }
                try {
                  if (indexMap.isEmpty && !indexMap.contains(liveId)) indexMap.put(liveId, 0)
                  else if (indexMap.nonEmpty && !indexMap.contains(liveId)) indexMap.put(liveId, indexMap.maxBy(_._2)._2 + 1)
//                  println(7, indexMap, fil, "index: " + (index-1), sampleFrame.audioChannels, sampleFrame.sampleRate, fil.getSampleFormat)
                  fil.pushSamples(indexMap(liveId), sampleFrame.audioChannels, sampleFrame.sampleRate, 1, sampleFrame.samples: _*)
                  val f = fil.pullSamples()
                  if (f != null) {
                    val ff = f.clone()
                    recorder4ts.recordSamples(ff.sampleRate, ff.audioChannels, ff.samples: _*)
//                    log.debug(s"record sample...")
                  }
//                  if (liveId == "1000_1") {
//                    recorder4ts.recordSamples(sampleFrame.sampleRate, sampleFrame.audioChannels, sampleFrame.samples: _*)
//                  }
                } catch {
                  case ex: Exception =>
                    println(s"record sample error: $ex")
                }

              }
            }
            else {
              try {
                recorder4ts.recordSamples(sampleFrame.sampleRate, sampleFrame.audioChannels, sampleFrame.samples: _*)
              }
              catch {
                case e: Exception =>
                  log.info(s"record sample error: ${e.getMessage}")
              }
//              log.debug(s"record sample...")
            }
//            try {
//              if (liveId == host) {
//                ffFilter.pushSamples(0, frame.audioChannels, frame.sampleRate, ffFilter.getSampleFormat, frame.samples: _*)
//              } else if (liveId == client) {
//                ffFilter.pushSamples(1, frame.audioChannels, frame.sampleRate, ffFilter.getSampleFormat, frame.samples: _*)
//              } else {
//                log.info(s"wrong liveId, couple got wrong audio")
//              }
//              val f = ffFilter.pullSamples().clone()
//              if (f != null) {
//                recorder4ts.recordSamples(f.sampleRate, f.audioChannels, f.samples: _*)
//              }
//            } catch {
//              case ex: Exception =>
//                log.debug(s"$liveId record sample error system: $ex")
//            }
          }
          idle(roomId, hostId, stream, pullLiveId, roomDealer, online, host, recorder4ts, ffFilter, drawer, grabbers, indexMap, shieldMap, newFilterInUse)

        case CloseRecorder =>
          val video = SlickTables.rVideo(0L, hostId, roomId, stream.split("_").last.toLong, stream + ".mp4", "",0,"")
          try {
            log.info(s"Recorder-${roomId} is storing video...path: ${AppSettings.videoPath}${video.filename}")
            var d = ""
            val file = new File(s"${AppSettings.videoPath}${video.filename}")
            if(file.exists()){
              d = getVideoDuration(s"${file.getPath}")
              log.info(s"get durationpath:${file.getPath}")
              log.info(s"duration:$d")
              val videoNew = video.copy(length = d)
              VideoDao.addVideo(videoNew)
            }else{
              log.info(s"no record for roomId:${roomId} and startTime:${video.timestamp}")
            }

//            roomDealer ! RoomDealer.StoreVideo(video)
          } catch {
            case e: Exception =>
              log.error(s"$roomId recorder close error ---")
          }
          Behaviors.stopped

        case StopRecorder(msg) =>
          log.info(s"recorder-$roomId stop because $msg")
          drawer ! Close
          filterInUse.foreach(_.close())
          timer.startSingleTimer(TimerKey4Close, CloseRecorder, 5.seconds)
          Behaviors.same

        case x@_ =>
          log.info(s"${ctx.self} got an unknown msg:$x")
          Behaviors.same
      }
    }

  def draw(
    canvas: BufferedImage,
    graph: Graphics,
    lastTime: Ts4LastImage,
    clientFrame: Image,
    recorder4ts: FFmpegFrameRecorder,
    convert1: Java2DFrameConverter, convert2: Java2DFrameConverter,convert:Java2DFrameConverter,
    layout: Int = 0,
    bgImg: String,
    roomId: Long,
    canvasSize: (Int, Int)): Behavior[DrawCommand] = {
    Behaviors.setup[DrawCommand] { ctx =>
      Behaviors.receiveMessage[DrawCommand] {
        case t: Image4Host =>
//          log.info(s"add host")
          val time = t.frame.timestamp
          val img = convert1.convert(t.frame)
          val clientImg = if(clientFrame.frame.nonEmpty) clientFrame.frame.toList.sortBy(_._1.split("_").last.toInt).map(
            i => convert2.convert(i._2)
          )
          else List.empty
          //          graph.clearRect(0, 0, canvasSize._1, canvasSize._2)
          layout match {
            case 0 =>
              graph.drawImage(img, 0, 0, canvasSize._1 , canvasSize._2 , null)
//              graph.drawString("主播", 24, 24)
//              graph.drawImage(clientImg, canvasSize._1 / 2, canvasSize._2 / 4, canvasSize._1 / 2, canvasSize._2 / 2, null)
//              graph.drawString("观众", 344, 24)
              clientImg.zipWithIndex.foreach{ i =>
                val index = i._2
                index match {
                  case 0 => graph.drawImage(i._1, 0, 0, canvasSize._1 / 4, canvasSize._2 / 4, null)
                  case 1 => graph.drawImage(i._1, 0, canvasSize._2 / 4 * 3, canvasSize._1 / 4, canvasSize._2 / 4, null)
                  case 2 => graph.drawImage(i._1, canvasSize._1 / 4 * 3, 0, canvasSize._1 / 4, canvasSize._2 / 4, null)
                  case 3 => graph.drawImage(i._1, canvasSize._1 / 4 * 3, canvasSize._2 / 4 * 3, canvasSize._1 / 4, canvasSize._2 / 4, null)
                  case _ =>
                }
              }

            case 1 =>
              graph.drawImage(img, 0, 0, canvasSize._1, canvasSize._2, null)
              graph.drawString("主播", 24, 24)
//              graph.drawImage(clientImg, canvasSize._1 / 4 * 3, 0, canvasSize._1 / 4, canvasSize._2 / 4, null)
//              graph.drawString("观众", 584, 24)

            case 2 =>
//              graph.drawImage(clientImg, 0, 0, canvasSize._1, canvasSize._2, null)
//              graph.drawString("观众", 24, 24)
              graph.drawImage(img, canvasSize._1 / 4 * 3, 0, canvasSize._1 / 4, canvasSize._2 / 4, null)
              graph.drawString("主播", 584, 24)

          }
          //          if (addTs) {
          //            val serverTime = ChannelWorker.pullClient.getServerTimestamp()
          //             val ts = TimeUtil.format(serverTime)
          //             graph.drawString(ts, canvasSize._1 - 200, 40)
          //          }
          //fixme 此处为何不直接recordImage
          val frame = convert.convert(canvas)
          try{
//            log.info("record image")
            recorder4ts.record(frame.clone())
          }
          catch {
            case e: Exception =>
              log.info(s"record error: ${e.getMessage}")
          }
//          val f = frame.clone()
//          recorder4ts.recordImage(
//            f.imageWidth,
//            f.imageHeight,
//            f.imageDepth,
//            f.imageChannels,
//            f.imageStride,
//            recorder4ts.getPixelFormat,
//            f.image: _*
//          )
//          println(s"record")
          //            lastTime.time = time
          Behaviors.same

        case t: Image4Others =>
//          log.info(s"add ${t.id}")
          clientFrame.frame.put(t.id, t.frame)
          Behaviors.same

        case RecordImage =>
          val frame = convert.convert(canvas)
          try{
            log.info("record image")
            val f = frame.clone()
            recorder4ts.recordImage(
              f.imageWidth,
              f.imageHeight,
              f.imageDepth,
              f.imageChannels,
              f.imageStride,
              recorder4ts.getPixelFormat,
              f.image: _*
            )
          }
          catch {
            case e: Exception =>
              log.info(s"record error: ${e.getMessage}")
          }
          Behaviors.same


        case t: DeleteImage4Others =>
          clientFrame.frame.-=(t.id)
          Behaviors.same

        case m@NewRecord4Ts(recorder4ts) =>
          log.info(s"got msg: $m")
          draw(canvas, graph, lastTime, clientFrame, recorder4ts, convert1, convert2,convert, layout, bgImg, roomId, canvasSize)

        case Close =>
          log.info(s"drawer stopped")
          recorder4ts.releaseUnsafe()
          Behaviors.stopped

        case t: SetLayout =>
          log.info(s"got msg: $t")
          draw(canvas, graph, lastTime, clientFrame, recorder4ts, convert1, convert2,convert, t.layout, bgImg, roomId, canvasSize)
      }
    }
  }

}

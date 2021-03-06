package org.seekloud.geek.models.dao

import org.seekloud.geek.models.SlickTables._
import org.seekloud.geek.utils.DBUtil.db
import org.seekloud.geek.utils.DBUtil.driver.api._
import org.seekloud.geek.Boot.executor

import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
 * Author: Jason
 * Date: 2020/2/6
 * Time: 13:00
 */
object VideoDao {

  def getAllVideo: Future[Seq[tVideo#TableElementType]] = {
    db.run{
      tVideo.result
    }
  }

  def getSecVideo2(inviteeId: Long): Future[Seq[tVideo#TableElementType]] = {
    db.run{
      tVideo.filter(_.userid === inviteeId).result
    }
  }

  def getSecVideo(inviteeId: Long) = {

    val q = tUser join tVideo.sortBy(_.userid).distinctOn(_.filename) on{
      (t1,t2)=>
        List(t1.id === t2.userid).reduceLeft(_ || _)
    }

//    val q = tUser join tVideo.filter(_.invitation === inviteeId) on{
//      (t1,t2)=>
//        List(t1.id === t2.userid).reduceLeft(_ || _)
//    }

//    val q=for {
//      a<-tVideo.filter(_.invitation===inviteeId).result.head
//      c <-tUser.filter(_.id===a.invitation).result
//    } yield (a,c)
//    val innerJoin = for {
//      (inviterName, inviterId) <- q
//    } yield (inviterName,inviterId)
    db.run(q.distinct.sortBy(_._1.id).result)


  }

  def getInviteeVideo(inviteeId: Long,filename: String): Future[Seq[tVideo#TableElementType]] = {
    db.run{
      tVideo.filter(_.invitation === inviteeId).filter(_.filename === filename).result
    }
  }

  def addVideo(video: rVideo): Future[Long] = {
    db.run(
      tVideo.returning(tVideo.map(_.id)) += video
    )
  }

  def deleteVideo(id: Long): Future[Int] =
    db.run{
      tVideo.filter(_.id === id).delete
    }

  //邀请相关

  def getInviter(id:Long) = {
    val q = tUser join tVideo.filter(_.invitation === id) on{
      (t1,t2)=>
        List(t1.id === t2.userid).reduceLeft(_ || _)
    }
    val innerJoin = for {
      (inviterName, inviterId) <- q
    } yield (inviterName,inviterId)
    db.run(innerJoin.distinct.sortBy(_._1.id).result)
  }

  def getInvitee(id:Long) = {
    val q = tUser join tVideo.filter(_.userid === id) on{
      (t1,t2)=>
        List(t1.id === t2.invitation).reduceLeft(_ || _)
    }
    val innerJoin = for {
      (inviteeName, inviteeId) <- q
    } yield (inviteeName,inviteeId)
    db.run(innerJoin.distinct.sortBy(_._1.id).result)
  }
  def checkInvitee(inviteeId: Long,fileName:String) = {

      val q = tVideo.filter(_.invitation ===inviteeId ).filter(_.filename === fileName).result
      db.run(q)
  }

//  def getInviteDetail_new(inviterId:Long,inviteeId:Long) = {
////    val q = tVideo.filter(_.userid ===inviterId ).filter(_.invitation === inviteeId).distinctOn(_.roomid).result
//    val q=for {
//      a<-tVideo.filter(_.userid ===inviterId ).filter(_.invitation === inviteeId).distinctOn(_.roomid).result
////      d<-tVideo.filter(_.userid ===inviterId ).filter(_.invitation === inviteeId).distinctOn(_.roomid).result
//      c <-tVideo ++= a.map(d=>rVideo(-1L,d.userid, d.roomid,d.timestamp,d.filename,d.length,d.invitation,d.filename))
//      d <- a.map(r=>tVideo.filter(_.roomid === r.roomid).result)
//    } yield (a,d,c)
//
//    db.run(q)
//  }


  def getInviteDetail(inviterId:Long,inviteeId:Long) = {
    val q = tVideo.filter(_.userid ===inviterId ).filter(_.invitation === inviteeId).distinctOn(r=>r.roomid).result
    db.run(q)
  }

//  def getInviteDetail2(inviterId:Long,inviteeId:Long) = {
//    val q = tVideo join tVideo.filter(_.userid ===inviterId ).filter(_.invitation === inviteeId)on {
//      (t1, t2) =>
//        List(t1.id === t2.invitation).reduceLeft(_ || _)
//    }
//    val innerJoin = for {
//          (inviteeName, inviteeId) <- q
//        } yield (inviteeName,inviteeId)
//    db.run(q)
//  }

//  def getInviteDetail2(roomId:Long) = {
//    val q = tVideo.filter(_.roomid===roomId).distinctOn(_.filename).result
//    db.run(q)
//  }


  def searchInvitee(inviteeName: String) = {
    val q = tUser.filter(_.name ===inviteeName ).result
    db.run(q)
  }
  def searchInvitee2(inviteeId:Long,roomId:Long) = {
    val q = tVideo.filter(_.invitation ===inviteeId ).filter(_.roomid === roomId).result
    db.run(q)
  }

  def searchInvitee_new (inviteeName: String, roomId: Long) = {
    val q=for {
      a <- tUser.filter(_.name === inviteeName).result.headOption
      c <- {
        if (a.nonEmpty) {
          tVideo.filter(i => i.roomid === roomId && i.invitation === a.get.id)
        }
        else {
          tVideo.filter(i => i.roomid === -1L)
        }
      }.result
    } yield (a, c)
    db.run(q)
  }

  def addInvitee(inviterId: Long,roomId:Long,inviteeId: Long) = {
    //在roomid相应的所有的不同filename，都复制一行
    val q=for {
      a<-tVideo.filter(_.roomid ===roomId ).filter(_.userid===inviterId).filter(_.comment==="").result
      c <-tVideo ++= a.map(d=>rVideo(-1L,d.userid, d.roomid,d.timestamp,d.filename,d.length,inviteeId,""))
    } yield a
    db.run(q)
  }

  def search (inviteeName: String, roomId: Long) = {
    val q = {
      tUser.filter(_.name === inviteeName) join tVideo.filter(_.roomid === roomId) on { (user, video) =>
        user.id === video.invitation
        }
    }.result
    db.run(q)
  }

  def aaa (inviteeName: String, roomId: Long) = {
    val q=for {
      a <- tUser.filter(_.name === inviteeName).result.headOption
      c <- {
        if (a.nonEmpty) {
          tVideo.filter(i => i.roomid === roomId && i.invitation === a.get.id)
        }
        else {
          tVideo.filter(i => i.roomid === -1L)
          }
      }.result
    } yield (a, c)
    db.run(q)
  }

  def getComment(roomid:Long,filename:String)   = {
//    val q=tVideo.filter(_.roomid===roomid).filter(_.filename===filename).result
    val q = tUser join tVideo.filter(_.roomid===roomid).filter(_.filename===filename) on{
      (t1,t2)=>
        List(t1.id === t2.invitation).reduceLeft(_ || _)
    }
    db.run{q.distinct.sortBy(_._1.id).result}
  }

  def addComment(filename:String,userId:Long,commentContent:String)  = {
    val q=for {
      d<-tVideo.filter(_.filename === filename).filter(_.invitation===userId).result.head
      c <-tVideo += rVideo(-1L,d.userid, d.roomid,d.timestamp,d.filename,d.length,d.invitation,commentContent)
    } yield d
    db.run(q)
  }

  def deleteComment(id: Long): Future[Int] ={

    try {
      db.run(tVideo.filter(t=>t.id===id).delete)

      Future.successful(1)
    } catch {
      case e: Throwable =>
        println(s"deleteComment error with error $e")
        Future.successful(-1)
    }
  }


  def checkDeleteComment(id: Long) =
    db.run{
      tVideo.filter(_.id === id).result
    }


//  def getInvitee(id: Long) =
//    db.run{
//      tVideo.filter(_.userid === id).result
//    }

//  def getInvitee2(id: Long) =
//    db.run{
//      tVideo.filter(_.invitation === id).result
//    }

  def delInvitee(inviterId: Long,inviteeId :Long,roomId:Long) =
    db.run{
      tVideo.filter(_.userid === inviterId).filter(_.invitation === inviteeId).filter(_.roomid===roomId).delete
    }

  def main(args: Array[String]): Unit = {
    aaa("qlh", 1418).onComplete{
      case Success(value) => println(value)
      case Failure(exception) =>  println(exception)
    }
  }
}

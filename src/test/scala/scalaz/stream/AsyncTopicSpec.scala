package scalaz.stream

import collection.mutable
import org.scalacheck.{Prop, Properties}
import org.scalacheck.Prop._
import scalaz.{\/-, \/, -\/}
import scala.concurrent.SyncVar
import scalaz.concurrent.Task
import scalaz.stream.Process._

object AsyncTopicSpec extends Properties("topic") {

  case object TestedEx extends Throwable {
    override def fillInStackTrace = this
  }


  //tests basic publisher and subscriber functionality 
  //have two publishers and four subscribers. 
  //each publishers emits own half of elements (odd and evens) and all subscribers must 
  //get that messages
  property("basic") = forAll {
    l: List[Int] =>

      case class SubscriberData(
                                 endSyncVar: SyncVar[Throwable \/ Unit] = new SyncVar[Throwable \/ Unit],
                                 data: mutable.Buffer[Int] = mutable.Buffer.empty)

      val (even, odd) = (l.filter(_ % 2 == 0), l.filter(_ % 2 != 0))

      val topic = async.topic[Int]
      val subscribers = List.fill(4)(SubscriberData())

      def sink[A](f: A => Unit): Process.Sink[Task, A] = {
        io.resource[Unit, A => Task[Unit]](Task.now(()))(_ => Task.now(()))(
          _ => Task.now {
            (i: A) =>
              Task.now {
                f(i)
              }
          }
        )
      }

      def collectToBuffer(buffer: mutable.Buffer[Int]): Sink[Task, Int] = {
        sink {
          (i: Int) =>
            buffer += i
        }
      }

      subscribers.foreach {
        subs =>
          Task(topic.subscribe.to(collectToBuffer(subs.data)).run.runAsync(subs.endSyncVar.put)).run
      }

      val pubOdd = new SyncVar[Throwable \/ Unit]
      Task((Process.emitAll(odd).evalMap(Task.now(_)) to topic.publish).run.runAsync(pubOdd.put)).run

      val pubEven = new SyncVar[Throwable \/ Unit]
      Task((Process.emitAll(even).evalMap(Task.now(_)) to topic.publish).run.runAsync(pubEven.put)).run

      val oddResult = pubOdd.get(3000)
      val evenResult = pubEven.get(3000)

      topic.close.run

      def verifySubscribers(result1: SubscriberData, subId: String) = {
        val result = result1.endSyncVar.get(3000)

        (result.nonEmpty && result.get.isRight :| s"Subscriber $subId finished") &&
          ((result1.data.size == l.size) :| s"Subscriber $subId got all numbers ${result1.data} == ${l.size}") &&
          ((result1.data.filter(_ % 2 == 0) == even) :| s"Subscriber $subId got all even numbers") &&
          ((result1.data.filter(_ % 2 != 0) == odd) :| s"Subscriber $subId got all odd numbers")
      }

      (oddResult.nonEmpty && oddResult.get.isRight :| "Odd numbers were published") &&
        (evenResult.nonEmpty && evenResult.get.isRight :| "Even numbers were published") &&
        (subscribers.zipWithIndex.foldLeft(Prop.propBoolean(true)) {
          case (b, (a, index)) => b && verifySubscribers(a, index.toString)
        })

  }





  //tests once failed all the publishes, subscribers and signals will fail too
  property("fail") = forAll {
    l: List[Int] =>
      (l.size > 0 && l.size < 10000) ==> {
        val topic = async.topic[Int]
        topic.fail(TestedEx).run


        val emitted = new SyncVar[Throwable \/ Unit]
        Task((Process.emitAll(l).evalMap(Task.now(_)) to topic.publish).run.runAsync(emitted.put)).run

        val sub1 = new SyncVar[Throwable \/ Seq[Int]]

        Task(topic.subscribe.runLog.runAsync(sub1.put)).run


        emitted.get(3000)
        sub1.get(3000)


        (emitted.get(0).nonEmpty && emitted.get == -\/(TestedEx)) :| "publisher fails" &&
          (sub1.get(0).nonEmpty && sub1.get == -\/(TestedEx)) :| "subscriber fails"
      }
  }



  property("writer.state.startWith") = forAll {
    l: List[String] =>
      (l.size > 0 && l.size < 10000) ==> {

        val w: Writer1[Long, String, Int] = {
          def go(acc: Long): Writer1[Long, String, Int] = {
            receive1[String, Long \/ Int] {
              s =>
                val t: Long = s.size.toLong + acc
                emit(-\/(t)) fby
                  emit(\/-(s.size)) fby
                  go(t)
            }
          }
          go(0L)
        }

        val topic = async.writerTopic(emit(-\/(0L)) fby w)
        ((Process(l: _*).toSource to topic.publish)).run.run

        val subscriber = topic.subscribe.take(1).runLog.run

        topic.close.run


        subscriber == List(-\/(l.map(_.size).sum))
      }
  }


}

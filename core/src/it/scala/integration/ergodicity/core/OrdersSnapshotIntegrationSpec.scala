package integration.ergodicity.core

import AkkaIntegrationConfigurations._
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import akka.actor.{Actor, Props, ActorSystem}
import akka.event.Logging
import akka.pattern.ask
import akka.testkit.{TestActorRef, ImplicitSender, TestFSMRef, TestKit}
import akka.util.Timeout
import akka.util.duration._
import com.ergodicity.cgate.Connection.StartMessageProcessing
import com.ergodicity.cgate._
import com.ergodicity.cgate.config.ConnectionConfig.Tcp
import com.ergodicity.cgate.config.Replication._
import com.ergodicity.core.order.OrdersSnapshotActor
import com.ergodicity.core.order.OrdersSnapshotActor.{OrdersSnapshot, GetOrdersSnapshot}
import config.{Replication, CGateConfig}
import java.io.File
import java.util.concurrent.TimeUnit
import org.scalatest.{BeforeAndAfterAll, WordSpec}
import ru.micexrts.cgate.{CGate, Connection => CGConnection, Listener => CGListener}

class OrdersSnapshotIntegrationSpec extends TestKit(ActorSystem("OrdersSnapshotIntegrationSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with BeforeAndAfterAll {
  val log = Logging(system, self)

  val Host = "localhost"
  val Port = 4001

  val RouterConnection = Tcp(Host, Port, system.name)

  override def beforeAll() {
    val props = CGateConfig(new File("cgate/scheme/cgate_dev.ini"), "11111111")
    CGate.open(props())
  }

  override def afterAll() {
    system.shutdown()
    CGate.close()
  }

  implicit val timeout = Timeout(5.seconds)

  "OrdersSnapshotActor" must {
    "load snapshots from FORTS_FUT/OPTORDERBOOK_REPL" in {

      val underlyingConnection = new CGConnection(RouterConnection())

      val connection = TestFSMRef(new Connection(underlyingConnection, Some(500.millis)), "Connection")

      val futOrderBook = TestFSMRef(new DataStream, "FutOrderBookDataStream")
      val optOrderBook = TestFSMRef(new DataStream, "OptOrderBookDataStream")

      // Listeners
      val futListenerConfig = Replication("FORTS_FUTORDERBOOK_REPL", new File("cgate/scheme/Orderbook.ini"), "CustReplScheme")
      val underlyingFutListener = new CGListener(underlyingConnection, futListenerConfig(), new DataStreamSubscriber(futOrderBook))
      val futListener = TestFSMRef(new Listener(underlyingFutListener), "FutTradeListener")

      val optListenerConfig = Replication("FORTS_OPTORDERBOOK_REPL", new File("cgate/scheme/Orderbook.ini"), "CustReplScheme")
      val underlyingOptListener = new CGListener(underlyingConnection, optListenerConfig(), new DataStreamSubscriber(optOrderBook))
      val optListener = TestFSMRef(new Listener(underlyingOptListener), "OptTradeListener")

      // Snapshots
      val futuresSnapshot = TestActorRef(new OrdersSnapshotActor(futOrderBook), "FutturesSnapshot")
      val optionsSnapshot = TestActorRef(new OrdersSnapshotActor(optOrderBook), "OptionsSnapshot")

      Thread.sleep(1000)

      // Log received snapshots
      (futuresSnapshot ? GetOrdersSnapshot).mapTo[OrdersSnapshot] onSuccess {case futures =>
        log.info("Futures snapshot = "+futures.revision+", moment = "+futures.moment+", size = "+futures.orders.size)
      }

      (optionsSnapshot ? GetOrdersSnapshot).mapTo[OrdersSnapshot] onSuccess {case options =>
        log.info("Options snapshot = "+options.revision+", moment = "+options.moment+", size = "+options.orders.size)
      }

      // On connection Activated open listeners etc
      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, Active) =>
            // Open Listener in Combined mode
            futListener ! Listener.Open(ReplicationParams(ReplicationMode.Snapshot))
            optListener ! Listener.Open(ReplicationParams(ReplicationMode.Snapshot))


            // Process messages
            connection ! StartMessageProcessing(500.millis)
        }
      })))

      // Open connections and track it's status
      connection ! Connection.Open

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }
}
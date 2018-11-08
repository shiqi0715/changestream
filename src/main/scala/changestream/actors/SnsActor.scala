package changestream.actors

import scala.language.postfixOps
import scala.util.{Failure, Success}
import akka.actor.{Actor, ActorRef, ActorRefFactory}
import changestream.actors.PositionSaver.EmitterResult
import changestream.events.{MutationEvent, MutationWithInfo}
import changestream.helpers.Topic
import com.amazonaws.services.sns.AmazonSNSAsyncClient
import com.amazonaws.services.sns.model.CreateTopicResult
import com.github.dwhjames.awswrap.sns.AmazonSNSScalaClient
import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.Future

class SnsActor(getNextHop: ActorRefFactory => ActorRef,
               config: Config = ConfigFactory.load().getConfig("changestream")) extends Actor {

  protected val nextHop = getNextHop(context)
  protected val log = LoggerFactory.getLogger(getClass)
  protected implicit val ec = context.dispatcher

  protected val TIMEOUT = config.getLong("aws.timeout")

  protected val snsTopic = config.getString("aws.sns.topic")
  protected val snsTopicHasVariable = snsTopic.contains("{")

  protected val client = new AmazonSNSScalaClient(
    AmazonSNSAsyncClient.
      asyncBuilder().
      withRegion(config.getString("aws.region")).
      build().
      asInstanceOf[AmazonSNSAsyncClient]
  )
  protected val topicArns = mutable.HashMap.empty[String, Future[CreateTopicResult]]

  protected def getOrCreateTopic(topic: String) = {
    val ret = client.createTopic(topic)
    ret onComplete {
      case Success(topicResult) =>
        log.info(s"Ready to publish messages to SNS topic ${topic} (${topicResult.getTopicArn}).")
      case Failure(exception) =>
        log.error(s"Failed to find/create SNS topic ${topic}.", exception.getMessage)
        throw exception
    }
    ret
  }

  override def postStop = {
    import java.util.concurrent.TimeUnit
    // attempt graceful shutdown
    val executor = client.client.getExecutorService()
    executor.shutdown()
    executor.awaitTermination(60, TimeUnit.SECONDS)
    client.client.shutdown()
  }

  def receive = {
    case MutationWithInfo(mutation, pos, _, _, Some(message: String)) =>
      log.debug(s"Received message of size ${message.length}")
      log.trace(s"Received message: ${message}")

      val topic = Topic.getTopic(mutation, snsTopic, snsTopicHasVariable)
      val topicArn = topicArns.getOrElse(topic, getOrCreateTopic(topic))
      topicArns.update(topic, topicArn)

      val request = topicArn.flatMap(topic => client.publish(topic.getTopicArn, message))

      request onComplete {
        case Success(result) =>
          log.debug(s"Successfully published message to ${topic} (messageId ${result.getMessageId})")
          nextHop ! EmitterResult(pos)
        case Failure(exception) =>
          log.error(s"Failed to publish to topic ${topic}: ${exception.getMessage}")
          throw exception
          // TODO retry N times then exit
      }
  }
}

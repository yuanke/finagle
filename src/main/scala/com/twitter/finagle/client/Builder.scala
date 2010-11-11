package com.twitter.finagle.client

import collection.JavaConversions._

import java.net.InetSocketAddress
import java.util.Collection
import java.util.concurrent.{TimeUnit, Executors}

import org.jboss.netty.channel._
import org.jboss.netty.channel.socket.nio._
import org.jboss.netty.handler.codec.http._

import com.twitter.ostrich
import com.twitter.util.TimeConversions._
import com.twitter.util.Duration

import com.twitter.finagle.channel._
import com.twitter.finagle.http.RequestLifecycleSpy
import com.twitter.finagle.thrift.ThriftClientCodec
import com.twitter.finagle.util._

sealed abstract class Codec {
  val pipelineFactory: ChannelPipelineFactory
}

object Http extends Codec {
  val pipelineFactory =
    new ChannelPipelineFactory {
      def getPipeline() = {
        val pipeline = Channels.pipeline()
        pipeline.addLast("httpCodec", new HttpClientCodec())
        pipeline.addLast("lifecycleSpy", RequestLifecycleSpy)
        pipeline
      }
    }
}

object Thrift extends Codec {
  val pipelineFactory =
    new ChannelPipelineFactory {
      def getPipeline() = {
        val pipeline = Channels.pipeline()
        pipeline.addLast("thriftCodec", new ThriftClientCodec)
        pipeline
      }
    }
}

object Codec {
  val http = Http
  val thrift = Thrift
}

sealed abstract class StatsReceiver
case class Ostrich(provider: ostrich.StatsProvider) extends StatsReceiver

object Builder {
  def apply() = new Builder
  def get() = apply()

  val channelFactory =
    new NioClientSocketChannelFactory(
      Executors.newCachedThreadPool(),
      Executors.newCachedThreadPool())

  case class Timeout(value: Long, unit: TimeUnit) {
    def duration = Duration.fromTimeUnit(value, unit)
  }

  def parseHosts(hosts: String): java.util.List[InetSocketAddress] = {
    val hostPorts = hosts split Array(' ', ',') filter (_ != "") map (_.split(":"))
    hostPorts map { hp => new InetSocketAddress(hp(0), hp(1).toInt) } toList
  }
}

class IncompleteClientSpecification(message: String)
  extends Exception(message)

// We're nice to java.
case class Builder(
  _hosts: Option[Seq[InetSocketAddress]],
  _codec: Option[Codec],
  _connectionTimeout: Builder.Timeout,
  _requestTimeout: Builder.Timeout,
  _statsReceiver: Option[StatsReceiver],
  _sampleWindow: Builder.Timeout,
  _sampleGranularity: Builder.Timeout,
  _name: Option[String])
{
  import Builder._
  def this() = this(
    None,
    None,
    Builder.Timeout(Long.MaxValue, TimeUnit.MILLISECONDS),
    Builder.Timeout(Long.MaxValue, TimeUnit.MILLISECONDS),
    None,
    Builder.Timeout(10, TimeUnit.MINUTES),
    Builder.Timeout(10, TimeUnit.SECONDS),
    None
  )

  def hosts(hostnamePortCombinations: String) =
    copy(_hosts = Some(Builder.parseHosts(hostnamePortCombinations)))

  def hosts(addresses: Collection[InetSocketAddress]) =
    copy(_hosts = Some(addresses toSeq))

  def codec(codec: Codec) =
    copy(_codec = Some(codec))

  def connectionTimeout(value: Long, unit: TimeUnit) =
    copy(_connectionTimeout = Timeout(value, unit))

  def requestTimeout(value: Long, unit: TimeUnit) =
    copy(_requestTimeout = Timeout(value, unit))

  def reportTo(receiver: StatsReceiver) =
    copy(_statsReceiver = Some(receiver))

  def sampleWindow(value: Long, unit: TimeUnit) =
    copy(_sampleWindow = Timeout(value, unit))

  def sampleGranularity(value: Long, unit: TimeUnit) =
    copy(_sampleGranularity = Timeout(value, unit))

  def name(value: String) = copy(_name = Some(value))

  def build() = {
    val (hosts, codec) = (_hosts, _codec) match {
      case (None, _) =>
        throw new IncompleteClientSpecification("No hosts were specified")
      case (_, None) =>
        throw new IncompleteClientSpecification("No codec was specified")
      case (Some(hosts), Some(codec)) =>
        (hosts, codec)
    }

    val bootstraps = hosts map { host =>
      val bs = new BrokerClientBootstrap(channelFactory)
      bs.setPipelineFactory(codec.pipelineFactory)
      bs.setOption("remoteAddress", host)
      bs
     }

    // TODO: request timeout

    val sampleRepository = new SampleRepository
    val timeoutBrokers = bootstraps map (
     (new ChannelPool(_))        andThen
     (new PoolingBroker(_))      andThen
     (new TimeoutBroker(_, _requestTimeout.value, _requestTimeout.unit)))

    // Construct sample stats.
    val granularity = _sampleGranularity.duration
    val window      = _sampleWindow.duration
    if (window < granularity) {
      throw new IncompleteClientSpecification(
        "window smaller than granularity!")
    }
    val numBuckets = math.max(1, window.inMilliseconds / granularity.inMilliseconds)
    val statsMaker = () => new TimeWindowedSample[ScalarSample](numBuckets.toInt, granularity)
    val namePrefix = _name map ("%s_".format(_)) getOrElse ""

    val statsBrokers = _statsReceiver match {
      case Some(Ostrich(provider)) =>
        (timeoutBrokers zip hosts) map { case (broker, host) =>
          val prefix = namePrefix
          val suffix = "_%s:%d".format(host.getHostName, host.getPort)
          val samples = new OstrichSampleRepository(prefix, suffix, provider) {
            def makeStats = statsMaker
          }
          new StatsLoadedBroker(broker, samples)
        }

      case _ =>
        timeoutBrokers map { broker =>
          new StatsLoadedBroker(broker, new SampleRepository)
        }
    }

    new LoadBalancedBroker(statsBrokers)
  }

  def buildClient[Request, Reply]() =
    new Client[HttpRequest, HttpResponse](build())
}


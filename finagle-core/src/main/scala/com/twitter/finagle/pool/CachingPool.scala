package com.twitter.finagle.pool

import collection.mutable.Queue
import scala.annotation.tailrec

import com.twitter.util.{Future, Time, Duration}

import com.twitter.finagle.{Service, ServiceFactory, ServiceProxy, ServiceClosedException}
import com.twitter.finagle.util.{Timer, Cache}
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}

/**
 * A pool that temporarily caches items from the underlying one, up to
 * the given timeout amount of time.
 */
private[finagle] class CachingPool[Req, Rep](
  factory: ServiceFactory[Req, Rep],
  cacheSize: Int,
  ttl: Duration,
  timer: com.twitter.util.Timer = Timer.default,
  statsReceiver: StatsReceiver = NullStatsReceiver)
  extends ServiceFactory[Req, Rep]
{
  private[this] val cache =
    new Cache[Service[Req, Rep]](cacheSize, ttl, timer, Some(_.release()))
  @volatile private[this] var isOpen = true
  private[this] val sizeGauge =
    statsReceiver.addGauge("pool_cached") { cache.size }

  private[this] class WrappedService(underlying: Service[Req, Rep])
    extends ServiceProxy[Req, Rep](underlying)
  {
    override def release() =
      if (this.isAvailable && CachingPool.this.isOpen)
        cache.put(underlying)
      else
        underlying.release()
  }

  @tailrec
  private[this] def get(): Option[Service[Req, Rep]] = {
    cache.get() match {
      case s@Some(service) if service.isAvailable => s
      case Some(service) /* unavailable */ => service.release(); get()
      case None => None
    }
  }

  def make(): Future[Service[Req, Rep]] = synchronized {
    if (!isOpen) Future.exception(new ServiceClosedException) else {
      get() match {
        case Some(service) =>
          Future.value(new WrappedService(service))
        case None =>
          factory.make() map { new WrappedService(_) }
      }
    }
  }

  def close() = synchronized {
    isOpen = false

    cache.evictAll()
    factory.close()
  }

  override def isAvailable = isOpen

  override val toString = "caching_pool_%s".format(factory.toString)
}

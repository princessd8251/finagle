package com.twitter.finagle.loadbalancer

import com.twitter.finagle.service.FailingFactory
import com.twitter.finagle.stats.{Counter, StatsReceiver}
import com.twitter.finagle.util.{Ema, Ring, Rng}
import com.twitter.finagle._
import com.twitter.util.{Activity, Duration, Future, Return, Throw, Time, Timer}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable.VectorBuilder
import scala.collection.mutable.ListBuffer

/**
 * The aperture load-band balancer balances load to the smallest
 * subset ("aperture") of services so that the concurrent load to each service,
 * measured over a window specified by `smoothWin`, stays within the
 * load band delimited by `lowLoad` and `highLoad`.
 *
 * Unavailable services are not counted--the aperture expands as
 * needed to cover those that are available.
 *
 * For example, if the load band is [0.5, 2], the aperture will be
 * adjusted so that no service inside the aperture has a load less
 * than 0.5 or more than 2, so long as offered load permits it.
 *
 * The default load band, [0.5, 2], matches closely the load distribution
 * given by least-loaded without any aperturing.
 *
 * Among the benefits of aperture balancing are:
 *
 *  1. A client uses resources commensurate to offered load. In particular,
 *     it does not have to open sessions with every service in a large cluster.
 *     This is especially important when offered load and cluster capacity
 *     are mismatched.
 *  2. It balances over fewer, and thus warmer, services. This enhances the
 *     efficacy of the fail-fast mechanisms, etc. This also means that clients pay
 *     the penalty of session establishment less frequently.
 *  3. It increases the efficacy of least-loaded balancing which, in order to
 *     work well, requires concurrent load. The load-band balancer effectively
 *     arranges load in a manner that ensures a higher level of per-service
 *     concurrency.
 */
private[loadbalancer] class ApertureLoadBandBalancer[Req, Rep](
    protected val activity: Activity[Traversable[ServiceFactory[Req, Rep]]],
    protected val smoothWin: Duration,
    protected val lowLoad: Double,
    protected val highLoad: Double,
    protected val minAperture: Int,
    protected val maxEffort: Int,
    protected val rng: Rng,
    protected implicit val timer: Timer,
    protected val statsReceiver: StatsReceiver,
    protected val emptyException: NoBrokersAvailableException)
  extends Balancer[Req, Rep]
  with Aperture[Req, Rep]
  with LoadBand[Req, Rep]
  with Updating[Req, Rep] {
  require(minAperture > 0, s"minAperture must be > 0, but was $minAperture")
  protected[this] val maxEffortExhausted: Counter = statsReceiver.counter("max_effort_exhausted")
}

object Aperture {
  // Note, we need to have a non-zero range for each node
  // in order for Ring.pick2 to pick distinctly. That is,
  // `RingWidth` should be wider than the number of slices
  // in the ring.
  private val RingWidth = Int.MaxValue

  // Ring that maps to 0 for every value.
  private val ZeroRing = Ring(1, RingWidth)
}

/**
 * The aperture distributor balances load onto a window--the
 * aperture--of underlying capacity. The distributor exposes a
 * control mechanism so that a controller can adjust the aperture
 * according to load conditions.
 *
 * The window contains a number of discrete serving units, one for each
 * node. No load metric is prescribed: this can be mixed in separately.
 *
 * The underlying nodes are arranged in a consistent fashion: an
 * aperture of a given size always refers to the same set of nodes; a
 * smaller aperture to a subset of those nodes. Thus it is relatively
 * harmless to adjust apertures frequently, since underlying nodes
 * are typically backed by pools, and will be warm on average.
 */
private[loadbalancer] trait Aperture[Req, Rep] { self: Balancer[Req, Rep] =>
  import Aperture._

  protected def rng: Rng

  /**
   * The minimum allowable aperture. Must be greater than zero.
   */
  protected def minAperture: Int

  private[this] val gauge = statsReceiver.addGauge("aperture") { aperture }

  protected class Distributor(vector: Vector[Node], initAperture: Int)
    extends DistributorT[Node](vector) {
    type This = Distributor

    private[this] val (ring, unitWidth, maxAperture, minAperture) =
      if (vector.isEmpty) {
        (ZeroRing, RingWidth, RingWidth, 0)
      } else {
        val numNodes = vector.size
        val ring = Ring(numNodes, RingWidth)
        val unit = RingWidth / numNodes
        val max = RingWidth / unit

        // The logic of pick() assumes that the aperture size is less than or
        // equal to number of available nodes, and may break if that is not true.
        val min = math.min(
          math.min(Aperture.this.minAperture, max),
          numNodes
        )

        (ring, unit, max, min)
      }

    @volatile private[Aperture] var aperture = initAperture

    // Make sure the aperture is within bounds [1, maxAperture].
    adjust(0)

    protected[Aperture] def adjust(n: Int) {
      aperture = math.max(minAperture, math.min(maxAperture, aperture + n))
    }

    def rebuild(): This = rebuild(vector)

    def rebuild(vector: Vector[Node]): This = {
      // We need to sort the nodes, with priority being given to the most
      // healthy nodes, then by token. There is an race condition with the
      // sort: the status can change before the sort is finished but this
      // is ignored as if the transition simply happened immediately after
      // the rebuild.

      // The token is immutable, so no race condition here.
      val byToken = vector.sortBy(_.token)

      // We bring the most healthy nodes to the front.
      val resultNodes = new VectorBuilder[Node]
      val busyNodes = new ListBuffer[Node]
      val closedNodes = new ListBuffer[Node]

      byToken.foreach { node =>
        node.status match {
          case Status.Open   => resultNodes += node
          case Status.Busy   => busyNodes += node
          case Status.Closed => closedNodes += node
        }
      }

      resultNodes ++= busyNodes ++= closedNodes

      new Distributor(resultNodes.result, aperture)
    }

    /**
     * The number of available serving units.
     */
    def units: Int = maxAperture

    // We use power of two choices to pick nodes. This keeps things
    // simple, but we could reasonably use a heap here, too.
    def pick(): Node = {
      if (vector.isEmpty)
        return failingNode(emptyException)

      if (vector.size == 1)
        return vector(0)

      val (i, j) = ring.pick2(rng, 0, aperture * unitWidth)
      val a = vector(i)
      val b = vector(j)

      // If both nodes are in the same health status, we pick the least loaded
      // one. Otherwise we pick the one that's healthier.
      if (a.status == b.status) {
        if (a.load < b.load) a else b
      } else {
        if (Status.best(a.status, b.status) == a.status) a else b
      }
    }

    // Since Aperture is probabilistic (it uses P2C) in its selection,
    // we don't partition and select only from healthy nodes. Instead, we
    // rely on the near zero probability of selecting two down nodes (given
    // the layers of retries above us). However, namers can still force
    // rebuilds when the underlying set of nodes changes (eg: some of the
    // nodes were unannounced and restarted).
    def needsRebuild: Boolean = false
  }

  protected def initDistributor(): Distributor =
    new Distributor(Vector.empty, 1)

  /**
   * Adjust the aperture by `n` serving units.
   */
  protected def adjust(n: Int): Unit = invoke(_.adjust(n))

  /**
   * Widen the aperture by one serving unit.
   */
  protected def widen(): Unit = adjust(1)

  /**
   * Narrow the aperture by one serving unit.
   */
  protected def narrow(): Unit = adjust(-1)

  /**
   * The current aperture. This is never less than 1, or more
   * than `units`.
   */
  protected def aperture: Int = dist.aperture

  /**
   * The number of available serving units.
   * The maximum aperture size.
   */
  protected def units: Int = dist.units
}

/**
 * LoadBand is an aperture controller targeting a load band.
 * `lowLoad` and `highLoad` are watermarks used to adjust the
 * aperture. Whenever the the capacity-adjusted, exponentially
 * smoothed, load is less than `lowLoad`, the aperture is shrunk by
 * one serving unit; when it exceeds `highLoad`, the aperture is
 * opened by one serving unit.
 *
 * The upshot is that `lowLoad` and `highLoad` define an acceptable
 * band of load for each serving unit.
 */
private[loadbalancer] trait LoadBand[Req, Rep] { self: Balancer[Req, Rep] with Aperture[Req, Rep] =>
  /**
   * The time-smoothing factor used to compute the capacity-adjusted
   * load. Exponential smoothing is used to absorb large spikes or
   * drops. A small value is typical, usually in the order of
   * seconds.
   */
  protected def smoothWin: Duration

  /**
   * The lower bound of the load band.
   * Must be less than [[highLoad]].
   */
  protected def lowLoad: Double

  /**
   * The upper bound of the load band.
   * Must be greater than [[lowLoad]].
   */
  protected def highLoad: Double

  private[this] val total = new AtomicInteger(0)
  private[this] val monoTime = new Ema.Monotime
  private[this] val ema = new Ema(smoothWin.inNanoseconds)

  /**
   * Adjust `node`'s load by `delta`.
   */
  private[this] def adjustNode(node: Node, delta: Int) = {
    node.counter.addAndGet(delta)

    // this is synchronized so that sampling the monotonic time and updating
    // based on that time are atomic, and we don't run into problems like:
    //
    // t1:
    // sample (ts = 1)
    // t2:
    // sample (ts = 2)
    // update (ts = 2)
    // t1:
    // update (ts = 1) // breaks monotonicity
    val avg = synchronized {
      ema.update(monoTime.nanos(), total.addAndGet(delta))
    }

    // Compute the capacity-adjusted average load and adjust the
    // aperture accordingly. We make only directional adjustments as
    // required, incrementing or decrementing the aperture by 1.
    //
    // Adjustments are somewhat racy: aperture and units may change
    // from underneath us. But this is not a big deal. If we
    // overshoot, the controller will self-correct quickly.
    val a = avg/aperture

    if (a >= highLoad && aperture < units)
      widen()
    else if (a <= lowLoad && aperture > minAperture)
      narrow()
  }

  protected case class Node(
      factory: ServiceFactory[Req, Rep],
      counter: AtomicInteger,
      token: Int)
    extends ServiceFactoryProxy[Req, Rep](factory)
    with NodeT[Req, Rep] {
    type This = Node

    def load: Double = counter.get
    def pending: Int = counter.get

    override def apply(conn: ClientConnection): Future[Service[Req, Rep]] = {
      adjustNode(this, 1)
      super.apply(conn).transform {
        case Return(svc) =>
          Future.value(new ServiceProxy(svc) {
            override def close(deadline: Time): Future[Unit] =
              super.close(deadline).ensure {
                adjustNode(Node.this, -1)
              }
          })

        case t@Throw(_) =>
          adjustNode(this, -1)
          Future.const(t)
      }
    }
  }

  protected def newNode(factory: ServiceFactory[Req, Rep], statsReceiver: StatsReceiver): Node =
    Node(factory, new AtomicInteger(0), rng.nextInt())

  private[this] val failingLoad = new AtomicInteger(0)
  protected def failingNode(cause: Throwable): Node = Node(
    new FailingFactory(cause), failingLoad, 0)
}

/*
 * Copyright 2020 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect
package testkit

import cats.{~>, Group, Monad, Monoid}
import cats.data.Kleisli
import cats.effect.kernel.{Concurrent, Fiber, Outcome, Temporal}
import cats.syntax.all._

import scala.concurrent.duration._

/*
 * NB: Even though we expect this to be usable on implementations which
 * interpret to multiple underlying threads, we never have to worry about
 * race conditions on `now` since we ensure that `Time` instances are
 * unique per-fiber. Thus, a volatile var is sufficient.
 */
final class Time private[effect] (@volatile private[effect] var now: FiniteDuration) {
  private[effect] def fork(): Time =
    new Time(now)
}

object TimeT {

  def liftF[F[_], A](fa: F[A]): TimeT[F, A] =
    Kleisli.liftF(fa)

  def liftK[F[_]]: F ~> TimeT[F, *] =
    new (F ~> TimeT[F, *]) {
      override def apply[A](fa: F[A]): TimeT[F, A] = liftF(fa)
    }

  def run[F[_], A](tfa: TimeT[F, A]): F[A] =
    tfa.run(new Time(0.millis))

  implicit def groupTimeT[F[_]: Monad, A](implicit A: Group[A]): Group[TimeT[F, A]] =
    new Group[TimeT[F, A]] {

      val empty = Monoid[A].empty.pure[TimeT[F, *]]

      def combine(left: TimeT[F, A], right: TimeT[F, A]) =
        (left, right).mapN(_ |+| _)

      def inverse(a: TimeT[F, A]) =
        a.map(_.inverse())
    }

  implicit def temporalForTimeT[F[_], E](
      implicit F: Concurrent[F, E]): Temporal[TimeT[F, *], E] =
    new TimeTTemporal[F, E]

  private[this] class TimeTTemporal[F[_], E](implicit F: Concurrent[F, E])
      extends Temporal[TimeT[F, *], E] {

    def pure[A](x: A): TimeT[F, A] =
      Kleisli.pure(x)

    def handleErrorWith[A](fa: TimeT[F, A])(f: E => TimeT[F, A]): TimeT[F, A] =
      Kleisli { time => F.handleErrorWith(fa.run(time))(f.andThen(_.run(time))) }

    def raiseError[A](e: E): TimeT[F, A] =
      TimeT.liftF(F.raiseError(e))

    val canceled: TimeT[F, Unit] =
      TimeT.liftF(F.canceled)

    def onCancel[A](fa: TimeT[F, A], fin: TimeT[F, Unit]): TimeT[F, A] =
      Kleisli { time => F.onCancel(fa.run(time), fin.run(time)) }

    val cede: TimeT[F, Unit] =
      TimeT.liftF(F.cede)

    def never[A]: TimeT[F, A] =
      TimeT.liftF(F.never[A])

    def racePair[A, B](fa: TimeT[F, A], fb: TimeT[F, B]): TimeT[
      F,
      Either[
        (Outcome[TimeT[F, *], E, A], Fiber[TimeT[F, *], E, B]),
        (Fiber[TimeT[F, *], E, A], Outcome[TimeT[F, *], E, B])]] =
      Kleisli { time =>
        val forkA = time.fork()
        val forkB = time.fork()

        // TODO this doesn't work (yet) because we need to force the "faster" effect to win the race, which right now isn't happening
        F.racePair(fa.run(forkA), fb.run(forkB)).map {
          case Left((oca, delegate)) =>
            time.now = forkA.now
            Left((oca.mapK(TimeT.liftK[F]), fiberize(forkB, delegate)))

          case Right((delegate, ocb)) =>
            time.now = forkB.now
            Right((fiberize(forkA, delegate), ocb.mapK(TimeT.liftK[F])))
        }
      }

    def start[A](fa: TimeT[F, A]): TimeT[F, Fiber[TimeT[F, *], E, A]] =
      for {
        time <- Kleisli.ask[F, Time]
        forked = time.fork()
        delegate <- Kleisli.liftF(F.start(fa.run(forked)))
      } yield fiberize(forked, delegate)

    def uncancelable[A](body: TimeT[F, *] ~> TimeT[F, *] => TimeT[F, A]): TimeT[F, A] =
      Kleisli { time =>
        F.uncancelable { poll =>
          val poll2 = new (TimeT[F, *] ~> TimeT[F, *]) {
            def apply[a](tfa: TimeT[F, a]) =
              Kleisli { time2 => poll(tfa.run(time2)) }
          }

          body(poll2).run(time)
        }
      }

    def forceR[A, B](fa: TimeT[F, A])(fb: TimeT[F, B]): TimeT[F, B] =
      Kleisli(time => F.forceR(fa.run(time))(fb.run(time)))

    def flatMap[A, B](fa: TimeT[F, A])(f: A => TimeT[F, B]): TimeT[F, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => TimeT[F, Either[A, B]]): TimeT[F, B] =
      Kleisli { time => F.tailRecM(a)(f.andThen(_.run(time))) }

    val monotonic: TimeT[F, FiniteDuration] =
      Kleisli.ask[F, Time].map(_.now)

    val realTime =
      pure(0.millis) // TODO is there anything better here?

    def sleep(time: FiniteDuration): TimeT[F, Unit] =
      Kleisli.ask[F, Time].map(_.now += time) // what could go wrong*

    private[this] def fiberize[A](
        forked: Time,
        delegate: Fiber[F, E, A]): Fiber[TimeT[F, *], E, A] =
      new Fiber[TimeT[F, *], E, A] {

        val cancel =
          Kleisli.liftF(delegate.cancel)

        val join =
          Kleisli { outerTime =>
            delegate.join.map { back =>
              // if the forked time is less, then it means it completed first; if it's more, then we semantically block (by increasing "now")
              outerTime.now = outerTime.now.max(forked.now)
              back.mapK(Kleisli.liftK[F, Time])
            }
          }
      }
  }
}

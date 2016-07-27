/*
 * Copyright 2016 47 Degrees, LLC. <http://www.47deg.com>
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

package fetch

import cats.Monad
import cats.std.FutureInstances
import scala.concurrent.{Promise, Future, ExecutionContext}

object implicits extends FutureInstances {
  implicit def fetchFutureFetchMonadError(
      implicit ec: ExecutionContext,
      M: Monad[Future]
  ): FetchMonadError[Future] = new FetchMonadError[Future] {
    override def runSync[A](q: Sync[A]): Future[A] = M.pureEval(q.action)
    override def runAsync[A](q: Async[A]): Future[A] = {
      val p = Promise[A]()

      ec.execute(new Runnable {
        def run() = q.action(p.trySuccess _, p.tryFailure _)
      })

      p.future
    }
    def pure[A](x: A): Future[A] = Future.successful(x)
    def handleErrorWith[A](fa: Future[A])(f: FetchError => Future[A]): Future[A] =
      fa.recoverWith({ case t: FetchError => f(t) })
    def raiseError[A](e: FetchError): Future[A]                    = Future.failed(e)
    def flatMap[A, B](fa: Future[A])(f: A => Future[B]): Future[B] = fa.flatMap(f)
  }
}

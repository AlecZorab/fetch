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

package fetch.monixTask

import fetch._

import cats.{Eval, Now, Later, Always, Traverse, Applicative}

import monix.eval.Task
import monix.execution.{Scheduler, Cancelable}

import scala.concurrent.duration._

object implicits {
  def evalToTask[A](e: Eval[A]): Task[A] = e match {
    case Now(x)       => Task.now(x)
    case l: Later[A]  => Task.evalOnce({ l.value })
    case a: Always[A] => Task.evalAlways({ a.value })
    case other        => Task.evalOnce({ other.value })
  }

  implicit val fetchTaskFetchMonadError: FetchMonadError[Task] = new FetchMonadError[Task] {
    override def map[A, B](fa: Task[A])(f: A => B): Task[B] =
      fa.map(f)

    override def product[A, B](fa: Task[A], fb: Task[B]): Task[(A, B)] =
      Task.zip2(Task.fork(fa), Task.fork(fb))

    override def pureEval[A](e: Eval[A]): Task[A] = evalToTask(e)

    def pure[A](x: A): Task[A] =
      Task.now(x)

    def handleErrorWith[A](fa: Task[A])(f: FetchError => Task[A]): Task[A] =
      fa.onErrorHandleWith({
        case e: FetchError => f(e)
      })

    def raiseError[A](e: FetchError): Task[A] =
      Task.raiseError(e)

    def flatMap[A, B](fa: Task[A])(f: A => Task[B]): Task[B] =
      fa.flatMap(f)

    override def runSync[A](q: Sync[A]): Task[A] = evalToTask(q.action)
    override def runAsync[A](q: Async[A]): Task[A] = {
      val task: Task[A] = Task.create(
          (scheduler, callback) => {

        scheduler.execute(new Runnable {
          def run() = q.action(callback.onSuccess, callback.onError)
        })

        Cancelable.empty
      })

      q.timeout match {
        case finite: FiniteDuration => task.timeout(finite)
        case _                      => task
      }
    }

    override def ap[A, B](qf: Task[A => B])(qx: Task[A]): Task[B] =
      Task.mapBoth(qf, qx)(_ (_))
  }
}

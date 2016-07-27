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

package fetch.unsafe

import fetch._

import cats.{Id, Eval}
import cats.data.Xor

import scala.concurrent._
import scala.concurrent.duration._

object implicits {
  implicit val evalFetchMonadError: FetchMonadError[Eval] = new FetchMonadError[Eval] {
    override def runSync[A](q: Sync[A]): Eval[A] = q.action
    override def runAsync[A](q: Async[A]): Eval[A] = {
      val action  = q.action
      val timeout = q.timeout

      Eval.later {
        val latch = new java.util.concurrent.CountDownLatch(1)
        @volatile var result: Xor[Throwable, A] = null
        new Thread(
            new Runnable {
          def run() = {
            action(a => {
              result = Xor.Right(a);
              latch.countDown
            }, err => {
              result = Xor.Left(err);
              latch.countDown
            })
          }
        }).start()
        latch.await
        result match {
          case Xor.Left(err) => throw err
          case Xor.Right(v)  => v
        }
      }
    }
    def pure[A](x: A): Eval[A] = Eval.now(x)

    def handleErrorWith[A](fa: Eval[A])(f: FetchError => Eval[A]): Eval[A] =
      Eval.later({
        try {
          fa.value
        } catch {
          case ex: FetchError => f(ex).value
          case th: Throwable  => f(FetchException(th)).value
        }
      })

    def raiseError[A](e: FetchError): Eval[A] =
      Eval.later({
        throw e
      })

    def flatMap[A, B](fa: Eval[A])(f: A => Eval[B]): Eval[B] =
      fa.flatMap(f)
  }

  implicit val idFetchMonadError: FetchMonadError[Id] = new FetchMonadError[Id] {
    override def runSync[A](q: Sync[A]): Id[A] = q.action.value
    override def runAsync[A](q: Async[A]): Id[A] = {
      val action = q.action
      val latch  = new java.util.concurrent.CountDownLatch(1)

      @volatile var result: Xor[Throwable, A] = null

      new Thread(
          new Runnable {
        def run() = {
          action(a => {
            result = Xor.Right(a);
            latch.countDown
          }, err => {
            result = Xor.Left(err);
            latch.countDown
          })
        }
      }).start()
      latch.await
      result match {
        case Xor.Left(err) => throw err
        case Xor.Right(v)  => v
      }
    }

    def pure[A](x: A): Id[A] = x

    def handleErrorWith[A](fa: Id[A])(f: FetchError => Id[A]): Id[A] =
      try {
        fa
      } catch {
        case ex: FetchError => f(ex)
      }
    def raiseError[A](e: FetchError): Id[A] =
      e match {
        case FetchException(ex) => {
            e.initCause(ex)
            throw e
          }
        case other => throw other
      }
    def flatMap[A, B](fa: Id[A])(f: A => Id[B]): Id[B] = f(fa)
  }
}

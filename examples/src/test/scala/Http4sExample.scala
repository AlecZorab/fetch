import cats.data.NonEmptyList
import cats.instances.list._
import cats.syntax.traverse._
import io.circe._
import io.circe.generic.semiauto._
import org.http4s.circe._
import org.http4s.client.blaze._
import org.scalatest.{AsyncWordSpec, Matchers}
import scalaz.concurrent.Task

import scala.concurrent.{ExecutionContext, Future}

import fetch._
import fetch.implicits._

class HttpExample extends AsyncWordSpec with Matchers {
  implicit override def executionContext = ExecutionContext.Implicits.global

  // in this example we are fetching users and their posts via http using http4s
  // the demo api is https://jsonplaceholder.typicode.com/

  // the User and Post classes

  case class UserId(id: Int)
  case class PostId(id: Int)

  case class User(id: UserId, name: String, username: String, email: String)
  case class Post(id: PostId, userId: UserId, title: String, body: String)

  // some circe decoders

  implicit val userIdDecoder: Decoder[UserId] = Decoder[Int].map(UserId.apply)
  implicit val postIdDecoder: Decoder[PostId] = Decoder[Int].map(PostId.apply)
  implicit val userDecoder: Decoder[User]     = deriveDecoder
  implicit val postDecoder: Decoder[Post]     = deriveDecoder

  // http4s client which is used by the datasources

  val client = PooledHttp1Client()

  // a DataSource that can fetch Users with their UserId.

  implicit val userDS = new DataSource[UserId, User] {
    override def name = "UserH4s"
    override def fetchOne(id: UserId): Query[Option[User]] =
      Query.async { (ok, fail) =>
        fetchById(id).unsafePerformAsync(_.fold(fail, ok))
      }
    override def fetchMany(ids: NonEmptyList[UserId]): Query[Map[UserId, User]] =
      Query.async { (ok, fail) =>
        fetchByIds(ids)
          .map(users => users.map(user => user.id -> user).toMap)
          .unsafePerformAsync(_.fold(fail, ok))
      }

    // fetchById and fetchByIds would probably be defined in some other module

    def fetchById(id: UserId): Task[Option[User]] = {
      val url = s"https://jsonplaceholder.typicode.com/users?id=${id.id}"
      client.expect(url)(jsonOf[List[User]]).map(_.headOption)
    }

    def fetchByIds(ids: NonEmptyList[UserId]): Task[List[User]] = {
      val filterIds = ids.map("id=" + _.id).toList.mkString("&")
      val url       = s"https://jsonplaceholder.typicode.com/users?$filterIds"
      client.expect(url)(jsonOf[List[User]])
    }
  }

  // a datasource that can fetch all the Posts using a UserId

  implicit val postsForUserDS = new DataSource[UserId, List[Post]] {
    override def name = "PostH4s"
    override def fetchOne(id: UserId): Query[Option[List[Post]]] =
      Query.async { (ok, fail) =>
        fetchById(id).map(Option.apply).unsafePerformAsync(_.fold(fail, ok))
      }
    override def fetchMany(ids: NonEmptyList[UserId]): Query[Map[UserId, List[Post]]] =
      Query.async { (ok, fail) =>
        fetchByIds(ids).unsafePerformAsync(_.fold(fail, ok))
      }

    def fetchById(id: UserId): Task[List[Post]] = {
      val url = s"https://jsonplaceholder.typicode.com/posts?userId=${id.id}"
      client.expect(url)(jsonOf[List[Post]])
    }

    def fetchByIds(ids: NonEmptyList[UserId]): Task[Map[UserId, List[Post]]] = {
      val filterIds = ids.map("userId=" + _.id).toList.mkString("&")
      val url       = s"https://jsonplaceholder.typicode.com/posts?$filterIds"
      client.expect(url)(jsonOf[List[Post]]).map(_.groupBy(_.userId).toMap)
    }
  }

  // some helper methods to create Fetches

  def user(id: UserId): Fetch[User]               = Fetch(id)
  def postsForUser(id: UserId): Fetch[List[Post]] = Fetch(id)

  "We can fetch one user" in {
    val fetch: Fetch[User]            = user(UserId(1))
    val fut: Future[(FetchEnv, User)] = Fetch.runFetch[Future](fetch)
    fut.map {
      case (env, user) =>
        println(user)
        env.rounds.size shouldEqual 1
    }
  }

  "We can fetch multiple users in parallel" in {
    val fetch: Fetch[List[User]] = List(1, 2, 3).traverse(i => user(UserId(i)))
    val fut                      = Fetch.runFetch[Future](fetch)
    fut.map {
      case (env, users) =>
        users.foreach(println)
        env.rounds.size shouldEqual 1
    }
  }

  "We can fetch multiple users with their posts" in {
    val fetch: Fetch[List[(User, List[Post])]] =
      for {
        users <- List(UserId(1), UserId(2)).traverse(user)
        usersWithPosts <- users.traverseU { user =>
          postsForUser(user.id).map(posts => (user, posts))
        }
      } yield usersWithPosts
    val fut = Fetch.runFetch[Future](fetch)
    fut.map {
      case (env, userPosts) =>
        userPosts.map {
          case (user, posts) =>
            s"${user.username} has ${posts.size} posts"
        }.foreach(println)
        env.rounds.size shouldEqual 2
    }
  }

}

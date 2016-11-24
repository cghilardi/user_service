import com.twitter.finagle.Service
import com.twitter.finagle.http
import com.twitter.finagle.Http
import com.twitter.finagle.Redis
import com.twitter.util.{Future, Await}
import com.twitter.io.Buf
import com.twitter.io.Bufs
import play.api.libs.json.Json

case class UsersToLoad(ids: Seq[Int])
case class User(name: String, age: String)

object UserService {
  val redisClient = Redis.client.withAdmissionControl.maxPendingRequests(1000).newRichClient("localhost:6379")
  
  def main(args: Array[String]): Unit = {
    val server = Http.server.serve(":3000", service)
    Await.ready(server)
  }

  val service = new Service[http.Request, http.Response] {
    def apply(req: http.Request): Future[http.Response] = {
      // json deserialization
      val json = Json.parse(req.getParam("ids"))
      implicit val modelFormat = Json.format[UsersToLoad]
      val usersToLoad = Json.fromJson[UsersToLoad](json).get

      // redis calls
      val f = redisClient.mGet(usersToLoad.ids.map(id => Buf.Utf8("name:").concat(Buf.Utf8(id.toString))))
      val f2 = redisClient.mGet(usersToLoad.ids.map(id => Buf.Utf8("age:").concat(Buf.Utf8(id.toString))))

      // await redis calls then build response
      Future.collect(Seq(f, f2)) flatMap buildResponse
    }
  }

  def buildResponse(l: Seq[Seq[Option[Buf]]]): Future[http.Response] = {
    // build user list
    val users:Seq[User] = (l.head, l.tail.head).zipped.map((name, age) => {
      new User(Bufs.asUtf8String(name.getOrElse(Buf.Utf8(""))), Bufs.asUtf8String(age.getOrElse(Buf.Utf8(""))))
    })
    // serialize users in json
    implicit val modelFormat = Json.format[User]
    val res = http.Response(http.Versions.HTTP_1_1, http.Status.Ok)
    res.setContentTypeJson()
    res.setContentString(Json.toJson(users).toString())
    Future.value(res)
  }
}

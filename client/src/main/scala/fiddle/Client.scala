package fiddle
import scala.scalajs.js
import js.Dynamic.{global, literal => lit}
import org.scalajs.dom
import collection.mutable
import scalatags.{Modifier, Tags2}
import scala.concurrent.{Promise, Future}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.async.Async.{async, await}
import scalatags.all._

object Page{

  def body = Seq(
    pre(id:="editor"),
    pre(id:="logspam"),
    div(id:="sandbox")(
      canvas(id:="canvas")
    )
  )
  val starting =
    """
      |import org.scalajs.dom
      |
      |case class Pt(x: Double, y: Double)
      |object ScalaJSExample{
      |  println("Hello!!")
      |  val sandbox = dom.document
      |    .getElementById("canvas")
      |    .asInstanceOf[dom.HTMLCanvasElement]
      |
      |  val renderer = sandbox.getContext("2d")
      |    .asInstanceOf[dom.CanvasRenderingContext2D]
      |
      |  val corners = Seq(
      |    Pt(sandbox.width/2, 0),
      |    Pt(0, sandbox.height),
      |    Pt(sandbox.width, sandbox.height)
      |  )
      |  var p = corners(0)
      |  val (w, h) = (sandbox.height.toDouble, sandbox.height.toDouble)
      |  def main(args: Array[String]): Unit = {
      |    dom.setInterval(() => for(_ <- 0 until 10){
      |      val c = corners(util.Random.nextInt(3))
      |      p = Pt((p.x + c.x) / 2, (p.y + c.y) / 2)
      |      val m = (p.y / h)
      |      val r = 255 - (p.x / w * m * 255).toInt
      |      val g = 255 - ((w-p.x) / w * m * 255).toInt
      |      val b = 255 - ((h - p.y) / h * 255).toInt
      |      renderer.fillStyle = s"rgb($r, $g, $b)"
      |      renderer.fillRect(p.x, p.y, 1, 1)
      |    }, 10)
      |  }
      |}
    """.stripMargin
  val red = span(color:="#ff8888")
  val blue = span(color:="#8888ff")
  val green = span(color:="#88ff88")
}
import Page.{red, green, blue}
object Client{

  def sandbox = js.Dynamic.global.sandbox.asInstanceOf[dom.HTMLDivElement]
  def canvas = js.Dynamic.global.canvas.asInstanceOf[dom.HTMLCanvasElement]
  def logspam = js.Dynamic.global.logspam.asInstanceOf[dom.HTMLPreElement]
  dom.document.body.innerHTML = Page.body.mkString
  lazy val editor: js.Dynamic = {
    val editor = global.ace.edit("editor")
    editor.setTheme("ace/theme/twilight")
    editor.getSession().setMode("ace/mode/scala")
    editor.renderer.setShowGutter(false)

    editor.commands.addCommand(lit(
      name = "compile",
      bindKey = lit(
        win = "Ctrl-Enter",
        mac = "Command-Enter",
        sender = "editor|cli"
      ),
      exec = (compile _): js.Function0[_]
    ))
    editor.commands.addCommand(lit(
      name = "save",
      bindKey = lit(
        win = "Ctrl-S",
        mac = "Command-S",
        sender = "editor|cli"
      ),
      exec = (save _): js.Function0[_]
    ))
    editor.commands.addCommand(lit(
      name = "complete",
      bindKey = lit(
        win = "Ctrl-`",
        mac = "Command-`",
        sender = "editor|cli"
      ),
      exec = (complete _): js.Function0[_]
    ))
    editor.getSession().setTabSize(2)
    editor
  }

  def clear() = {
    canvas.height = sandbox.clientHeight
    canvas.width = sandbox.clientWidth
    for(i <- 0 until 1000){
      dom.clearInterval(i)
      dom.clearTimeout(i)
    }
    sandbox.innerHTML = sandbox.innerHTML
  }
  val saved = mutable.Map.empty[String, String]
  var logged = div(
    div("- ", blue("Cmd/Ctrl-Enter"), " to compile & execute, ", blue("Cmd/Ctrl-S"), " to save your code to a Gist"),
    div("- Draw pictures on the right pane and see println()s in the browser console"),
    div("- Navigate to ", blue("http://", dom.document.location.host.toString, "/gist/<gist-id>")," to load it from one")
  )

  def log(s: Modifier*): Unit = {
    logged = div(s:_*).transform(logged)
    logspam.innerHTML = logged.toString()
    logspam.scrollTop = logspam.scrollHeight - logspam.clientHeight
  }

  def main(args: Array[String]): Unit = {

    clear()
    if (dom.document.location.pathname == "/"){
      editor.getSession().setValue(Page.starting)
      compile()
    } else load(dom.document.location.pathname.drop(6))
  }
  def load(gistId: String): Unit = async {

    val gistUrl = "https://gist.github.com/" + gistId

    log("Loading code from gist ", a(href:=gistUrl)(gistUrl), "...")

    val res = await(Ajax.get("https://api.github.com/gists/" + gistId))
    val result = js.JSON.parse(res.responseText)

    val allFiles = result.files.asInstanceOf[js.Dictionary[js.Dynamic]]

    val mainFile = allFiles("Main.scala")

    val firstFile = allFiles(js.Object.keys(allFiles)(0).toString)

    val content = (if (!mainFile.isInstanceOf[js.Undefined]) mainFile else firstFile).selectDynamic("content").toString

    editor.getSession().setValue(content)
    saved(content) = gistId
    compile()
  }.onFailure{ case e =>
    log(red(s"Loading failed with $e, Falling back to default example."))
    editor.getSession().setValue(Page.starting)
    compile()
  }
  def compile(): Future[Unit] = async {
    val code = editor.getSession().getValue().asInstanceOf[String]
    log("Compiling...")
    val res = await(Ajax.post("/compile", code))

    val result = js.JSON.parse(res.responseText)
    log(result.logspam.toString)
    if(result.success.asInstanceOf[js.Boolean]){
      clear()
      js.eval(""+result.code)
      log(green("Success"))
    }else{
      log(red("Failure"))
    }
  }

  def complete() = async {

    val Seq(row, col) = Seq(
      editor.getCursorPosition().row,
      editor.getCursorPosition().column
    ).map(_.asInstanceOf[js.Number].toInt)

    val code = editor.getSession().getValue().asInstanceOf[String]
    val intOffset =code.split("\n").take(row).map(_.length + 1).sum + col
    log("Completing...")
    val xhr = await(Ajax.post("/complete/" + intOffset, code))
    val result = js.JSON.parse(xhr.responseText)
    dom.console.log(result)

  }
  def save(): Unit = async{
    await(compile())
    val code = editor.getSession().getValue().asInstanceOf[String]
    val resultId = saved.lift(code) match{
      case Some(id) => id
      case None =>
        val res = await(Ajax.post("https://api.github.com/gists",
          data = js.JSON.stringify(
            lit(
              description = "Scala.jsFiddle gist",
              public = true,
              files = js.Dictionary(
                ("Main.scala": js.String) -> lit(
                  content = code
                )
              )
            )
          )
        ))
        val result = js.JSON.parse(res.responseText)
        saved(code) = result.id.toString
        result.id
    }

    val fiddleUrl = "http://" + dom.document.location.host + "/gist/" + resultId
    log(green("Saved as ", a(href:=fiddleUrl)(fiddleUrl)))
    dom.history.pushState(null, null, "/gist/" + resultId)
    val gistUrl = "https://gist.github.com/" + resultId
    log(green("Or view on github at ", a(href:=gistUrl)(gistUrl)))
  }
}

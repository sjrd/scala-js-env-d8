package be.doeraene.scalajsd8env

import scala.collection.immutable
import scala.collection.mutable

import scala.concurrent.{Future, Promise}
import scala.util.Try

import java.io.{ Console => _, _ }
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import org.scalajs.io._
import org.scalajs.io.JSUtils.escapeJS
import org.scalajs.logging._

import org.scalajs.jsenv._

import scala.concurrent.TimeoutException
import scala.concurrent.duration._

class D8JSEnv(config: D8JSEnv.Config) extends ExternalJSEnv with ComJSEnv {
  import D8JSEnv._

  def this() = this(D8JSEnv.Config())

  protected def vmName: String = "d8"

  protected def executable: String = config.executable

  override protected def args: immutable.Seq[String] = config.args

  override protected def env: Map[String, String] = config.env

  override def jsRunner(files: Seq[VirtualJSFile]): JSRunner =
    new D8Runner(files)

  override def asyncRunner(files: Seq[VirtualJSFile]): AsyncJSRunner =
    new AsyncD8Runner(files)

  override def comRunner(files: Seq[VirtualJSFile]): ComJSRunner =
    new ComD8Runner(files)

  protected trait AbstractD8Runner extends AbstractExtRunner {
    protected[this] val libCache = new VirtualFileMaterializer(true)

    override protected def getVMArgs(): Seq[String] =
      args ++ getJSFiles().map(f => libCache.materialize(f).getAbsolutePath)
  }

  protected class D8Runner(files: Seq[VirtualJSFile])
      extends ExtRunner(files) with AbstractD8Runner

  protected class AsyncD8Runner(files: Seq[VirtualJSFile])
      extends AsyncExtRunner(files) with AbstractD8Runner

  // This works around some obscure thing with `abstract override def stop()`
  protected class ComD8Runner(files: Seq[VirtualJSFile])
      extends AbstractComD8Runner(files) with ComJSRunner {
    override def stop(): Unit = super.stop()
  }

  protected abstract class AbstractComD8Runner(files: Seq[VirtualJSFile])
      extends AbstractExtRunner(files) with AbstractD8Runner {

    private[this] var vmInst: Process = null
    private[this] val promise = Promise[Unit]

    // In these queues, a None element is a sentinel for a closed channel
    private[this] val jvm2js = new LinkedBlockingQueue[Option[String]]()
    private[this] val js2jvm = new LinkedBlockingQueue[Option[String]]()

    private[this] val jvm2jsThread = new Thread {
      override def run(): Unit = {
        val writer = new OutputStreamWriter(vmInst.getOutputStream(), UTF_8)
        try {
          var msg: Option[String] = jvm2js.take()
          while (msg.isDefined) {
            val encoded = base16Encode(msg.get)
            writer.write(encoded + "\n")
            writer.flush()
            msg = jvm2js.take()
          }
        } finally {
          writer.close()
        }
      }
    }

    private[this] val js2jvmThread = new Thread {
      override def run(): Unit = {
        val pipeResult = Try {
          val reader = new BufferedReader(new InputStreamReader(
              vmInst.getInputStream(), UTF_8))
          try {
            var line: String = reader.readLine()
            while (line != null) {
              if (line.startsWith(D8ComMessagePrefix)) {
                val encoded = line.stripPrefix(D8ComMessagePrefix)
                if (encoded == "") {
                  AbstractComD8Runner.this.close()
                  line = null
                } else {
                  js2jvm.offer(Some(base16Decode(encoded)))
                  line = reader.readLine()
                }
              } else {
                console.log(line)
                line = reader.readLine()
              }
            }
          } finally {
            js2jvm.offer(None)
            reader.close()
          }
        }

        val vmComplete = Try(waitForVM(vmInst))

        // Chain Try's the other way: We want VM failure first, then IO failure
        promise.complete(pipeResult.orElse(vmComplete))
      }
    }

    def future: Future[Unit] = promise.future

    def start(logger: Logger, console: JSConsole): Future[Unit] = {
      setupLoggerAndConsole(logger, console)
      startExternalJSEnv()
      future
    }

    /** Core functionality of [[start]].
     *
     *  Same as [[start]] but without a call to [[setupLoggerAndConsole]] and
     *  not returning [[future]].
     *  Useful to be called in overrides of [[start]].
     */
    protected def startExternalJSEnv(): Unit = {
      require(vmInst == null, "start() may only be called once")
      vmInst = startVM()
      jvm2jsThread.start()
      js2jvmThread.start()
    }

    def close(): Unit = {
      jvm2js.offer(None)
    }

    def stop(): Unit = {
      require(vmInst != null, "start() must have been called")
      close()
      js2jvm.offer(None)
      vmInst.destroy()
    }

    override protected def getVMArgs(): Seq[String] =
      args :+ libCache.materialize(launcher()).getAbsolutePath

    private def launcher(): VirtualJSFile = {
      val loadJSFiles = (for {
        f <- getJSFiles()
      } yield {
        val fileName = libCache.materialize(f).getAbsolutePath
        s"load('${escapeJS(fileName)}');"
      }).mkString("\n")

      val workerScript =
        s"""
          |var scalajsCom = {
          |  init: function(onReceive) {
          |    onmessage = function(line) {
          |      var readByteAt = function(i) {
          |        return ((line.charCodeAt(i) - 65) << 4) |
          |          (line.charCodeAt(i + 1) - 65);
          |      };
          |      var decoded = '';
          |      var i = 0;
          |      while (i != line.length) {
          |        if (line[i] != 'u') {
          |          decoded += String.fromCharCode(readByteAt(i));
          |          i += 2;
          |        } else {
          |          decoded += String.fromCharCode(
          |            (readByteAt(i + 1) << 8) | readByteAt(i + 3));
          |          i += 5;
          |        }
          |      }
          |      onReceive(decoded);
          |    };
          |  },
          |  send: function(msg) {
          |    var makeByte = function(c) {
          |      return String.fromCharCode(
          |        ((c & 0xf0) >>> 4) + 65, (c & 0x0f) + 65);
          |    };
          |    var encoded = '';
          |    var i = 0;
          |    while (i != msg.length) {
          |      var c = msg.charCodeAt(i);
          |      if (c < 0x100)
          |        encoded += makeByte(c);
          |      else
          |        encoded += 'u' + makeByte(c >>> 8) + makeByte(c);
          |      i++;
          |    }
          |    print("$D8ComMessagePrefix" + encoded);
          |  },
          |  close: function() {
          |    // Ask the JVM side to close me from without
          |    print("$D8ComMessagePrefix");
          |  }
          |};
          |$loadJSFiles
        """.stripMargin

      new MemVirtualJSFile("launcher.js").withContent(
          s"""
            |var worker = new Worker("${escapeJS(workerScript)}");
            |var line;
            |while (line = readline()) {
            |  worker.postMessage(line);
            |}
            |worker.terminate();
            |quit();
          """.stripMargin)
    }

    private def base16Encode(msg: String): String = {
      val result = new java.lang.StringBuilder(msg.length * 2)

      def appendByte(b: Int): Unit = {
        result.append(('A' + ((b & 0xf0) >>> 4)).toChar)
        result.append(('A' + (b & 0x0f)).toChar)
      }

      for (c <- msg) {
        if (c < 0x100) {
          appendByte(c.toInt)
        } else {
          result.append('u')
          appendByte(c >>> 8)
          appendByte(c)
        }
      }
      result.toString()
    }

    private def base16Decode(msg: String): String = {
      val len = msg.length
      val result = new java.lang.StringBuilder(len / 2)

      def readByteAt(i: Int): Int =
        ((msg.charAt(i) - 'A') << 4) | (msg.charAt(i + 1) - 'A')

      var i = 0
      while (i != len) {
        if (msg.charAt(i) != 'u') {
          result.append(readByteAt(i).toChar)
          i += 2
        } else {
          result.append(((readByteAt(i + 1) << 8) | readByteAt(i + 3)).toChar)
          i += 5
        }
      }

      result.toString()
    }

    def send(msg: String): Unit = {
      jvm2js.offer(Some(msg))
    }

    def receive(timeout: Duration): String = {
      val result = {
        if (timeout.isFinite()) {
          val result = js2jvm.poll(timeout.toMillis, TimeUnit.MILLISECONDS)
          if (result == null)
            throw new TimeoutException("Timeout expired")
          result
        } else {
          js2jvm.take()
        }
      }
      result.getOrElse {
        js2jvm.offer(None) // put it back in, in case receive() is called again
        throw new ComJSEnv.ComClosedException
      }
    }

    override protected def finalize(): Unit = close()
  }

}

object D8JSEnv {
  private final val D8ComMessagePrefix = "@__ScalaJSD8JSEnvComMessage__@"

  private val DefaultD8Executable =
    System.getProperty("be.doeraene.scalajsd8env.d8executable", "d8")

  final class Config private (
      val executable: String,
      val args: List[String],
      val env: Map[String, String]
  ) {
    private def this() = {
      this(
          executable = DefaultD8Executable,
          args = Nil,
          env = Map.empty
      )
    }

    def withExecutable(executable: String): Config =
      copy(executable = executable)

    def withArgs(args: List[String]): Config =
      copy(args = args)

    def withEnv(env: Map[String, String]): Config =
      copy(env = env)

    private def copy(
        executable: String = executable,
        args: List[String] = args,
        env: Map[String, String] = env
    ): Config = {
      new Config(executable, args, env)
    }
  }

  object Config {
    /** Returns a default configuration for a [[D8JSEnv]].
     *
     *  The defaults are:
     *
     *  - `executable`: `"node"`
     *  - `args`: `Nil`
     *  - `env`: `Map.empty`
     */
    def apply(): Config = new Config()
  }
}

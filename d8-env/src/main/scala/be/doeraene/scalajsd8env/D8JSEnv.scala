package be.doeraene.scalajsd8env

import scala.collection.immutable
import scala.collection.mutable

import scala.concurrent.{Future, Promise}
import scala.util.Try
import scala.util.control.NonFatal

import java.io._
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, StandardCopyOption}
import java.net.URI
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import org.scalajs.io._
import org.scalajs.io.JSUtils.escapeJS
import org.scalajs.logging._

import org.scalajs.jsenv._

import scala.concurrent.TimeoutException
import scala.concurrent.duration._

class D8JSEnv(config: D8JSEnv.Config) extends JSEnv {
  import D8JSEnv._

  def this() = this(D8JSEnv.Config())

  val name: String = "d8"

  def start(input: Input, runConfig: RunConfig): JSRun = {
    D8JSEnv.validator.validate(runConfig)
    try {
      val files = scriptFiles(input).map(materialize(_).getPath)
      val command = config.executable :: config.args ::: files
      val externalConfig = ExternalJSRun.Config()
        .withEnv(config.env)
        .withRunConfig(runConfig)
      ExternalJSRun.start(command, externalConfig)(_.close())
    } catch {
      case NonFatal(t) =>
        JSRun.failed(t)

      case t: NotImplementedError =>
        /* In Scala 2.10.x, NotImplementedError was considered fatal.
         * We need this case for the conformance tests to pass on 2.10.
         */
        JSRun.failed(t)
    }
  }

  def startWithCom(input: Input, runConfig: RunConfig,
      onMessage: String => Unit): JSComRun = {
    D8JSEnv.validator.validate(runConfig)
    try {
      new ComD8Run(input, runConfig, onMessage)
    } catch {
      case NonFatal(t) =>
        JSComRun.failed(t)

      case t: NotImplementedError =>
        /* In Scala 2.10.x, NotImplementedError was considered fatal.
         * We need this case for the conformance tests to pass on 2.10.
         * Non-fatal exceptions are already handled by ComRun.start().
         */
        JSComRun.failed(t)
    }
  }

  private class ComD8Run(input: Input, runConfig: RunConfig,
      onMessage: String => Unit)
      extends JSComRun {

    private val stdoutStreams = {
      if (runConfig.inheritOutput) {
        None
      } else {
        val is = new PipedInputStream()
        val osw = new OutputStreamWriter(new PipedOutputStream(is), UTF_8)
        Some((is, osw))
      }
    }

    private def sendToStdout(line: String): Unit = {
      stdoutStreams.fold {
        System.out.println(line)
      } { pair =>
        pair._2.write(line + "\n")
      }
    }

    // In this queue, a None element is a sentinel for a closed channel
    private[this] val jvm2js = new LinkedBlockingQueue[Option[String]]()

    private def writeJVMToJS(outputStream: OutputStream): Unit = {
      try {
        val writer = new OutputStreamWriter(outputStream, UTF_8)
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
      } catch {
        case _: EOFException =>
          // Happens when the VM crashes. Let ExternalJSEnv take care of the failure.
      }
    }

    private class JSToJVMThread(inputStream: InputStream) extends Thread {
      override def run(): Unit = {
        val pipeResult = Try {
          val reader =
            new BufferedReader(new InputStreamReader(inputStream, UTF_8))
          try {
            var line: String = reader.readLine()
            while (line != null) {
              if (line.startsWith(D8ComMessagePrefix)) {
                val encoded = line.stripPrefix(D8ComMessagePrefix)
                if (encoded == "") {
                  ComD8Run.this.close()
                  line = null
                } else {
                  onMessage(base16Decode(encoded))
                  line = reader.readLine()
                }
              } else {
                sendToStdout(line)
                line = reader.readLine()
              }
            }
          } finally {
            reader.close()
          }
        }
      }
    }

    private val underlyingRun = {
      val launcher = materialize(makeLauncher()).getAbsolutePath
      val command = config.executable :: config.args ::: launcher :: Nil

      val underlyingRunConfig = runConfig
        .withInheritOut(false)
        .withOnOutputStream { (underlyingStdout, stderr) =>
          new JSToJVMThread(underlyingStdout.get).start()
          runConfig.onOutputStream.foreach(_(stdoutStreams.map(_._1), stderr))
        }

      val externalConfig = ExternalJSRun.Config()
        .withEnv(config.env)
        .withRunConfig(underlyingRunConfig)
      ExternalJSRun.start(command, externalConfig)(writeJVMToJS(_))
    }

    private def makeLauncher(): VirtualBinaryFile = {
      val loadJSFiles = (for {
        f <- scriptFiles(input)
      } yield {
        val fileName = materialize(f).getAbsolutePath
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

      MemVirtualBinaryFile.fromStringUTF8("launcher.js",
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

    def future: Future[Unit] = underlyingRun.future

    def send(msg: String): Unit =
      jvm2js.offer(Some(msg))

    def close(): Unit =
      jvm2js.offer(None)
  }

}

object D8JSEnv {
  private lazy val validator = ExternalJSRun.supports(RunConfig.Validator())

  private final val D8ComMessagePrefix = "@__ScalaJSD8JSEnvComMessage__@"

  private val DefaultD8Executable =
    System.getProperty("be.doeraene.scalajsd8env.d8executable", "d8")

  private def scriptFiles(input: Input): List[VirtualBinaryFile] = input match {
    case Input.ScriptsToLoad(scripts) => scripts
    case _                            => throw new UnsupportedInputException(input)
  }

  // tmpSuffixRE and tmpFile copied from HTMLRunnerBuilder.scala in Scala.js

  private val tmpSuffixRE = """[a-zA-Z0-9-_.]*$""".r

  private def tmpFile(path: String, in: InputStream): File = {
    try {
      /* - createTempFile requires a prefix of at least 3 chars
       * - we use a safe part of the path as suffix so the extension stays (some
       *   browsers need that) and there is a clue which file it came from.
       */
      val suffix = tmpSuffixRE.findFirstIn(path).orNull

      val f = File.createTempFile("tmp-", suffix)
      f.deleteOnExit()
      Files.copy(in, f.toPath(), StandardCopyOption.REPLACE_EXISTING)
      f
    } finally {
      in.close()
    }
  }

  private def materialize(file: VirtualBinaryFile): File = {
    file match {
      case file: FileVirtualFile => file.file
      case file                  => tmpFile(file.path, file.inputStream)
    }
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

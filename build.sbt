import com.amazonaws.services.s3.model.PutObjectResult
import com.softwaremill.Publish.Release.updateVersionInDocs
import com.typesafe.sbt.packager.docker.{Cmd, ExecCmd}
import sbt.Keys.credentials
import sbtrelease.ReleaseStateTransformations._
import scoverage.ScoverageKeys._

val v2_12 = "2.12.12"
val v2_13 = "2.13.3"

val buildSettings = commonSmlBuildSettings ++ ossPublishSettings ++ Seq(
  organization := "org.elasticmq",
  scalaVersion := v2_13,
  crossScalaVersions := Seq(v2_13, v2_12),
  libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "1.3.0",
  dependencyOverrides := akka25Overrides,
  parallelExecution := false,
  sonatypeProfileName := "org.elasticmq",
  // workaround for: https://github.com/sbt/sbt/issues/692
  fork in Test := true,
  releaseProcess := {
    val uploadAssembly: ReleaseStep = ReleaseStep(
      action = { st: State =>
        val extracted = Project.extract(st)
        val (st2, _) = extracted.runTask(assembly in server, st)
        val (st3, _) = extracted.runTask(s3Upload in server, st2)
        st3
      }
    )

    val uploadDocker: ReleaseStep = ReleaseStep(
      action = { st: State =>
        val extracted = Project.extract(st)
        val (st2, _) = extracted.runTask(publish in Docker in server, st)
        st2
      }
    )

    val uploadNativeDocker: ReleaseStep = ReleaseStep(
      action = { st: State =>
        val extracted = Project.extract(st)
        val (st2, _) = extracted.runTask(packageBin in GraalVMNativeImage in nativeServer, st)
        val (st3, _) = extracted.runTask(publish in Docker in nativeServer, st2)
        st3
      }
    )

    Seq(
      checkSnapshotDependencies,
      inquireVersions,
      // publishing locally so that the pgp password prompt is displayed early
      // in the process
      releaseStepCommand("publishLocalSigned"),
      runClean,
      runTest,
      setReleaseVersion,
      uploadDocker,
      uploadNativeDocker,
      uploadAssembly,
      updateVersionInDocs(organization.value),
      commitReleaseVersion,
      tagRelease,
      publishArtifacts,
      releaseStepCommand("sonatypeBundleRelease"),
      pushChanges,
      setNextVersion,
      commitNextVersion,
      pushChanges
    )
  }
)

val jodaTime = "joda-time" % "joda-time" % "2.10.8"
val jodaConvert = "org.joda" % "joda-convert" % "2.2.1"
val config = "com.typesafe" % "config" % "1.4.1"

val scalalogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2"
val logback = "ch.qos.logback" % "logback-classic" % "1.2.3"
val jclOverSlf4j = "org.slf4j" % "jcl-over-slf4j" % "1.7.30" // needed form amazon java sdk

val scalatest = "org.scalatest" %% "scalatest" % "3.2.2"
val awaitility = "org.awaitility" % "awaitility-scala" % "4.0.3"

val amazonJavaSdk = "com.amazonaws" % "aws-java-sdk" % "1.11.848" exclude ("commons-logging", "commons-logging")

val akkaVersion = "2.6.10"
val akkaHttpVersion = "10.2.1"
val akka2Actor = "com.typesafe.akka" %% "akka-actor" % akkaVersion
val akka2Slf4j = "com.typesafe.akka" %% "akka-slf4j" % akkaVersion
val akka2Streams = "com.typesafe.akka" %% "akka-stream" % akkaVersion
val akka2Testkit = "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test"
val akka2Http = "com.typesafe.akka" %% "akka-http" % akkaHttpVersion
val sprayJson = "io.spray" %% "spray-json" % "1.3.5"
val akka2HttpTestkit = "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % "test"

val scalaAsync = "org.scala-lang.modules" %% "scala-async" % "0.10.0"

val common = Seq(scalalogging)

val akka25Overrides =
  Seq( // override the 2.4.x transitive dependency from Akka HTTP
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion
  )

// see https://github.com/scala/scala-dist/pull/181/files
val s3Upload = TaskKey[PutObjectResult]("s3-upload", "Uploads files to an S3 bucket.")

lazy val root: Project = (project in file("."))
  .settings(buildSettings)
  .settings(name := "elasticmq-root")
  .aggregate(commonTest, core, rest, server, nativeServer)

lazy val commonTest: Project = (project in file("common-test"))
  .settings(buildSettings)
  .settings(name := "elasticmq-common-test")
  .settings(Seq(libraryDependencies ++= Seq(scalatest, awaitility, logback), publishArtifact := false))

lazy val core: Project = (project in file("core"))
  .settings(buildSettings)
  .settings(
    Seq(name := "elasticmq-core",
        libraryDependencies ++= Seq(jodaTime, jodaConvert, akka2Actor, akka2Testkit) ++ common,
        coverageMinimum := 94))
  .dependsOn(commonTest % "test")

lazy val rest: Project = (project in file("rest"))
  .settings(buildSettings)
  .settings(name := "elasticmq-rest")
  .aggregate(restSqs, restSqsTestingAmazonJavaSdk)

lazy val restSqs: Project = (project in file("rest/rest-sqs"))
  .settings(buildSettings)
  .settings(Seq(
    name := "elasticmq-rest-sqs",
    libraryDependencies ++= Seq(akka2Actor,
                                akka2Slf4j,
                                akka2Http,
                                akka2Streams,
                                sprayJson,
                                akka2Testkit,
                                akka2HttpTestkit,
                                scalaAsync) ++ common
  ))
  .dependsOn(core, commonTest % "test")

lazy val restSqsTestingAmazonJavaSdk: Project =
  (project in file("rest/rest-sqs-testing-amazon-java-sdk"))
    .settings(buildSettings)
    .settings(
      Seq(name := "elasticmq-rest-sqs-testing-amazon-java-sdk",
          libraryDependencies ++= Seq(amazonJavaSdk, jclOverSlf4j) ++ common,
          publishArtifact := false))
    .dependsOn(restSqs % "test->test")

lazy val server: Project = (project in file("server"))
  .enablePlugins(JavaServerAppPackaging, DockerPlugin)
  .settings(buildSettings)
  .settings(generateVersionFileSettings)
  .settings(Seq(
    name := "elasticmq-server",
    libraryDependencies ++= Seq(logback, config),
    mainClass in assembly := Some("org.elasticmq.server.Main"),
    coverageMinimum := 52,
    // s3 upload
    s3Upload := {
      import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials, DefaultAWSCredentialsProviderChain}
      import com.amazonaws.services.s3.AmazonS3ClientBuilder
      import com.amazonaws.services.s3.model.{CannedAccessControlList, PutObjectRequest}

      val bucketName = "softwaremill-public"
      val creds = Credentials.forHost(credentials.value, bucketName + ".s3.amazonaws.com")

      val awsCreds = creds match {
        case Some(cred) => new AWSStaticCredentialsProvider(new BasicAWSCredentials(cred.userName, cred.passwd))
        case None       => new DefaultAWSCredentialsProviderChain
      }

      val client = AmazonS3ClientBuilder.standard().withCredentials(awsCreds).withRegion("eu-west-1").build()

      val log = streams.value.log
      val v = version.value

      val source = (assemblyOutputPath in assembly).value
      val targetObjectName = s"elasticmq-server-$v.jar"

      log.info("Uploading " + source.getAbsolutePath + " as " + targetObjectName)

      client.putObject(new PutObjectRequest(bucketName, targetObjectName, source)
        .withCannedAcl(CannedAccessControlList.PublicRead))
    },
    /*
    Format:
    realm=Amazon S3
    host=softwaremill-public.s3.amazonaws.com
    user=[AWS key id]
    password=[AWS secret key]
     */
    credentials += Credentials(Path.userHome / ".s3_elasticmq_credentials"),
    // docker
    dockerExposedPorts := Seq(9324),
    dockerBaseImage := "openjdk:8u212-b04-jdk-stretch",
    packageName in Docker := "elasticmq",
    dockerUsername := Some("softwaremill"),
    dockerUpdateLatest := true,
    javaOptions in Universal ++= Seq("-Dconfig.file=/opt/elasticmq.conf"),
    mappings in Docker += (baseDirectory.value / "docker" / "elasticmq.conf") -> "/opt/elasticmq.conf",
    dockerCommands += Cmd("COPY",
                          "--from=stage0",
                          s"--chown=${(daemonUser in Docker).value}:root",
                          "/opt/elasticmq.conf",
                          "/opt")
  ))
  .dependsOn(core, restSqs, commonTest % "test")

val graalVmVersion = "20.2.0"

lazy val nativeServer: Project = (project in file("native-server"))
  .enablePlugins(GraalVMNativeImagePlugin, DockerPlugin)
  .settings(buildSettings)
  .settings(Seq(
    name := "elasticmq-native-server",
    libraryDependencies ++= Seq(
      "org.graalvm.nativeimage" % "svm" % graalVmVersion % "compile-internal"
    ),
    //configures sbt-native-packager to build app using dockerized graalvm
    graalVMNativeImageGraalVersion := Some(graalVmVersion + "-java11"),
    graalVMNativeImageOptions ++= Seq(
      "--static",
      "-H:IncludeResources=.*conf",
      "-H:IncludeResources=version",
      "-H:IncludeResources=.*\\.properties",
      "-H:IncludeResources='org/joda/time/tz/data/.*'",
      "-H:+ReportExceptionStackTraces",
      "-H:-ThrowUnsafeOffsetErrors",
      "-H:+PrintClassInitialization",
      "--enable-http",
      "--enable-https",
      "--enable-url-protocols=https,http",
      "--initialize-at-build-time",
      "--report-unsupported-elements-at-runtime",
      "--allow-incomplete-classpath",
      "--no-fallback",
      "--verbose"
    ),
    mainClass in Compile := Some("org.elasticmq.server.Main"),
    //configures sbt-native-packager to build docker image with generated executable
    dockerBaseImage := "alpine:3.11",
    mappings in Docker := Seq(
      (baseDirectory.value / ".." / "server" / "docker" / "elasticmq.conf") -> "/opt/docker/elasticmq.conf",
      ((target in GraalVMNativeImage).value / "elasticmq-native-server") -> "/opt/docker/bin/elasticmq-native-server"
    ),
    dockerEntrypoint := Seq("/sbin/tini", "--", "/opt/docker/bin/elasticmq-native-server", "-Dconfig.file=/opt/elasticmq.conf"),
    dockerUpdateLatest := true,
    dockerExposedPorts := Seq(9324),
    dockerCommands := {
      val commands = dockerCommands.value
      val index = commands.indexWhere {
        case Cmd("FROM", args@_*) =>
          args.head == "alpine:3.11" && args.last == "mainstage"
        case _ => false
      }
      val (front, back) = commands.splitAt(index + 1)
      val copyConfig = ExecCmd("COPY", "/opt/docker/elasticmq.conf", "/opt/elasticmq.conf")
      val tiniCommand = ExecCmd("RUN", "apk", "add", "--no-cache", "tini")
      front ++ Seq(tiniCommand, copyConfig) ++ back
    },
    packageName in Docker := "elasticmq-native",
    dockerUsername := Some("softwaremill"),
    dockerUpdateLatest := true,
  ))
  .dependsOn(server)

lazy val performanceTests: Project = (project in file("performance-tests"))
  .settings(buildSettings)
  .settings(
    Seq(
      name := "elasticmq-performance-tests",
      libraryDependencies ++= Seq(amazonJavaSdk, jclOverSlf4j, logback) ++ common,
      publishArtifact := false
    ))
  .dependsOn(core, restSqs, commonTest % "test")

val generateVersionFileSettings = Seq(
  resourceGenerators in Compile += Def.task {
    val targetFile = (resourceManaged in Compile).value / "version"
    IO.write(targetFile, version.value.toString)
    Seq(targetFile)
  }.taskValue
)

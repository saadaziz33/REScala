organization := "de.tuda.stg"

name := "rescala"

version := "0.0.0"

scalaVersion := "2.11.2"

scalaSource in Compile <<= baseDirectory {(base) => new File(base, "REScala/src")}

scalaSource in Test <<= baseDirectory {(base) => new File(base, "REScalaTests/test")}

scalacOptions ++= (
  "-deprecation" ::
  "-encoding" :: "UTF-8" ::
  "-unchecked" ::
  "-feature" ::
  "-target:jvm-1.6" ::
  "-language:implicitConversions" ::
  "-language:reflectiveCalls" ::
  "-Xlint" ::
  "-language:postfixOps" ::
  Nil)

libraryDependencies ++= (
  "org.mockito" % "mockito-all" % "1.9.5" % "test" ::
  "org.scalatest" %% "scalatest" % "2.2.0" % "test" ::
  "com.novocode" % "junit-interface" % "0.10" % "test" ::
  "org.scala-stm" %% "scala-stm" % "0.7" ::
  Nil)

libraryDependencies <+= scalaVersion("org.scala-lang" % "scala-compiler" % _)

parallelExecution in Test := false

name := "pelta"

version := "0.1.0"

scalaVersion := "2.13.12"

libraryDependencies ++= Seq(
  "org.chipsalliance" %% "chisel" % "6.0.0",
  "edu.berkeley.cs" %% "chiseltest" % "6.0.0" % "test"
)

scalacOptions ++= Seq(
  "-language:reflectiveCalls",
  "-deprecation",
  "-feature",
  "-Xcheckinit",
  "-Ymacro-annotations"
)

addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % "6.0.0" cross CrossVersion.full)

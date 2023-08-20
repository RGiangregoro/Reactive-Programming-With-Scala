course := "reactive"
assignment := "async"

Test / parallelExecution := false

scalaVersion := "3.3.0"

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-encoding", "UTF-8",
  "-unchecked"
)

libraryDependencies += "org.scalameta" %% "munit" % "0.7.26" % Test

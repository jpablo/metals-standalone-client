addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.10.4")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.0")

resolvers ++= Resolver.sonatypeOssRepos("public")
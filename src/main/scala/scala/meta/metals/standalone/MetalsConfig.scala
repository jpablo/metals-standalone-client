package scala.meta.metals.standalone

import java.util.Properties
import scala.util.Try
import java.util.logging.Logger

/** Configuration for Metals language server versions and settings.
  */
object MetalsConfig:
  private val logger = Logger.getLogger(MetalsConfig.getClass.getName)
  
  private lazy val properties: Properties = 
    val props = new Properties()
    Try {
      val stream = getClass.getClassLoader.getResourceAsStream("metals.properties")
      if stream != null then
        props.load(stream)
        stream.close()
      else
        logger.warning("metals.properties not found, using fallback values")
    }.recover { case e =>
      logger.warning(s"Failed to load metals.properties: ${e.getMessage}")
    }
    props
  
  /** The Metals version to use when fetching via Coursier */
  lazy val metalsVersion: String = 
    properties.getProperty("metals.version", "1.6.2")
  
  /** The full Metals artifact coordinates for Coursier */
  lazy val metalsArtifact: String = 
    s"org.scalameta:metals_2.13:$metalsVersion"
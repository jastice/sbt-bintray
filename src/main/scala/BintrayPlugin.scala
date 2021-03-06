package bintray

import bintry.Attr
import sbt.Classpaths.publishTask
import sbt.Def.{Initialize, setting, task, taskDyn}
import sbt.Keys._
import sbt.{AutoPlugin, Credentials, Global, Path, Resolver, Setting, Tags, Task, ThisBuild, _}

object BintrayPlugin extends AutoPlugin {
  import BintrayKeys._
  import InternalBintrayKeys._

  override def requires = sbt.plugins.JvmPlugin
  override def trigger = allRequirements
  
  override def globalSettings: Seq[Setting[_]] = globalPublishSettings
  override def buildSettings: Seq[Setting[_]] = buildPublishSettings
  override def projectSettings: Seq[Setting[_]] = bintraySettings

  object autoImport extends BintrayKeys {
  }

  lazy val Git = Tags.Tag("git")

  def bintraySettings: Seq[Setting[_]] =
    bintrayCommonSettings ++ bintrayPublishSettings ++ bintrayQuerySettings

  def bintrayCommonSettings: Seq[Setting[_]] = Seq(
    bintrayChangeCredentials := {
      Bintray.changeCredentials(bintrayCredentialsFile.value)
    },
    bintrayWhoami := {
      Bintray.whoami(bintrayLoadedCredentials.value, sLog.value)
    }
  )

  def bintrayQuerySettings: Seq[Setting[_]] = Seq(
    bintrayPackageVersions := packageVersionsTask.value
  )

  def globalPublishSettings: Seq[Setting[_]] = Seq(
    bintrayCredentials in Global := None,
    bintrayCredentialsFile in Global := Path.userHome / ".bintray" / ".credentials",
    concurrentRestrictions in Global += Tags.exclusive(Git)
  )

  def buildPublishSettings: Seq[Setting[_]] = Seq(
    bintrayOrganization in ThisBuild := None,
    bintrayVcsUrl in ThisBuild := vcsUrlTask.value,
    bintrayReleaseOnPublish in ThisBuild := true
  )

  def bintrayPublishSettings: Seq[Setting[_]] = bintrayCommonSettings ++ Seq(
    bintrayPackage := moduleName.value,
    bintrayRepo := Bintray.cachedRepo(bintrayEnsureCredentials.value,
      bintrayOrganization.value,
      bintrayRepository.value),
    // todo: don't force this to be sbt-plugin-releases
    bintrayRepository := {
      if (sbtPlugin.value) Bintray.defaultSbtPluginRepository
      else Bintray.defaultMavenRepository
    },
    publishMavenStyle := {
      if (sbtPlugin.value) false else publishMavenStyle.value
    },
    bintrayPackageLabels := Nil,
    description in bintray := description.value,
    // note: publishTo may not have dependencies. therefore, we can not rely well on inline overrides
    // for inline credentials resolution we recommend defining bintrayCredentials _before_ mixing in the defaults
    // perhaps we should try overriding something in the publishConfig setting -- https://github.com/sbt/sbt-pgp/blob/master/pgp-plugin/src/main/scala/com/typesafe/sbt/pgp/PgpSettings.scala#L124-L131
    publishTo in bintray := publishToBintray.value,
    resolvers in bintray := {
      Bintray.buildResolvers(
        bintrayLoadedCredentials.value,
        bintrayOrganization.value,
        bintrayRepository.value)
    },
    credentials in bintray := {
      // TODO use either file or resolved
      Credentials(bintrayCredentialsFile.value) :: Nil
    },
    bintrayPackageAttributes := {
      if (sbtPlugin.value) Map(AttrNames.sbtPlugin -> Seq(Attr.Boolean(sbtPlugin.value)))
      else Map.empty
    },
    bintrayVersionAttributes := {
      val scalaVersions = crossScalaVersions.value
      val sv = Map(AttrNames.scalas -> scalaVersions.map(Attr.Version))
      if (sbtPlugin.value) sv ++ Map(AttrNames.sbtVersion-> Seq(Attr.Version(sbtVersion.value)))
      else sv
    },
    bintrayOmitLicense := {
      if (sbtPlugin.value) sbtPlugin.value
      else false
    },
    bintrayEnsureLicenses := {
      Bintray.ensureLicenses(licenses.value, bintrayOmitLicense.value)
    },
    bintrayLoadedCredentials := {
      Bintray.loadedCredentials(bintrayCredentials.value, bintrayCredentialsFile.value, sLog.value)
    },
    bintrayTryEnsureCredentials := {
      bintrayLoadedCredentials.value
        .orElse(Bintray.ensuredCredentials(bintrayCredentialsFile.value))
    },
    bintrayEnsureCredentials := {
      bintrayTryEnsureCredentials.value.get
    },
    bintrayEnsureBintrayPackageExists := ensurePackageTask.value,
    bintrayUnpublish := {
      // introduce task dependencies
      bintrayEnsureBintrayPackageExists.value
      bintrayEnsureLicenses.value

      val repo = bintrayRepo.value
      repo.unpublish(bintrayPackage.value, version.value, sLog.value)
    },
    bintrayRemoteSign := {
      val repo = bintrayRepo.value
      repo.remoteSign(bintrayPackage.value, version.value, sLog.value)
    },
    bintraySyncMavenCentral := {
      val repo = bintrayRepo.value
      repo.syncMavenCentral(bintrayPackage.value, version.value, credentials.value, sLog.value)
    },
    bintrayRelease := {
      val _ = publishVersionAttributesTask.value
      val repo = bintrayRepo.value
      repo.release(bintrayPackage.value, version.value, sLog.value)
    }
  ) ++ Seq(
    resolvers ++= (resolvers in bintray).value,
    credentials ++= (credentials in bintray).value,
    publishTo := (publishTo in bintray).value,
    publish := dynamicallyPublish.value
  )

  private def vcsUrlTask: Initialize[Task[Option[String]]] =
    task {
      Bintray.resolveVcsUrl.recover { case _ => None }.get
    } tag(Git)

  // uses taskDyn because it can return one of two potential tasks
  // as its result, each with their own dependencies
  // see also: http://www.scala-sbt.org/0.13/docs/Tasks.html#Dynamic+Computations+with 
  private def dynamicallyPublish: Initialize[Task[Unit]] =
    taskDyn {
      (if (bintrayReleaseOnPublish.value) bintrayRelease else warnToRelease).dependsOn(publishTask(publishConfiguration, deliver))
    } dependsOn(bintrayEnsureBintrayPackageExists, bintrayEnsureLicenses)

  private def warnToRelease: Initialize[Task[Unit]] =
    task {
      val log = sLog.value
      log.warn("You must run bintrayRelease once all artifacts are staged.")
    }

  private def publishVersionAttributesTask: Initialize[Task[Unit]] =
    task {
      val repo = bintrayRepo.value
      repo.publishVersionAttributes(
        bintrayPackage.value,
        version.value,
        bintrayVersionAttributes.value)
    }

  private def ensurePackageTask: Initialize[Task[Unit]] =
    task {
      val vcs = bintrayVcsUrl.value.getOrElse {
        sys.error("""bintrayVcsUrl not defined. assign this with bintrayVcsUrl := Some("git@github.com:you/your-repo.git")""")
      }
      val repo = bintrayRepo.value
      repo.ensurePackage(bintrayPackage.value,
        bintrayPackageAttributes.value,
        (description in bintray).value,
        vcs,
        licenses.value,
        bintrayPackageLabels.value)
    }

  /** set a user-specific bintray endpoint for sbt's `publishTo` setting.*/
  private def publishToBintray: Initialize[Option[Resolver]] =
    setting {
      val creds = bintrayLoadedCredentials.value
      val btyOrg = bintrayOrganization.value
      val repoName = bintrayRepository.value

      Bintray.withRepo(creds, btyOrg, repoName) { repo =>
        repo.buildPublishResolver(bintrayPackage.value,
          version.value,
          publishMavenStyle.value,
          sbtPlugin.value,
          bintrayReleaseOnPublish.value)
      }
    }

  /** Lists versions of bintray packages corresponding to the current project */
  private def packageVersionsTask: Initialize[Task[Seq[String]]] =
    task {
      val creds = bintrayTryEnsureCredentials.value
      val btyOrg = bintrayOrganization.value
      val repoName = bintrayRepository.value

      Bintray.withRepo(creds, btyOrg, repoName) { repo =>
        repo.packageVersions(bintrayPackage.value, sLog.value)
      }.getOrElse(Nil)
    }
}

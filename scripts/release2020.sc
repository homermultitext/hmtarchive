// If you want to use sbt instead of almond.sh, comment out
// sections one and two.
//
/*
// Almond.sh set up:
// 1. Add maven repository where we can find our libraries
val myBT = coursierapi.MavenRepository.of("https://dl.bintray.com/neelsmith/maven")
interp.repositories() ++= Seq(myBT)

// 2. Ivy imports
import $ivy.`edu.holycross.shot::xmlutils:2.0.0`
import $ivy.`edu.holycross.shot::cex:6.4.0`
import $ivy.`edu.holycross.shot::ohco2:10.18.1`
import $ivy.`edu.holycross.shot.cite::xcite:4.2.0`
import $ivy.`edu.holycross.shot::scm:7.2.0`
import $ivy.`org.homermultitext::hmtcexbuilder:3.5.0`
import $ivy.`org.homermultitext::hmt-textmodel:5.2.2`
*/
//
// GENERIC SCALA from here on
//
// A Scala script to build a HMT project release.
import org.homermultitext.hmtcexbuilder._
import edu.holycross.shot.ohco2._
import edu.holycross.shot.cite._
import edu.holycross.shot.scm._
import edu.holycross.shot.cex._
import edu.holycross.shot.xmlutils._

import edu.holycross.shot.citevalidator._
import edu.holycross.shot.mid.validator._
import edu.holycross.shot.mid.orthography._


import edu.holycross.shot.greek._
import org.homermultitext.edmodel._


import java.io.PrintWriter
import scala.io._
import java.io.File

// Predefine the layout of archive's file directory:
// 1. src directories for texts broken out by MS and book
val scholiaXml = "archive/scholia"
val iliadXml = "archive/iliad"
val otherCex = "archive/other_cex"
// 2. target directory for intermeidate composite xml output
val scholiaComposites = "archive/scholia-xml-composites"
val iliadComposites = "archive/iliad-xml-composites"
// 3. src directory for dynamically downloaded authority lists:
val authListDir = "archive/authlists"
// 4. target directory for publishable CEX editions of texts
val cexEditions = "archive/editions"

val orthoMap : Map[CtsUrn, MidOrthography] = Map(
  CtsUrn("urn:cts:greekLit:tlg0012.tlg001:") -> LiteraryGreekString
  //CtsUrn("urn:cts:greekLit:tlg5026:") -> ScholiaOrthography
)

/** Write index of scholia to Iliadic text they comment on.
*
* @param xrefNodes Vector of citation nodes.
* @param editionsDir Directory where output should be written.
*/
def indexScholiaCommentary(xrefNodes: Vector[CitableNode], editionsDir: String) : Unit = {

  val xrefUrns = for (n <- xrefNodes) yield {
    val scholion = n.urn.collapsePassageTo(2)
    val iliadUrnText = TextReader.collectText(n.text).trim
    try {
      val iliadUrn = CtsUrn(iliadUrnText)
      (scholion, Some(iliadUrn))
    } catch {
      case t: Throwable => {
        println("Could not parse " + iliadUrnText)
        (scholion, None)
      }
    }
  }
  val verb = "urn:cite2:cite:verbs.v1:commentsOn"
  val versionId = "hmt"
  val index = xrefUrns.map { case (sch,iliadOpt) =>
    iliadOpt match {
      case None => ""
      case u: Some[CtsUrn] => {
        s"${sch.dropVersion.addVersion(versionId)}#${verb}#${u.get}"
      }
    }
  }

  val hdr = "#!relations\n"
  new PrintWriter(s"${cexEditions}/commentaryIndex.cex") { write(hdr + index.mkString("\n") + "\n");close }

}


/**  Compose editions of scholia.  This includes
* an archival XML edition in CEX format; and
* a pure diplomatic edition in CEX format.
*/
def scholia: Unit = {

  println("Creating composite XML editions of scholia...")
  ScholiaComposite.composite(scholiaXml, scholiaComposites)
  val catalog = s"${scholiaComposites}/ctscatalog.cex"
  val citation = s"${scholiaComposites}/citationconfig.cex"

  val repo = TextRepositorySource.fromFiles(catalog,citation,scholiaComposites)
  val scholiaNodes = repo.corpus.nodes.filterNot(_.urn.passageComponent.endsWith("ref"))

  val scholiaRepo = TextRepository( Corpus(scholiaNodes), repo.catalog)
  val corpus = scholiaRepo.corpus
  val scholiaCatalog = scholiaRepo.catalog
  val scholiaDocs = corpus.nodes.map(_.urn.dropPassage).distinct
  for (s <- scholiaDocs) {
    val siglum = s.work
    if (siglum != "msAextra") {
      println("Get subcorpus for " + s)
      val subcorpusAll = corpus ~~ s

      val cleanNodes = subcorpusAll.nodes.filterNot(n => TextReader.collectText(n.text).trim.isEmpty)
      println("Clean nodes: " + cleanNodes.size)
      val subcorpus = Corpus(cleanNodes)
      println("Got " + subcorpus.size + " scholia.")
      val diplSubcorpus = DiplomaticReader.edition(subcorpus,HmtDiplomaticEdition)

      val diplHeader: String = {
        val entries: Vector[CatalogEntry] = scholiaCatalog.entriesForUrn(s)
        val newCat = Catalog(entries)
        "\n\n#!ctscatalog\n" + newCat.cex("#") + "\n\n#!ctsdata\n"
      }

      new PrintWriter(s"${cexEditions}/${siglum}_diplomatic.cex") { write(diplHeader + diplSubcorpus.cex("#"));close }
    }
  }


  val refNodes = repo.corpus.nodes.filter(_.urn.passageComponent.endsWith("ref"))
  indexScholiaCommentary (refNodes, cexEditions)

}

/**  Compose editions of Iliad.  This includes
* an archival XML edition in CEX format;  and
* a pure diplomatic edition in CEX format.
*/
def iliad : Unit= {
  // revisit this for making multiple Iliads...
  val fileBase = "va_iliad_"
  println("Creating editions of Iliad...")

  // create temporary composite XML file from source
  // documents organized by book:
  IliadComposite.composite(iliadXml, iliadComposites)
  val catalog = s"${iliadComposites}/ctscatalog.cex"
  val citation = s"${iliadComposites}/citationconfig.cex"
  val repo = TextRepositorySource.fromFiles(catalog,citation,iliadComposites)
  // write CEX-formatted version of archival XML:
  //new PrintWriter(s"${iliadComposites}/va_iliad_xml.cex") { write(repo.cex("#"));close }

  val diplIliad =  DiplomaticReader.edition(repo.corpus, HmtDiplomaticEdition)
  val diplHeader = "\n\n#!ctscatalog\nurn#citationScheme#groupName#workTitle#versionLabel#exemplarLabel#online#lang\nurn:cts:greekLit:tlg0012.tlg001.msA:#book,line#Homeric epic#Iliad#HMT project diplomatic edition##true#grc\n\n#!ctsdata\n"


  val tidyDipl = diplIliad.cex("#").replaceAll("[\t ]+", " ")

  new PrintWriter(s"${cexEditions}/va_iliad_diplomatic.cex") { write(diplHeader +  tidyDipl);close }
}


/** Concatenate all CEX source into a single string.
*/
def catAll: String = {
  // The archive's root directory has the library definition
  // for a given release in the file "library.cex".
  //val libraryCex = DataCollector.compositeFiles("archive", "cex")

  // Five subdirectories of the archive root contain all archival
  // data in CEX format:
  val tbsCex = DataCollector.compositeFiles("archive/codices", "cex")
  val textCex = DataCollector.compositeFiles( "archive/editions", "cex")
  val otherCex = DataCollector.compositeFiles( "archive/other_cex", "cex")
  val imageCex = DataCollector.compositeFiles("archive/images", "cex")
  val annotationCex = DataCollector.compositeFiles("archive/annotations","cex")
  val dseCex = DataCollector.compositeFiles("archive/dse-catalog", "cex") +  DataCollector.compositeFiles("archive/dse-data", "cex")
  val indexCex = DataCollector.compositeFiles("archive/relations", "cex")

  val authlistsCex = DataCollector.compositeFiles("archive/authlists", "cex")

  // Concatenate into a single string:
  List(tbsCex, textCex, otherCex, imageCex, annotationCex, dseCex, indexCex, authlistsCex ).mkString("\n\n") + "\n"
}

/** Remove all temporary files created in process of composing
* a release.
*/
def tidy = {
  val scholiaCompositeFiles  = DataCollector.filesInDir(scholiaComposites, "xml")
  for (f <- scholiaCompositeFiles.toSeq) {
    f.delete()
  }


  val iliadCompositeFiles  = DataCollector.filesInDir(iliadComposites, "xml")
  for (f <- iliadCompositeFiles.toSeq) {
    f.delete()
  }


  val cexEditionFiles = DataCollector.filesInDir(cexEditions, "cex")
  for (f <- cexEditionFiles.toSeq) {
    f.delete()
  }

  val authListFiles = DataCollector.filesInDir(authListDir, "cex")
  for (f <- authListFiles) {
    if (f.getName() != "catalog.cex") {f.delete() }
  }

}


/** Compose CEX header for library by replacing
* template values for name and urn of release in library.cex.
*/
def libraryHeader(releaseId: String, cexId: String = "all"): String = {
  val src = Source.fromFile("archive/library.cex").getLines.toVector.mkString("\n")
  val modified = src.replaceFirst("RELEASE_NAME_VALUE",
    s"name#Homer Multitext project, release ${releaseId}"
  ).replaceFirst("RELEASE_URN_VALUE",
    s"urn#urn:cite2:hmt:publications.cex.${releaseId}:${cexId}"
  )
  modified
}

/** Concatenate all CEX source into a single string.
*/
def catTextThings: String = {
  // Five subdirectories of the archive root contain all archival
  // data in CEX format. Three of them deal with texts.
  val textCex = DataCollector.compositeFiles( "archive/editions", "cex")
  val indexCex = DataCollector.compositeFiles("archive/relations", "cex")
  val otherCex = DataCollector.compositeFiles( "archive/other_cex", "cex")

  // Concatenate into a single string:
  List(textCex, otherCex,  indexCex ).mkString("\n\n") + "\n"
}

def releaseTexts(releaseId: String) =  {
  // build single CEX composite and write it out to a file:
  val allCex = libraryHeader(releaseId, "texts") + "\n" + catTextThings
  new PrintWriter(s"release-candidates/hmt-${releaseId}-texts.cex") { write(allCex); close}
}

def updateAuthlists = {
  println("Retrieving personal names data from github...")
  val persnamesUrl = "https://raw.githubusercontent.com/homermultitext/hmt-authlists/master/data/hmtnames.cex"
  val personLines = Source.fromURL(persnamesUrl).getLines.toVector
  new PrintWriter("archive/authlists/hmtnames.cex") {write(personLines.mkString("\n") + "\n"); close;}

  println("Retrieving place names data from github...")
  val placenamesUrl = "https://raw.githubusercontent.com/homermultitext/hmt-authlists/master/data/hmtplaces.cex"
  val placeLines = Source.fromURL(placenamesUrl).getLines.toVector
  new PrintWriter("archive/authlists/hmtplaces.cex") {write(placeLines.mkString("\n") + "\n"); close;}
}


def hmtValidators(lib: CiteLibrary) : Vector[CiteValidator[Any]]= {
  val dsev = DseValidator(lib)
  Vector(dsev)
}

def validateRelease(releaseId: String) : TestResultGroup = {
  val f = s"release-candidates/hmt-${releaseId}.cex"
  val lib = CiteLibrarySource.fromFile(f)
  println("Validating library " + lib.name)

  val rslts = LibraryValidator.validate(lib, hmtValidators(lib))
  val title = "Valdation results for HMT release " + releaseId
  TestResultGroup(title, rslts)
}




/** Build a release of the HMT archive as a CITE library.*/
def buildRelease(releaseId: String) = {
  // Generate intermediate files:
  scholia
  iliad
  // Collect remote data:
  updateAuthlists

  // build single CEX composite and write it out to a file:
  val allCex =  libraryHeader(releaseId) + "\n\n" + catAll
  new PrintWriter(s"release-candidates/hmt-${releaseId}.cex") { write(allCex); close}

  // From the composite, select material for a text-only release
  releaseTexts(releaseId)

  // clean up intermediate files:
  tidy
}


def printValidation(releaseId: String) : Unit = {
  println("Validating release " + releaseId)
  val testResults = validateRelease(releaseId)
  new PrintWriter(s"release-candidates/hmt-${releaseId}-validation.md") { write(testResults.markdown); close}
}

/** Publish a release of the Homer Multitext project archive.
*
* @param releaseId Identifier for the release.
* The value should be the version identifier for this release's URN
* as given in `library.cex`.  E.g., to publish release
* `urn:cite2:hmt:publications.cex.2018a:all`, use `2018a`
* as the value for releaseId.
*/
def release(releaseId: String) =  {
  buildRelease(releaseId)
  printValidation(releaseId)

  println(s"\nRelease ${releaseId} is available in release-candidates/hmt-${releaseId}.cex with accompanying report on validation in release-candidates/hmt-${releaseId}-validation.md\n")

}

println("\nBuild and vadidate a release of the HMT archive:")
println("\n\trelease(RELEASE_ID)")

println("\nBuild a CITE library without validating:")
println("\n\tbuildRelease(RELEASE_ID)")

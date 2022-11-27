package org.hl7.fhir.igtools.renderers;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.utilities.MarkDownProcessor;
import org.hl7.fhir.utilities.StringPair;
import org.hl7.fhir.utilities.TextFile;
import org.hl7.fhir.utilities.Utilities;
import org.hl7.fhir.utilities.VersionUtilities;
import org.hl7.fhir.utilities.json.model.JsonArray;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.json.parser.JsonParser;
import org.hl7.fhir.utilities.npm.NpmPackage;


public class PublicationChecker {

  private String folder; // the root folder being built
  private String historyPage;
  private MarkDownProcessor mdEngine;

  public PublicationChecker(String folder, String historyPage, MarkDownProcessor markdownEngine) {
    super();
    this.folder = folder;
    this.historyPage = historyPage;
    this.mdEngine = markdownEngine;
  }
  
  /**
   * returns html for the qa page 
   * @param string 
   * @param c 
   * @param errors2 
   * 
   * @return
   * @throws IOException 
   */
  public String check() throws IOException {
    List<String> messages = new ArrayList<>();
    List<StringPair> summary = new ArrayList<>();
    checkFolder(messages, summary);
    StringBuilder bs = new StringBuilder();
    if (summary.size() > 0) {
      bs.append("<table class=\"grid\">\r\n");
      for (StringPair p : summary) {
        if ("descmd".equals(p.getName())) {
          bs.append(" <tr><td>"+Utilities.escapeXml(p.getName())+"</d><td>"+p.getValue()+"</td></tr>\r\n");
        } else {
          bs.append(" <tr><td>"+Utilities.escapeXml(p.getName())+"</d><td>"+Utilities.escapeXml(p.getValue())+"</td></tr>\r\n");
        }
      }      
      bs.append("</table>\r\n");
    }
    if (messages.size() == 0) {
      return bs.toString()+"No Information found";
    } else if (messages.size() == 1) {
      return bs.toString()+messages.get(0);
    } else {
      StringBuilder b = new StringBuilder();
      b.append("<ul>");
      for (String s : messages) {
        b.append("<li>");
        b.append(s);
        b.append("</li>\r\n");
      }
      b.append("</ul>\r\n");
      return bs.toString()+b.toString();
    }
  }

  private void checkFolder(List<String> messages, List<StringPair> summary) throws IOException {
    check(messages, !exists("package-list.json"), "The file package-list.json should not exist in the root folder"+mkError());
    check(messages, !exists("output", "package-list.json"), "The file package-list.json should not exist in generated output"+mkError());
    if (check(messages, exists("output", "package.tgz"), "No output package found - can't check publication details"+mkWarning())) {
      NpmPackage npm = null;
      try {
        npm = NpmPackage.fromPackage(new FileInputStream(Utilities.path(folder, "output", "package.tgz")));
      } catch (Exception e) {
        check(messages, false, "Error reading package: "+e.getMessage()+mkError());
      }
      if (npm != null) {
        checkPackage(messages, npm);  
        String dst = determineDestination(npm);
        JsonObject pl = null;
        try {
          pl = readPackageList(dst);
        } catch (Exception e) {
          if (e.getMessage() != null && e.getMessage().contains("404")) {
            messages.add("<span title=\""+Utilities.escapeXml(e.getMessage())+"\">This IG has never been published</span>"+mkInfo());
          } else {            
            check(messages, false, "Error fetching package-list from "+dst+": "+e.getMessage()+mkError());
          }
        }
        checkExistingPublication(messages, npm, pl);
        if (check(messages, exists("publication-request.json"), "No publication request found"+mkInfo())) {
          checkPublicationRequest(messages, npm, pl, summary);
        } 
      }
    }
  }

  private void checkPackage(List<String> messages, NpmPackage npm) {
    if (check(messages, !"current".equals(npm.version()), "The version of the IG is 'current' which is not valid. It should have a version X.Y.z-cibuild"+mkError())) {
      check(messages, VersionUtilities.isSemVer(npm.version()), "Version '"+npm.version()+"' does not conform to semver rules"+mkError());
    }
    check(messages, Utilities.pathURL(npm.canonical(), "history.html").equals(historyPage), "History Page '"+Utilities.escapeXml(historyPage)+"' is wrong (ig.json#paths/history) - must be '"+
       Utilities.escapeXml(Utilities.pathURL(npm.canonical(), "history.html"))+"'"+mkError());
  }

  private void checkExistingPublication(List<String> messages, NpmPackage npm, JsonObject pl) {
    if (pl != null) {
      check(messages, npm.name().equals(pl.asString("package-id")), "Package ID mismatch. This package is "+npm.name()+" but the website has "+pl.asString("package-id")+mkError());
      check(messages, npm.canonical().equals(pl.asString("canonical")), "Package canonical mismatch. This package canonical is "+npm.canonical()+" but the website has "+pl.asString("canonical")+mkError());
      check(messages, !hasVersion(pl, npm.version()), "Version "+npm.version()+" has already been published"+mkWarning());
    } else {
      check(messages, npm.version().startsWith("0.1") || npm.version().contains("-"), "This IG has never been published, so the version should start with '0.' or include a patch version e.g. '-ballot'"+mkWarning());
    }  
  }

  private void checkPublicationRequest(List<String> messages, NpmPackage npm, JsonObject pl, List<StringPair> summary) throws IOException {
    JsonObject pr = null;
    try {
      pr = JsonParser.parseObjectFromFile(Utilities.path(folder, "publication-request.json"));
    } catch (Exception e) {
      check(messages, false, "Error parsing publication-request.json: "+e.getMessage()+mkError());
      return;
    }    
    if (check(messages, pr.has("package-id"), "No package id found in publication request (required for cross-check)"+mkError())) {
      if (check(messages, npm.name().equals(pr.asString("package-id")), "Publication Request is for '"+pr.asString("package-id")+"' but package is "+npm.name()+mkError())) {
        summary.add(new StringPair("package-id", pr.asString("package-id")));
      }
    }
    if (check(messages, pr.has("version"), "No publication request version found"+mkError())) {
      String v = pr.asString("version");
      if (check(messages, npm.version().equals(v), "Publication Request is for v'"+pr.asString("version")+"' but package version is v"+npm.version()+mkError())) {
        summary.add(new StringPair("version", pr.asString("version")));        
      }
      if (pl != null) {
        JsonObject plv = getVersionObject(v, pl);
        if (!check(messages, plv == null, "Publication Requet is for version v"+v+" which is already publisehd"+mkError())) {
          summary.clear();
          return;          
        }
        String cv = getLatestVersion(pl);
        check(messages, cv == null || VersionUtilities.isThisOrLater(cv, v), "Proposed version v"+v+" is older than already published version v"+cv+mkError());
      }
    }
    if (check(messages, pr.has("path"), "No publication request path found"+mkError())) {
      if (check(messages, pr.asString("path").startsWith(npm.canonical()), "Proposed path for this publication does not start with the canonical URL ("+pr.asString("path")+" vs "+npm.canonical() +")"+mkError())) {
        summary.add(new StringPair("path", pr.asString("path")));                        
      }
    }
    boolean milestone = pr.asBoolean("milestone");
    if (milestone) {
      if (check(messages, !npm.version().contains("-"), "This release is labelled as a milestone, so should not have a patch version ("+npm.version() +")"+mkWarning())) {
        summary.add(new StringPair("milestone", pr.asString("milestone")));        
      }
    } else {
      if (check(messages, npm.version().contains("-"), "This release is not labelled as a milestone, so should have a patch version ("+npm.version() +")"+mkWarning())) {
        summary.add(new StringPair("milestone", pr.asString("milestone")));                
      }
    }
    if (check(messages, pr.has("status"), "No publication request status found"+mkError())) {
      if (check(messages, isValidStatus(pr.asString("status")), "Proposed status for this publication is not valid (valid values: release|trial-use|update|qa-preview|ballot|draft|normative+trial-use|normative|informative)"+mkError())) {
        summary.add(new StringPair("status", pr.asString("status")));                        
      }
    }
    if (check(messages, pr.has("sequence"), "No publication request sequence found (sequence is e.g. R1, and groups all the pre-publications together. if you don't have a lifecycle like that, just use 'Releases' or 'Publications')"+mkError())) {
      if (pl != null) {
        String seq = getCurrentSequence(pl);
        check(messages, pr.asString("sequence").equals(seq), "This publication will finish the sequence '"+seq+"' and start a new sequence '"+pr.asString("sequence")+"'"+mkInfo());
      }
      summary.add(new StringPair("sequence", pr.asString("sequence")));                        
    }

    if (check(messages, pr.has("desc") || pr.has("descmd") , "No publication request description found"+mkError())) {
      check(messages, pr.has("desc"), "No publication request desc found (it is recommended to provide a shorter desc as well as descmd"+mkWarning());
      if (pr.has("desc")) {
        summary.add(new StringPair("desc", pr.asString("desc")));                        
      }
    }
    if (pr.has("descmd")) {
      String md = pr.asString("descmd");
      if (md.startsWith("@")) {
        File mdFile = new File(Utilities.path(folder, md.substring(1)));
        if (check(messages, mdFile.exists(), "descmd references the file "+md.substring(1)+" but it doesn't exist")) {
          md = TextFile.fileToString(mdFile);
        }
      }
      check(messages, !md.contains("'"), "descmd cannot contain a '"+mkError());
      check(messages, !md.contains("\""), "descmd cannot contain a \""+mkError());
      summary.add(new StringPair("descmd", mdEngine.process(md, "descmd")));                        
    }
    if (pr.has("changes")) {
      summary.add(new StringPair("changes", pr.asString("changes")));                        
      if (check(messages, !Utilities.isAbsoluteUrl(pr.asString("changes")), "Publication request changes must be a relative URL"+mkError())) {
      }
    }
    if (pl == null) {
      if (check(messages, pr.has("category"), "No publication request category found (needed for first publication - consult FHIR product director for a value"+mkError())) {
        summary.add(new StringPair("category", pr.asString("category")));                                
      }
      if (check(messages, pr.has("title"), "No publication request title found (needed for first publication)"+mkError())) {
        summary.add(new StringPair("title", pr.asString("title")));                                
      }
      if (check(messages, pr.has("introduction"), "No publication request introduction found (needed for first publication)"+mkError())) {
        summary.add(new StringPair("introduction", pr.asString("introduction")));                                
      }
      if (check(messages, pr.has("ci-build"), "No publication request ci-build found (needed for first publication)"+mkError())) {
        summary.add(new StringPair("ci-build", pr.asString("ci-build")));                                
      }
    } else {
      check(messages, !pr.has("category"), "Publication request category found (not allowed after first publication"+mkError());
      check(messages, !pr.has("title"), "Publication request title found (not allowed after first publication)"+mkError());
      check(messages, !pr.has("introduction"), "Publication request introduction found (not allowed after first publication)"+mkError());
      check(messages, !pr.has("ci-build"), "Publication request ci-build found (not allowed after first publication)"+mkError());
    }
    check(messages, !pr.has("date"), "Cannot specify a date of publication in the request"+mkError());
    check(messages, !pr.has("canonical"), "Cannot specify a canonical in the request"+mkError());
    
  }

  private JsonObject getVersionObject(String v, JsonObject pl) {
    for (JsonObject j : pl.getJsonArray("list").asJsonObjects()) {
      String vl = j.asString("version");
      if (v.equals(vl)) {
        return j;
      }
    }
    return null;
  }
  
  private String mkError() {
    return " <img src=\"icon-error.gif\" height=\"16px\" width=\"16px\"/>";
  }

  private String mkWarning() {
    return " <img src=\"icon-warning.png\" height=\"16px\" width=\"16px\"/>";
  }

  private String mkInfo() {
    return " <img src=\"information.png\" height=\"16px\" width=\"16px\"/>";
  }

  private boolean isValidStatus(String str) {
    return Utilities.existsInList(str, "release", "trial-use", "update", "qa-preview", "ballot", "draft", "normative+trial-use", "normative", "informative");
  }

  private String getCurrentSequence(JsonObject pl) {
    String cv = null;
    String res = null;
    for (JsonObject j : pl.getJsonArray("list").asJsonObjects()) {
      String v = j.asString("version");
      if (!Utilities.noString(v) && !"current".equals(v)) {
        if (cv == null || VersionUtilities.isThisOrLater(cv, v)) {
          cv = v;
          res = j.asString("sequence");
        }
      }
    }
    return res;
  }

  private String getLatestVersion(JsonObject pl) {
    String cv = null;
    for (JsonObject j : pl.getJsonArray("list").asJsonObjects()) {
      String v = j.asString("version");
      if (!Utilities.noString(v)) {
        if (cv == null || VersionUtilities.isThisOrLater(v, cv)) {
          cv = v;
        }
      }
    }
    return cv;
  }

  private boolean hasVersion(JsonObject pl, String version) {
    JsonArray list = pl.getJsonArray("list");
    if (list != null) {
      for (JsonObject o : list.asJsonObjects()) {
        if (o.has("version") && o.asString("version").equals(version)) {
          return true;
        }
      }
    }
    return false;
  }
  
  private JsonObject readPackageList(String dst) throws IOException {
    return JsonParser.parseObjectFromUrl(Utilities.pathURL(dst, "package-list.json"));
  }

  private String determineDestination(NpmPackage npm) {
    return npm.canonical();
  }

  private boolean check(List<String> messages, boolean test, String error) {
    if (!test) {
      messages.add(error);
    }
    return test;
  }

  private boolean exists(String... parts) throws IOException {
    List<String> p = new ArrayList<>();
    p.add(folder);
    for (String s : parts) {
      p.add(s);
    }
    File f = new File(Utilities.path(p.toArray(new String[] {})));
    return f.exists();
  }
  
}

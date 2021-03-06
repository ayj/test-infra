package org.istio.testutils

GIT_SHA = ''
GIT_SHORT_SHA = ''
BUCKET = ''
NOTIFY_LIST = ''
DEFAULT_SLAVE_LABEL = ''


def initialize() {
  setVars()
  stashSourceCode()
  setArtifactsLink()
}

def setGit() {
  writeFile(
      file: "${env.HOME}/.gitconfig",
      text: '''
[user]
        name = istio-testing
        email = istio-testing@gmail.com
[remote "origin"]
        fetch = +refs/pull/*/head:refs/remotes/origin/pr/*''')
}

def setVars() {
  BUCKET = env.BUCKET
  DEFAULT_SLAVE_LABEL = env.DEFAULT_SLAVE_LABEL
  NOTIFY_LIST = env.NOTIFY_LIST
}

// In a pipeline, multiple scm checkout might checkout different version of the code.
// Stashing source code to make sure that all pipeline branches uses the same version.
// See JENKINS-35245 bug for more info.
def stashSourceCode(postcheckout_call = null) {
  // Checking out code
  retry(10) {
    // Timeout after 5 minute
    timeout(5) {
      checkout(scm)
    }
    sleep(5)
  }
  updateSubmodules()
  if (postcheckout_call != null) {
    postcheckout_call()
  }
  // Setting source code related global variable once so it can be reused.
  GIT_SHA = getRevision()
  GIT_SHORT_SHA = GIT_SHA.take(7)
  echo('Stashing source code')
  fastStash('src-code', '.')
}

// Checks whether a remote path exists
def pathExistsCloudStorage(filePath) {
  def status = sh(returnStatus: true, script: "gsutil stat ${filePath}")
  return status == 0
}

// Checking out code to the current directory
def checkoutSourceCode() {
  deleteDir()
  echo('Unstashing source code')
  fastUnstash('src-code')
  sh("git status")
  setGit()
}

// Generates the archive path based on the bucket, git_sha and name
def stashArchivePath(name) {
  return "gs://${BUCKET}/${GIT_SHA}/tmp/${name}.tar.gz"
}

// pipeline stash/unstash is too slow as it stores and retrieve data from Jenkins.
// Poor man's stash implementation using Cloud Storage
def fastStash(name, stashPaths) {
  // Checking if archive already exists
  def archivePath = stashArchivePath(name)
  if (!pathExistsCloudStorage(archivePath)) {
    echo("Stashing ${stashPaths} to ${archivePath}")
    retry(5) {
      sh("tar czf - ${stashPaths} | gsutil " +
          "-h Content-Type:application/x-gtar cp - ${archivePath}")
      sleep(5)
    }
  }
}

// This only trigger when SUBMODULES_UPDATE build parameter is set.
// Format is FILE1:KEY1:VALUE1,FILE2:KEY2:VALUE2
// Which will update the key1 in file1 with the new value
// and key2 in file2 with the value2 and create a commit for each change
def updateSubmodules() {
  def submodules_update = params.get('SUBMODULES_UPDATE')
  if (submodules_update == '' || submodules_update == null) {
    return
  }
  def res = libraryResource('update-submodules')
  def remoteFile = '/tmp/update-submodules'
  writeFile(file: remoteFile, text: res)
  sh("chmod +x ${remoteFile}")
  sh("${remoteFile} -s ${submodules_update}")
}

// Unstashing data to current directory.
def fastUnstash(name) {
  def archivePath = stashArchivePath(name)
  retry(5) {
    sh("gsutil cp ${archivePath} - | tar zxf - ")
    sleep(5)
  }
}

// Sets an artifacts links to the Build.
def setArtifactsLink() {
  def url = "https://console.cloud.google.com/storage/browser/${BUCKET}/${GIT_SHA}"
  def html = """
<!DOCTYPE HTML>
Find <a href='${url}'>artifacts</a> here
"""
  def artifactsHtml = 'artifacts.html'
  writeFile(file: artifactsHtml, text: html)
  archive(artifactsHtml)
}

// Finds the revision from the source code.
def getRevision() {
  // Code needs to be checked out for this.
  return sh(returnStdout: true, script: 'git rev-parse --verify HEAD').trim()
}

return this
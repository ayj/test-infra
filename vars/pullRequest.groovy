/*
Updates pull request based on status.
*/

def call(utils, Closure body) {
  def success = true
  utils.updatePullRequest('run')
  try {
    body()
  } catch (Exception e) {
    success = false
    throw e
  } finally {
    utils.updatePullRequest('verify', success)
  }
}

return this
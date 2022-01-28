/*
 * Copyright (c) 2018 - 2022 TomTom N.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tomtom.hopic

import com.tomtom.hopic.BaseGitPullRequest

public class BitbucketPullRequest extends BaseGitPullRequest {
  private url
  private info = null
  private credentialsId
  private restUrl = null
  private baseRestUrl = null
  private keyIds = [:]

  BitbucketPullRequest(steps, url, credentialsId, refspec) {
    super(steps, refspec)
    this.url = url
    this.credentialsId = credentialsId

    if (this.url != null) {
      this.restUrl = url
        .replaceFirst(/(\/projects\/)/, '/rest/api/1.0$1')
        .replaceFirst(/\/overview$/, '')
      this.baseRestUrl = this.restUrl
        .replaceFirst(/(\/rest)\/.*/, '$1')
    }
  }

  @NonCPS
  private List find_username_replacements(String message) {
    def m = message =~ /(?<!\\)(?<!\S)@(\w+)/

    def user_replacements = []

    m.each { match ->
      def username = match[1]
      if (!username) {
        return
      }

      user_replacements.add([
          username,
          m.start(),
          m.end(),
      ])
    }

    return user_replacements
  }

  private def get_info(allow_cache = true) {
    if (allow_cache && this.info) {
      return this.info
    }
    if (url == null
     || !url.contains('/pull-requests/')) {
     return null
    }
    def info = steps.readJSON(text: steps.httpRequest(
        url: restUrl,
        httpMode: 'GET',
        authentication: credentialsId,
      ).content)
    def merge = steps.readJSON(text: steps.httpRequest(
        url: restUrl + '/merge',
        httpMode: 'GET',
        authentication: credentialsId,
      ).content)
    if (merge.containsKey('canMerge')) {
      info['canMerge'] = merge['canMerge']
    }
    if(merge.containsKey('vetoes')) {
      info['vetoes'] = merge['vetoes']
    }

    // Expand '@user' tokens in pull request description to 'Full Name <Full.Name@example.com>'
    // because we don't have this mapping handy when reading git commit messages.
    if (info.containsKey('description')) {
      def users = [:]

      def user_replacements = find_username_replacements(info.description)

      int last_idx = 0
      String new_description = ''
      user_replacements.each { repl ->
        def (username, start, end) = repl
        if (!users.containsKey(username)) {
          def response = steps.httpRequest(
              url: "${baseRestUrl}/api/1.0/users/${username}",
              httpMode: 'GET',
              authentication: credentialsId,
              validResponseCodes: '200,404',
            )
          def json = response.content ? steps.readJSON(text: response.content) : [:]
          if (response.status == 200) {
            users[username] = json
          } else {
            def errors = json.getOrDefault('errors', [])
            def msg = errors ? errors[0].getOrDefault('message', '') : ''
            steps.println("\033[31m[error] could not find BitBucket user '${username}'${msg ? ': ' : ''}${msg}\033[39m")
          }
        }

        if (users.containsKey(username)) {
          def user = users[username]

          def str = user.getOrDefault('displayName', user.getOrDefault('name', username))
          if (user.emailAddress) {
            str = "${str} <${user.emailAddress}>"
          }

          // Because Groovy is unable to obtain empty substrings
          if (last_idx != start)
            new_description = new_description + info.description[last_idx..start - 1]
          new_description = new_description + str
          last_idx = end
        }
      }

      // Because Groovy is unable to obtain an empty trailing string
      if (last_idx != info.description.length())
        new_description = new_description + info.description[last_idx..-1]
      info.description = new_description.replace('\r\n', '\n')
    }

    info['author_time'] = info.getOrDefault('updatedDate', steps.currentBuild.timeInMillis) / 1000.0
    info['commit_time'] = steps.currentBuild.startTimeInMillis / 1000.0
    this.info = info
    return info
  }

  @Override
  public boolean maySubmit(String target_commit, String source_commit, boolean allow_cache = true) {
    if (!super.maySubmitImpl(target_commit, source_commit, allow_cache)) {
      return false
    }
    def cur_cr_info = this.get_info(allow_cache)
    if (cur_cr_info == null
     || cur_cr_info.fromRef == null
     || cur_cr_info.fromRef.latestCommit != source_commit) {
      steps.println("\033[31m[error] failed to get pull request info from BitBucket for ${source_commit}\033[39m")
      return false
    }
    if (!cur_cr_info.canMerge) {
      steps.println("\033[36m[info] not submitting because the BitBucket merge criteria are not met\033[39m")
      steps.currentBuild.description = "Not submitting: Bitbucket merge criteria not met"
      if (cur_cr_info.vetoes) {
        steps.println("\033[36m[info] the following merge condition(s) are not met: \033[39m")
        cur_cr_info.vetoes.each { veto ->
          if (veto.summaryMessage) {
            steps.println("\033[36m[info] summary: ${veto.summaryMessage}\033[39m")
            if (veto.detailedMessage) {
              steps.println("\033[36m[info]   details: ${veto.detailedMessage}\033[39m")
            }
          }
        }
      } else {
        steps.println("\033[36m[info] no information about why merge failed available\033[39m")
      }
    }
    return cur_cr_info.canMerge
  }

  @Override
  public void abort_if_changed(String source_remote) {
    super.abort_if_changed_impl(source_remote)

    if (!this.info
      // we don't care about builds that weren't going to be merged anyway
     || !this.info.canMerge)
      return

    final old_cr_info = this.info
    def cur_cr_info = this.get_info(/* allow_cache=*/ false)
    // keep the cache intact as it's used to generate merge commit messages
    this.info = old_cr_info

    // Ignore the current INPROGRESS build from the merge vetoes
    for (int i = cur_cr_info.getOrDefault('vetoes', []).size() - 1; i >= 0; i--) {
      if (cur_cr_info.vetoes[i].summaryMessage == 'Not all required builds are successful yet') {
        if (!cur_cr_info.canMerge
         && cur_cr_info.vetoes.size() == 1) {
          cur_cr_info.canMerge = true
        }
        cur_cr_info.vetoes.remove(i)
        break
      }
    }

    String msg = ''
    if (!cur_cr_info.canMerge) {
      msg += '\n\033[33m[warning] no longer submitting because the BitBucket merge criteria are no longer met\033[39m'
      if (cur_cr_info.vetoes) {
        msg += '\n\033[36m[info] the following merge condition(s) are not met:'
        cur_cr_info.vetoes.each { veto ->
          if (veto.summaryMessage) {
            msg += "\n[info] summary: ${veto.summaryMessage}"
            if (veto.detailedMessage) {
              msg += "\n[info]   details: ${veto.detailedMessage}"
            }
          }
        }
        msg += '\033[39m'
      }
    }
    final String old_title = old_cr_info.getOrDefault('title', steps.env.CHANGE_TITLE)
    final String cur_title = cur_cr_info.getOrDefault('title', steps.env.CHANGE_TITLE)
    if (cur_title.trim() != old_title.trim()) {
      msg += '\n\033[33m[warning] no longer submitting because the change request\'s title changed\033[39m'
      msg += "\n\033[36m[info] old title: '${old_title}'"
      msg +=         "\n[info] new title: '${cur_title}'\033[39m"
    }
    final String old_description = old_cr_info.containsKey('description') ? old_cr_info.description.trim() : null
    final String cur_description = cur_cr_info.containsKey('description') ? cur_cr_info.description.trim() : null
    if (cur_description != old_description) {
      msg += '\n\033[33m[warning] no longer submitting because the change request\'s description changed\033[39m'
      msg += '\n\033[36m[info] old description:'
      if (old_description == null) {
        msg += ' null'
      } else {
        line_split(old_description).each { line ->
          msg += "\n[info]     ${line}"
        }
      }
      msg += '\n[info] new description:'
      if (cur_description == null) {
        msg += ' null'
      } else {
        line_split(cur_description).each { line ->
          msg += "\n[info]     ${line}"
        }
      }
      msg += '\033[39m'
    }

    // trim() that doesn't strip \033
    while (msg && msg[0] == '\n')
      msg = msg[1..-1]

    if (msg) {
      steps.println(msg)
      steps.currentBuild.result = 'ABORTED'
      if (!cur_cr_info.canMerge) {
        steps.currentBuild.description = "No longer submitting: Bitbucket merge criteria no longer met"
        steps.error("This build is outdated. Merge criteria of its change request are no longer met.")
      } else {
        steps.currentBuild.description = "No longer submitting: change request's metadata changed since start"
        steps.error("This build is outdated. Metadata of its change request changed.")
      }
    }
  }

  @Override
  public Map apply(String cmd, String source_remote, pip_constraints_file) {
    def change_request = this.get_info()
    def extra_params = ''
    if (change_request.containsKey('description')) {
      extra_params += ' --description=' + shell_quote(change_request.description)
    }

    // Record approving reviewers for auditing purposes
    def approvers = change_request.getOrDefault('reviewers', []).findAll { reviewer ->
        return reviewer.approved
      }.collect { reviewer ->
        def str = reviewer.user.getOrDefault('displayName', reviewer.user.name)
        if (reviewer.user.emailAddress) {
          str = "${str} <${reviewer.user.emailAddress}>"
        }
        return str + ':' + reviewer.lastReviewedCommit
      }.sort()
    approvers.each { approver ->
      extra_params += ' --approved-by=' + shell_quote(approver)
    }

    if (this.source_commit == null) {
      // Pin to the head commit of the PR to ensure every node builds the same version, even when the PR gets updated while the build runs
      this.source_commit = this.current_source_commit(source_remote)
    }
    def cr_author = change_request.getOrDefault('author', [:]).getOrDefault('user', [:])
    def merge_bundle = steps.pwd(tmp: true) + '/merge-transfer.bundle'
    def output = line_split(steps.sh(script: cmd
                                + ' prepare-source-tree'
                                + ' --author-name=' + shell_quote(cr_author.getOrDefault('displayName', steps.env.CHANGE_AUTHOR ?: "Unknown user"))
                                + ' --author-email=' + shell_quote(cr_author.getOrDefault('emailAddress', steps.env.CHANGE_AUTHOR_EMAIL ?: ""))
                                + ' --author-date=' + shell_quote(String.format("@%.3f", change_request.author_time))
                                + ' --commit-date=' + shell_quote(String.format("@%.3f", change_request.commit_time))
                                + ' --bundle=' + shell_quote(merge_bundle)
                                + (pip_constraints_file ? (' --constraints=' + pip_constraints_file) : '')
                                + ' merge-change-request'
                                + ' --source-remote=' + shell_quote(source_remote)
                                + ' --source-ref=' + shell_quote(this.source_commit)
                                + ' --change-request=' + shell_quote(change_request.getOrDefault('id', steps.env.CHANGE_ID))
                                + ' --title=' + shell_quote(change_request.getOrDefault('title', steps.env.CHANGE_TITLE))
                                + extra_params,
                          label: 'Hopic: preparing source tree',
                          returnStdout: true)).findAll{it.size() > 0}
    if (output.size() <= 0) {
      return null
    }
    def rv = [
        commit: output.remove(0),
        bundle: merge_bundle,
      ]
    if (output.size() > 0) {
      rv.version = output.remove(0)
    }
    return rv
  }

  @Override
  public void notify_build_result(String job_name, String branch, String commit, String result, boolean exclude_branches_filled_with_pr_branch_discovery) {
    def state = (result == 'STARTING'
        ? 'INPROGRESS'
        : (result == 'SUCCESS' ? 'SUCCESSFUL' : 'FAILED')
        )

    def description = steps.currentBuild.description
    if (!description) {
      if        (result == 'STARTING') {
        description = 'The build is in progress...'
      } else if (result == 'SUCCESS') {
        description = 'This change request looks good.'
      } else if (result == 'UNSTABLE') {
        description = 'This change request has test failures.'
      } else if (result == 'FAILURE') {
        description = 'There was a failure building this change request.'
      } else if (result == 'ABORTED') {
        description = 'The build of this change request was aborted.'
      } else {
        description = 'Something is wrong with the build of this change request.'
      }
    }

    // It is impossible to get this Bitbucket branch plugin trait setting via groovy, therefore it is a parameter here
    if (!exclude_branches_filled_with_pr_branch_discovery) {
      branch = "${steps.env.JOB_BASE_NAME}"
    }
    def key = "${job_name}/${branch}"

    if (!this.keyIds[key]) {
      // We could use java.security.MessageDigest instead of relying on a node. But that requires extra script approvals.
      assert steps.env.NODE_NAME != null, "notify_build_result must be executed on a node the first time"
      this.keyIds[key] = steps.sh(script: "echo -n ${shell_quote(key)} | md5sum",
                                  label: 'Hopic (internal): generating unique build key',
                                  returnStdout: true).substring(0, 32)
    }
    def keyid = this.keyIds[key]

    def build_status = JsonOutput.toJson([
        state: state,
        key: keyid,
        url: steps.env.BUILD_URL,
        name: steps.currentBuild.fullDisplayName,
        description: description,
      ])
    steps.httpRequest(
        url: "${baseRestUrl}/build-status/1.0/commits/${commit}",
        httpMode: 'POST',
        contentType: 'APPLICATION_JSON',
        requestBody: build_status,
        authentication: credentialsId,
        validResponseCodes: '204',
      )
  }
}

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

public class GithubPullRequest extends BaseGitPullRequest {
  private String url
  private String credentialsId
  private String restUrl = null
  private Map info = null
  private keyIds = [:]

  GithubPullRequest(steps, String url, String credentialsId, String refspec) {
    super(steps, refspec)
    this.url = url
    this.credentialsId = credentialsId

    if (this.url != null) {
      this.restUrl = url.replaceFirst(/^([^:]+:[\/]*)(.+?)\/(.+?)\/(.+?)\/pull\/(\d+)$/, '$1api.$2/repos/$3/$4/pulls/$5')
    }
  }

  private def get_info(allow_cache = true) {
    if (allow_cache && this.info) {
      return this.info
    }
    if (url == null
     || !url.contains('/pull/')) {
     return null
    }
    def info = steps.readJSON(text: steps.httpRequest(
        url: restUrl,
        httpMode: 'GET',
        authentication: credentialsId,
      ).content)

    info['author_time'] = info.getOrDefault('updated_at', String.format("@%.3f", steps.currentBuild.timeInMillis / 1000.0))
    info['commit_time'] = String.format("@%.3f", steps.currentBuild.startTimeInMillis / 1000.0)
    this.info = info
    return info
  }

  @Override
  public Map apply(String cmd, String source_remote, pip_constraints_file) {
    def change_request = this.get_info()
    def extra_params = ''
    if (change_request.containsKey('body')) {
      extra_params += ' --description=' + shell_quote(change_request.body)
    }

    if (this.source_commit == null) {
      // Pin to the head commit of the PR to ensure every node builds the same version, even when the PR gets updated while the build runs
      this.source_commit = this.current_source_commit(source_remote)
    }
    def merge_bundle = steps.pwd(tmp: true) + '/merge-transfer.bundle'
    def output = line_split(steps.sh(script: cmd
                                + ' prepare-source-tree'
                                + ' --author-name=' + shell_quote(steps.env.CHANGE_AUTHOR ?: "Unknown user")
                                + ' --author-email=' + shell_quote(steps.env.CHANGE_AUTHOR_EMAIL ?: "")
                                + ' --author-date=' + shell_quote(change_request.author_time)
                                + ' --commit-date=' + shell_quote(change_request.commit_time)
                                + ' --bundle=' + shell_quote(merge_bundle)
                                + (pip_constraints_file ? (' --constraints=' + pip_constraints_file) : '')
                                + ' merge-change-request'
                                + ' --source-remote=' + shell_quote(source_remote)
                                + ' --source-ref=' + shell_quote(this.source_commit)
                                + ' --change-request=' + shell_quote(change_request.getOrDefault('number', steps.env.CHANGE_ID))
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
}

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

import com.tomtom.hopic.ChangeRequest

public class GithubPullRequest extends ChangeRequest {
  private String url
  private String credentialsId
  private String refspec
  private info = null
  private keyIds = [:]
  private source_commit = null

  GithubPullRequest(steps, String url, String credentialsId, String refspec) {
    super(steps)
    this.url = url
    this.credentialsId = credentialsId
    this.refspec = refspec
  }

  private def current_source_commit(String source_remote) {
    assert steps.env.NODE_NAME != null, "current_source_commit must be executed on a node"
    def (remote_ref, local_ref) = this.refspec.tokenize(':')
    if (remote_ref.startsWith('+'))
      remote_ref = remote_ref.substring(1)

    def refs = line_split(
      steps.sh(
        script: "git ls-remote ${shell_quote(source_remote)}",
        label: 'Hopic: finding last commit of PR',
        returnStdout: true,
      )
    ).collectEntries { line ->
      def (hash, ref) = line.split('\t')
      [(ref): hash]
    }
    return refs[remote_ref] ?: refs["refs/heads/${remote_ref}"] ?: refs["refs/tags/${remote_ref}"]
  }

  @Override
  public void abort_if_changed(String source_remote) {
    if (this.source_commit == null)
      return

    final current_commit = this.current_source_commit(source_remote)
    if (this.source_commit != current_commit) {
      steps.currentBuild.result = 'ABORTED'
      steps.currentBuild.description = 'Aborted: build outdated; change request updated since start'
      steps.error("this build is outdated. Its change request got updated to ${current_commit} (from ${this.source_commit}).")
    }
  }

  @Override
  public Map apply(String cmd, String source_remote, pip_constraints_file) {
    if (this.source_commit == null) {
      // Pin to the head commit of the PR to ensure every node builds the same version, even when the PR gets updated while the build runs
      this.source_commit = this.current_source_commit(source_remote)
    }
    def merge_bundle = steps.pwd(tmp: true) + '/merge-transfer.bundle'
    def output = line_split(steps.sh(script: cmd
                                + ' prepare-source-tree'
                                + ' --author-name=' + shell_quote(steps.env.CHANGE_AUTHOR ?: "Unknown user")
                                + ' --author-email=' + shell_quote(steps.env.CHANGE_AUTHOR_EMAIL ?: "")
                                + ' --bundle=' + shell_quote(merge_bundle)
                                + (pip_constraints_file ? (' --constraints=' + pip_constraints_file) : '')
                                + ' merge-change-request'
                                + ' --source-remote=' + shell_quote(source_remote)
                                + ' --source-ref=' + shell_quote(this.source_commit)
                                + ' --change-request=' + shell_quote(steps.env.CHANGE_ID)
                                + ' --title=' + shell_quote(steps.env.CHANGE_TITLE),
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

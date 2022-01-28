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

public class BaseGitPullRequest extends ChangeRequest {
  protected String refspec = null
  protected String source_commit = null

  BaseGitPullRequest(steps, String refspec) {
    super(steps)
    this.refspec = refspec
  }

  protected def current_source_commit(String source_remote) {
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

  protected void abort_if_changed_impl(String source_remote) {
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
  public void abort_if_changed(String source_remote) {
    abort_if_changed_impl(source_remote)
  }
}

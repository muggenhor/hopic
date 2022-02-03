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

public class ChangeRequest {
  protected steps

  ChangeRequest(steps) {
    this.steps = steps
  }

  protected String shell_quote(word) {
    return "'" + (word as String).replace("'", "'\\''") + "'"
  }

  protected ArrayList line_split(String text) {
    return text.split('\\r?\\n') as ArrayList
  }

  protected boolean maySubmitImpl(String target_commit, String source_commit, boolean allow_cache = true) {
    return !line_split(steps.sh(script: 'LC_ALL=C.UTF-8 TZ=UTC git log ' + shell_quote(target_commit) + '..' + shell_quote(source_commit) + " --pretty='%H:%s' --reverse",
                                label: 'Hopic (internal): retrieving git log',
                                returnStdout: true)
      .trim()).find { line ->
        if (!line) {
          return false
        }
        def (commit, subject) = line.split(':', 2)
        if (subject.startsWith('fixup!') || subject.startsWith('squash!')) {
          steps.println("\033[36m[info] not submitting because commit ${commit} is marked with 'fixup!' or 'squash!': ${subject}\033[39m")
          steps.currentBuild.description = "Not submitting: PR contains fixup! or squash!"
          return true
        }
    }
  }

  public boolean maySubmit(String target_commit, String source_commit, boolean allow_cache = true) {
    return this.maySubmitImpl(target_commit, source_commit, allow_cache)
  }

  public void abort_if_changed(String source_remote) {
    // Default NOP
  }

  public Map getinfo(String cmd) {
    return [:]
  }

  public Map apply(String cmd, String source_remote, pip_constraints_file) {
    assert false : "Change request instance does not override apply()"
  }

  public void notify_build_result(String job_name, String branch, String commit, String result, boolean exclude_branches_filled_with_pr_branch_discovery) {
    // Default NOP
  }
}
